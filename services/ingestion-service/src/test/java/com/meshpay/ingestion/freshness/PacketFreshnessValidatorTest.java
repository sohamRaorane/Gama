package com.meshpay.ingestion.freshness;

import com.meshpay.ingestion.exception.InvalidPacketTimestampException;
import com.meshpay.ingestion.exception.StalePacketException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day 13 freshness rules, verified against a FIXED clock so no assertion depends
 * on wall-clock timing.
 *
 * Configured here: window = 300s, skew = 30s (the same defaults as
 * meshpay.packet.* in application.yaml).
 *
 * Boundary contract under test (lower bound inclusive, upper bound inclusive):
 *
 * <pre>
 *   now - 300s &lt;= timestamp &lt;= now + 30s
 * </pre>
 */
class PacketFreshnessValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final long WINDOW_SECONDS = 300;
    private static final long SKEW_SECONDS = 30;

    private PacketFreshnessValidator validator;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        validator = new PacketFreshnessValidator(fixedClock, WINDOW_SECONDS, SKEW_SECONDS);
    }

    /** Builds the timestamp {@code ageSeconds} BEFORE the fixed now. */
    private String ageSecondsAgo(long ageSeconds) {
        return NOW.minusSeconds(ageSeconds).toString();
    }

    /** Builds the timestamp {@code futureSeconds} AFTER the fixed now. */
    private String secondsInFuture(long futureSeconds) {
        return NOW.plusSeconds(futureSeconds).toString();
    }

    // --- Fresh packets -------------------------------------------------------------

    @Test
    void tenSecondOldPacketIsFresh() {
        assertDoesNotThrow(() -> validator.validate(ageSecondsAgo(10)));
    }

    @Test
    void timestampExactlyNowIsFresh() {
        assertDoesNotThrow(() -> validator.validate(NOW.toString()));
    }

    // --- Boundary (must be explicit, never accidental) -----------------------------

    @ParameterizedTest(name = "age {0}s is accepted (inside inclusive window)")
    @CsvSource({"0", "1", "299", "300"})
    void ageInsideWindowIncludingExactBoundaryIsAccepted(long ageSeconds) {
        assertDoesNotThrow(() -> validator.validate(ageSecondsAgo(ageSeconds)),
                "age " + ageSeconds + "s must be accepted: the lower bound (now - window) is inclusive");
    }

    @ParameterizedTest(name = "age {0}s is rejected as stale")
    @CsvSource({"301", "302", "600", "86400"})
    void ageBeyondWindowIsRejectedAsStale(long ageSeconds) {
        StalePacketException ex = assertThrows(StalePacketException.class,
                () -> validator.validate(ageSecondsAgo(ageSeconds)));
        assertEquals("Packet has expired", ex.getMessage());
    }

    @Test
    void exactWindowBoundaryIsDocumentedAsInclusive() {
        // age == window exactly → accepted
        assertDoesNotThrow(() -> validator.validate(ageSecondsAgo(WINDOW_SECONDS)),
                "age == freshness-window-seconds must be accepted (inclusive lower bound)");

        // one second past the window → rejected
        assertThrows(StalePacketException.class, () -> validator.validate(ageSecondsAgo(WINDOW_SECONDS + 1)),
                "age == window + 1 must be rejected");
    }

    // --- Future timestamps ---------------------------------------------------------

    @ParameterizedTest(name = "timestamp {0}s in the future is within allowed clock skew")
    @CsvSource({"1", "15", "30"})
    void futureTimestampWithinSkewIsAccepted(long futureSeconds) {
        assertDoesNotThrow(() -> validator.validate(secondsInFuture(futureSeconds)));
    }

    @ParameterizedTest(name = "timestamp {0}s in the future exceeds allowed clock skew")
    @CsvSource({"31", "60", "3600"})
    void futureTimestampBeyondSkewIsRejected(long futureSeconds) {
        InvalidPacketTimestampException ex = assertThrows(InvalidPacketTimestampException.class,
                () -> validator.validate(secondsInFuture(futureSeconds)));
        assertEquals("Packet timestamp is invalid", ex.getMessage());
        assertTrue(ex.getReason().contains("future"),
                "diagnostic reason must mention the future offset, was: " + ex.getReason());
    }

    @Test
    void largeFutureTimestampCannotBypassTheAgeCheck() {
        // A naive "now - packetTimestamp <= window" check would accept this,
        // because now - futureTime is negative (i.e. 'negative age').
        assertThrows(InvalidPacketTimestampException.class,
                () -> validator.validate(secondsInFuture(86400 * 365)),
                "a one-year-future timestamp must be rejected, not silently treated as fresh");
    }

    // --- Malformed timestamps ------------------------------------------------------

    @Test
    void nullTimestampIsRejected() {
        assertThrows(InvalidPacketTimestampException.class, () -> validator.validate(null));
    }

    @Test
    void blankTimestampIsRejected() {
        assertThrows(InvalidPacketTimestampException.class, () -> validator.validate("   "));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-a-timestamp",
            "2026-13-45T99:99:99Z",
            "2026-10-05",
            "12:00:00"
    })
    void unparseableTimestampIsRejected(String raw) {
        InvalidPacketTimestampException ex = assertThrows(InvalidPacketTimestampException.class,
                () -> validator.validate(raw));
        assertEquals("Packet timestamp is invalid", ex.getMessage());
        assertTrue(ex.getReason().contains("ISO-8601"),
                "reason must explain the parse failure, was: " + ex.getReason());
    }

    // --- UTC-safety ----------------------------------------------------------------

    @Test
    void nonUtcOffsetResolvesToTheSameUtcInstant() {
        // 14:00+02:00 == 12:00Z → exactly now → fresh.
        assertDoesNotThrow(() -> validator.validate("2026-10-05T14:00:00+02:00"));
    }

    @Test
    void nonUtcOffsetBeyondWindowIsStillStale() {
        // 09:59:59+02:00 == 07:59:59Z → age 14401s → stale regardless of offset rendering.
        assertThrows(StalePacketException.class, () -> validator.validate("2026-10-05T09:59:59+02:00"));
    }

    // --- Configuration guards ------------------------------------------------------

    @Test
    void nonPositiveWindowIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new PacketFreshnessValidator(Clock.fixed(NOW, ZoneOffset.UTC), 0, SKEW_SECONDS));
        assertThrows(IllegalArgumentException.class,
                () -> new PacketFreshnessValidator(Clock.fixed(NOW, ZoneOffset.UTC), -1, SKEW_SECONDS));
    }

    @Test
    void negativeClockSkewIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new PacketFreshnessValidator(Clock.fixed(NOW, ZoneOffset.UTC), WINDOW_SECONDS, -1));
    }

    // --- Idempotency ≠ freshness ---------------------------------------------------

    @Test
    void freshnessNeitherReadsNorWritesIdempotencyState() {
        // The validator is a pure time check: two calls on the same timestamp behave
        // identically, proving it cannot itself be the duplicate detector.
        String timestamp = ageSecondsAgo(10);
        assertDoesNotThrow(() -> validator.validate(timestamp));
        assertDoesNotThrow(() -> validator.validate(timestamp));
    }
}
