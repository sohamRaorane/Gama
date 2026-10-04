package com.meshpay.ingestion.idempotency;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Claims a packet fingerprint in Redis so only the first submission of that
 * packet is processed (Day 12, Layer 2).
 *
 * <p>Redis key: {@code packet_hash:{packetHash}} — deliberately built from the
 * packet fingerprint only, never from {@code bridgeId}. Bridge A and Bridge B
 * submitting the same packet therefore contend for the SAME key, and only the
 * first one wins. This namespace is separate from the Day 11 rate-limit namespace
 * {@code rate_limit:{bridgeId}}.
 *
 * <p>Redis value: the minimal constant {@code claimed}. The packet itself is
 * never stored here.
 *
 * <p>The claim is created with a single atomic {@code SET key value NX EX ttl}
 * command via {@code setIfAbsent(key, value, ttl, SECONDS)}: {@code NX} means the
 * key is only created when absent, {@code EX} gives it a bounded lifetime. There
 * is deliberately no {@code EXISTS}-then-{@code SET} sequence, which would let two
 * concurrent submissions both observe an absent key and both proceed.
 *
 * <p>The TTL is externalized as {@code meshpay.idempotency.ttl-seconds} so claims
 * expire instead of accumulating forever.
 */
@Service
public class IdempotencyService {

    private static final Logger log = Logger.getLogger(IdempotencyService.class.getName());

    static final String KEY_PREFIX = "packet_hash:";
    static final String CLAIM_VALUE = "claimed";

    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;

    public IdempotencyService(StringRedisTemplate redisTemplate,
                              @Value("${meshpay.idempotency.ttl-seconds:300}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Attempts to claim the packet fingerprint in Redis.
     *
     * <p>Runs one atomic {@code SET packet_hash:{hash} claimed NX EX ttl}.
     * Note that Day 11's rate limiter already rejects with 429 when Redis is
     * unreachable (fail-closed), so a Redis outage is reported before this
     * method is reached rather than being masked as a duplicate.
     *
     * @param packetHash lowercase hex SHA-256 fingerprint from {@link PacketFingerprintService}
     * @return true if this call created the claim (first submission),
     *         false if the packet is already claimed within the TTL window
     */
    public boolean tryClaim(String packetHash) {
        String key = KEY_PREFIX + packetHash;
        Boolean claimed = redisTemplate.opsForValue()
                .setIfAbsent(key, CLAIM_VALUE, ttlSeconds, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(claimed)) {
            log.info("Duplicate packet rejected, claim already held for key " + key);
            return false;
        }
        return true;
    }
}
