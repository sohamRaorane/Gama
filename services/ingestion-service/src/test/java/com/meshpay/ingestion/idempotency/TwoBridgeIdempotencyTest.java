package com.meshpay.ingestion.idempotency;

import com.meshpay.ingestion.dto.IngestionRequest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day 12 core requirement: two different bridges submitting the exact same logical
 * packet must produce the same fingerprint and therefore compete for the same Redis
 * claim — only the first one wins.
 */
@Testcontainers
class TwoBridgeIdempotencyTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.10.1")
            .withExposedPorts(6379);

    private static final long TTL_SECONDS = 300;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private PacketFingerprintService fingerprintService;
    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        fingerprintService = new PacketFingerprintService(new PacketCanonicalizer());
        idempotencyService = new IdempotencyService(redisTemplate, TTL_SECONDS);
        // The container is shared across this class, so each test starts without any
        // inherited claim and cannot be affected by the order tests run in.
        // The fingerprint is bridge-independent, so one packet per shape is enough.
        redisTemplate.delete(List.of(
                "packet_hash:" + fingerprintService.fingerprint(paymentPacketFrom("bridge-A")),
                "packet_hash:" + fingerprintService.fingerprint(heartbeatPacketFrom("bridge-A"))));
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    /** The exact same logical payment packet, submitted under different bridge identities. */
    private IngestionRequest paymentPacketFrom(String bridgeId) {
        return IngestionRequest.builder()
                .encryptedPayload("base64encodeddata")
                .bridgeId(bridgeId)
                .timestamp("2026-09-14T12:00:00Z")
                .senderId("sender-1")
                .recipientId("receiver-2")
                .type("PAYMENT")
                .build();
    }

    /** A second, genuinely different packet used to prove claims are per packet. */
    private IngestionRequest heartbeatPacketFrom(String bridgeId) {
        return IngestionRequest.builder()
                .encryptedPayload("base64encodeddata")
                .bridgeId(bridgeId)
                .timestamp("2026-09-14T12:00:05Z")
                .senderId("sender-1")
                .recipientId("receiver-2")
                .type("HEARTBEAT")
                .build();
    }

    @Test
    void samePacketFromTwoBridges_onlyFirstBridgeWinsTheClaim() {
        String hashFromBridgeA = fingerprintService.fingerprint(paymentPacketFrom("bridge-A"));
        String hashFromBridgeB = fingerprintService.fingerprint(paymentPacketFrom("bridge-B"));

        assertEquals(hashFromBridgeA, hashFromBridgeB,
                "identical packets must fingerprint identically regardless of submitting bridge");

        assertTrue(idempotencyService.tryClaim(hashFromBridgeA),
                "bridge-A submits first and claims the packet");
        assertFalse(idempotencyService.tryClaim(hashFromBridgeB),
                "bridge-B submits the same packet and must be rejected as a duplicate");

        Set<String> keys = redisTemplate.keys("packet_hash:*");
        assertEquals(1, keys.size(), "both bridges must contend for one shared claim");
    }

    @Test
    void differentPacketsFromDifferentBridgesCanBothBeClaimed() {
        String paymentHash = fingerprintService.fingerprint(paymentPacketFrom("bridge-A"));
        String heartbeatHash = fingerprintService.fingerprint(heartbeatPacketFrom("bridge-B"));

        assertFalse(paymentHash.equals(heartbeatHash), "different packets must have different hashes");
        assertTrue(idempotencyService.tryClaim(paymentHash), "bridge-A packet is claimable");
        assertTrue(idempotencyService.tryClaim(heartbeatHash), "bridge-B packet is claimable");
    }

    @Test
    void bridgeIdentityIsNeverWrittenToRedisByTheIdempotencyLayer() {
        assertTrue(idempotencyService.tryClaim(fingerprintService.fingerprint(paymentPacketFrom("bridge-A"))));

        Set<String> allKeys = redisTemplate.keys("*");
        assertEquals(1, allKeys.size());
        assertTrue(allKeys.iterator().next().startsWith("packet_hash:"),
                "only the packet_hash namespace may be used for idempotency");
        assertFalse(allKeys.iterator().next().contains("bridge"),
                "bridgeId must never appear in an idempotency key");
    }
}
