package com.meshpay.ingestion.ratelimiter;

import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = Logger.getLogger(RedisRateLimiter.class.getName());

    private static final String KEY_PREFIX = "rate_limit:";

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refillRate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])

            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])

            if tokens == nil then tokens = capacity end
            if ts == nil then ts = now end

            local elapsed = math.max(0, now - ts)
            local newTokens = math.min(capacity, tokens + math.floor(elapsed * refillRate / 1000))

            if newTokens >= 1 then
                redis.call('HSET', key, 'tokens', newTokens - 1, 'ts', now)
                redis.call('EXPIRE', key, 3600)
                return 1
            else
                redis.call('HSET', key, 'tokens', newTokens, 'ts', ts)
                redis.call('EXPIRE', key, 3600)
                return 0
            end
            """;

    private final StringRedisTemplate redisTemplate;
    private final long capacity;
    private final long refillRate;
    private final DefaultRedisScript<Long> consumeScript;

    public RedisRateLimiter(StringRedisTemplate redisTemplate,
                            @Value("${meshpay.rate-limit.capacity:10}") long capacity,
                            @Value("${meshpay.rate-limit.refill-rate:5}") long refillRate) {
        this.redisTemplate = redisTemplate;
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.consumeScript = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);
    }

    /**
     * Attempt to consume one token from the bridge's rate-limit bucket.
     *
     * <p><b>Fail-closed on Redis outage:</b> if Redis is unreachable or the Lua
     * script errors, this method returns {@code false} (request rejected with 429).
     * This prevents abuse during rate-limiter downtime but means legitimate
     * packets are also rejected while Redis is down. A circuit-breaker pattern
     * (e.g. Resilience4j) could degrade gracefully in the future.</p>
     */
    @Override
    public boolean attemptConsume(String bridgeId) {
        String key = KEY_PREFIX + bridgeId;
        try {
            Long result = redisTemplate.execute(consumeScript, List.of(key),
                    String.valueOf(capacity),
                    String.valueOf(refillRate),
                    String.valueOf(Instant.now().toEpochMilli()));
            return result != null && result == 1L;
        } catch (Exception e) {
            // Fail-closed: reject request when Redis is unavailable rather than
            // allowing unlimited traffic through without rate limiting.
            log.warning("Rate limiter error for bridge " + bridgeId + ": " + e.getMessage());
            return false;
        }
    }

    @Override
    public void reset(String bridgeId) {
        try {
            redisTemplate.delete(KEY_PREFIX + bridgeId);
        } catch (Exception e) {
            log.warning("Rate limiter reset error for bridge " + bridgeId + ": " + e.getMessage());
        }
    }
}