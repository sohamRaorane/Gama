package com.meshpay.ingestion.freshness;

import com.meshpay.ingestion.exception.InvalidPacketTimestampException;
import com.meshpay.ingestion.exception.StalePacketException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Day 13 freshness gate: rejects packets that are too old, too far in the
 * future, or carry an unparseable timestamp.
 *
 * <p><b>Placement in the pipeline:</b> Redis idempotency claim &rarr;
 * <em>this validator</em> &rarr; {@code HybridCryptoService.decrypt}. A packet
 * that fails here is rejected with 400 before any (comparatively expensive)
 * cryptographic work happens.
 *
 * <p><b>Authoritative timestamp:</b> {@code IngestionRequest.timestamp} — the
 * bridge-declared creation/submission time, already part of the Day 12
 * canonical fingerprint and persisted in {@code packets.timestamp}. HTTP arrival
 * time and server processing time are deliberately NOT used: they would make a
 * replayed packet look fresh forever. {@code PaymentInstruction.signedAt} cannot
 * be used here because it sits inside the ciphertext (only reachable after
 * decryption, i.e. after this check).
 *
 * <p><b>Rule (all arithmetic on UTC {@link Instant}s):</b>
 *
 * <pre>
 *   now - freshnessWindow &lt;= packetTimestamp &lt;= now + clockSkew
 * </pre>
 *
 * With the defaults (window = 300s, skew = 30s):
 * <ul>
 *   <li>age 299s &rarr; accepted</li>
 *   <li>age 300s &rarr; <b>accepted</b> — the lower bound is INCLUSIVE
 *       ({@code !packetTime.isBefore(oldestAllowed)}), so the boundary is a
 *       documented rule rather than an accident of comparison direction</li>
 *   <li>age 301s &rarr; rejected with {@link StalePacketException}</li>
 *   <li>timestamp up to 30s in the future &rarr; accepted (tolerated clock skew)</li>
 *   <li>timestamp beyond 30s in the future &rarr; rejected with
 *       {@link InvalidPacketTimestampException} — a future stamp must never
 *       bypass the age calculation</li>
 * </ul>
 *
 * <p><b>Idempotency ≠ freshness:</b> this check says nothing about whether the
 * packet was already seen — that is exclusively the Redis claim's job. An unseen
 * packet 10 minutes old is "claimable but stale"; the same fresh packet
 * resubmitted is "fresh but duplicate".
 */
@Component
public class PacketFreshnessValidator {

    private final Clock clock;
    private final long freshnessWindowSeconds;
    private final long clockSkewSeconds;

    public PacketFreshnessValidator(Clock clock,
                                    @Value("${meshpay.packet.freshness-window-seconds:300}") long freshnessWindowSeconds,
                                    @Value("${meshpay.packet.clock-skew-seconds:30}") long clockSkewSeconds) {
        if (freshnessWindowSeconds <= 0) {
            throw new IllegalArgumentException("meshpay.packet.freshness-window-seconds must be > 0");
        }
        if (clockSkewSeconds < 0) {
            throw new IllegalArgumentException("meshpay.packet.clock-skew-seconds must be >= 0");
        }
        this.clock = clock;
        this.freshnessWindowSeconds = freshnessWindowSeconds;
        this.clockSkewSeconds = clockSkewSeconds;
    }

    /**
     * Validates that the packet timestamp lies inside the allowed window.
     *
     * @param packetTimestamp ISO-8601 timestamp declared by the bridge (resolved to a UTC instant)
     * @throws InvalidPacketTimestampException if unparseable or beyond the allowed future skew
     * @throws StalePacketException if the packet is older than the freshness window
     */
    public void validate(String packetTimestamp) {
        Instant packetTime = parse(packetTimestamp);
        Instant now = clock.instant();

        Instant oldestAllowed = now.minusSeconds(freshnessWindowSeconds);
        Instant newestAllowed = now.plusSeconds(clockSkewSeconds);

        if (packetTime.isBefore(oldestAllowed)) {
            // Age strictly greater than the window: stale. The lower bound is inclusive.
            throw new StalePacketException();
        }
        if (packetTime.isAfter(newestAllowed)) {
            // Significantly future-dated: reject rather than let it bypass the age check.
            throw new InvalidPacketTimestampException(
                    "timestamp is " + Duration.between(now, packetTime).getSeconds()
                            + "s in the future, max allowed skew is " + clockSkewSeconds + "s");
        }
    }

    private Instant parse(String packetTimestamp) {
        if (packetTimestamp == null || packetTimestamp.isBlank()) {
            throw new InvalidPacketTimestampException("timestamp is blank");
        }
        try {
            // Instant.parse is zone-independent: it resolves any ISO-8601 offset to a
            // UTC epoch second, so no local timezone can influence the comparison.
            return Instant.parse(packetTimestamp);
        } catch (DateTimeParseException ex) {
            throw new InvalidPacketTimestampException("timestamp is not a valid ISO-8601 instant");
        }
    }
}
