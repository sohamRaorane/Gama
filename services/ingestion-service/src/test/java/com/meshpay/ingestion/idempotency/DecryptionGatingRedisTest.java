package com.meshpay.ingestion.idempotency;

import com.meshpay.crypto.HybridCryptoService;
import com.meshpay.crypto.RsaOaepCipher;
import com.meshpay.crypto.model.PaymentInstruction;
import com.meshpay.ingestion.decryption.MeshPacketCodec;
import com.meshpay.ingestion.decryption.PacketDecryptionService;
import com.meshpay.ingestion.dto.IngestionRequest;
import com.meshpay.ingestion.dto.IngestionResponse;
import com.meshpay.ingestion.entity.IngestedPacket;
import com.meshpay.ingestion.exception.DuplicatePacketException;
import com.meshpay.ingestion.exception.StalePacketException;
import com.meshpay.ingestion.freshness.PacketFreshnessValidator;
import com.meshpay.ingestion.repository.IngestedPacketRepository;
import com.meshpay.ingestion.service.IngestionService;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Day 13 end-to-end gating against a REAL Redis container and REAL cryptography.
 *
 * Everything here is production code except the repository (mocked — no database
 * is in scope for Day 13) and the spy wrapper around PacketDecryptionService,
 * which exists purely to count how often decryption ran while still executing
 * the real HybridCryptoService round-trip.
 *
 * Proven here:
 * <ul>
 *   <li>a fresh unseen packet is claimed, passes freshness, and decrypts to the
 *       original PaymentInstruction;</li>
 *   <li>resubmitting it (same bridge or a different bridge) hits the real
 *       {@code SET packet_hash:{hash} claimed NX EX} and never decrypts again;</li>
 *   <li>a stale packet is claimed but rejected by freshness — zero decryption;</li>
 *   <li>two genuinely different packets are two identities (not a global lock).</li>
 * </ul>
 */
@Testcontainers
class DecryptionGatingRedisTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.10.1")
            .withExposedPorts(6379);

    private static final long TTL_SECONDS = 300;
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final long WINDOW_SECONDS = 300;

    private static final PaymentInstruction INSTRUCTION = new PaymentInstruction(
            "user-123", "user-456", 4200L, "nonce-redis-001", Instant.parse("2026-10-05T11:59:45Z"));

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private KeyPair backendKeyPair;
    private MeshPacketCodec codec;
    private PacketDecryptionService decryptionService;
    private IngestedPacketRepository repository;
    private IngestionService ingestionService;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        backendKeyPair = RsaOaepCipher.generateKeyPair();
        codec = new MeshPacketCodec();
        // Spy (not mock): the real decryption runs, the spy only counts invocations.
        decryptionService = spy(new PacketDecryptionService(codec, backendKeyPair.getPrivate()));

        PacketFingerprintService fingerprintService = new PacketFingerprintService(new PacketCanonicalizer());
        IdempotencyService idempotencyService = new IdempotencyService(redisTemplate, TTL_SECONDS);
        PacketFreshnessValidator freshnessValidator =
                new PacketFreshnessValidator(Clock.fixed(NOW, ZoneOffset.UTC), WINDOW_SECONDS, 30);

        repository = mock(IngestedPacketRepository.class);
        when(repository.save(any(IngestedPacket.class))).thenAnswer(inv -> inv.getArgument(0));

        ingestionService = new IngestionService(repository, fingerprintService, idempotencyService,
                freshnessValidator, decryptionService);

        // Each test starts from an empty claim namespace.
        Set<String> existing = claimKeys();
        if (!existing.isEmpty()) {
            redisTemplate.delete(existing);
        }
    }

    private Set<String> claimKeys() {
        Set<String> keys = redisTemplate.keys("packet_hash:*");
        return keys == null ? java.util.Set.of() : keys;
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    private String encryptedPayload() {
        return codec.encode(HybridCryptoService.encrypt(INSTRUCTION, backendKeyPair.getPublic()));
    }

    private IngestionRequest requestFor(String bridgeId, Instant timestamp, String payload) {
        return IngestionRequest.builder()
                .encryptedPayload(payload)
                .bridgeId(bridgeId)
                .timestamp(timestamp.toString())
                .senderId("user-123")
                .recipientId("user-456")
                .type("PAYMENT")
                .build();
    }

    // --- Fresh packet (required Day 13 test) ---------------------------------------

    @Test
    void freshPacketIsClaimedAndDecryptsToTheOriginalInstruction() {
        String payload = encryptedPayload();

        IngestionResponse response = ingestionService.ingest(
                requestFor("bridge-001", NOW.minusSeconds(10), payload), "bridge-001");

        assertEquals("RECEIVED", response.status());
        verify(decryptionService, times(1)).decrypt(any());

        // Explicitly prove the real round trip: decoding + HybridCryptoService.decrypt
        // on this very payload yields the sender's original instruction (nonce included).
        PaymentInstruction recovered = new PacketDecryptionService(codec, backendKeyPair.getPrivate())
                .decrypt(requestFor("bridge-001", NOW.minusSeconds(10), payload));
        assertEquals(INSTRUCTION, recovered, "the original PaymentInstruction must be recovered");

        // The packet that reached persistence is the same one that was decrypted.
        var savedCaptor = org.mockito.ArgumentCaptor.forClass(IngestedPacket.class);
        verify(repository, times(1)).save(savedCaptor.capture());
        assertEquals(payload, savedCaptor.getValue().getEncryptedPayload());

        // Exactly one claim key exists, and it is in the packet_hash namespace only.
        Set<String> keys = claimKeys();
        assertEquals(1, keys.size());
        assertTrue(keys.iterator().next().startsWith("packet_hash:"));
    }

    // --- Duplicate (required Day 13 test) ------------------------------------------

    @Test
    void duplicateSubmissionIsRejectedAfterRealRedisClaimAndNeverDecryptsAgain() {
        String payload = encryptedPayload();
        IngestionRequest request = requestFor("bridge-001", NOW.minusSeconds(10), payload);

        IngestionResponse first = ingestionService.ingest(request, "bridge-001");
        assertEquals("RECEIVED", first.status());
        verify(decryptionService, times(1)).decrypt(any());

        DuplicatePacketException ex = assertThrows(DuplicatePacketException.class,
                () -> ingestionService.ingest(request, "bridge-001"));

        assertEquals("Packet has already been submitted", ex.getMessage());
        verify(decryptionService, times(1)).decrypt(any());
        verify(repository, times(1)).save(any(IngestedPacket.class));
        assertEquals(1, claimKeys().size(), "the duplicate must not create a second claim");
    }

    // --- Two bridges (required Day 13 test) ----------------------------------------

    @Test
    void samePacketFromTwoBridgesOnlyTheFirstBridgeReachesDecryption() {
        String payload = encryptedPayload();
        // Same logical packet: identical content, only the submitting bridge differs.
        IngestionRequest fromBridgeA = requestFor("bridge-A", NOW.minusSeconds(10), payload);
        IngestionRequest fromBridgeB = requestFor("bridge-B", NOW.minusSeconds(10), payload);

        PacketFingerprintService fingerprintService = new PacketFingerprintService(new PacketCanonicalizer());
        assertEquals(fingerprintService.fingerprint(fromBridgeA), fingerprintService.fingerprint(fromBridgeB),
                "bridge identity must not be part of packet identity");

        IngestionResponse responseA = ingestionService.ingest(fromBridgeA, "bridge-A");
        assertEquals("RECEIVED", responseA.status());
        verify(decryptionService, times(1)).decrypt(any());

        assertThrows(DuplicatePacketException.class,
                () -> ingestionService.ingest(fromBridgeB, "bridge-B"));

        // Still exactly one decrypt in total: bridge-B lost the shared claim and never decrypted.
        verify(decryptionService, times(1)).decrypt(any());
        verify(repository, times(1)).save(any(IngestedPacket.class));
        assertEquals(1, claimKeys().size(), "both bridges contend for one shared claim key");
    }

    // --- Stale packet (required Day 13 test) ---------------------------------------

    @Test
    void stalePacketIsClaimedButRejectedBeforeDecryption() {
        String payload = encryptedPayload();
        // Age 301s against a 300s window on the fixed clock.
        IngestionRequest stale = requestFor("bridge-001", NOW.minusSeconds(301), payload);

        StalePacketException ex = assertThrows(StalePacketException.class,
                () -> ingestionService.ingest(stale, "bridge-001"));

        assertEquals("Packet has expired", ex.getMessage());
        verify(decryptionService, never()).decrypt(any());
        verifyNoInteractions(repository);
        // Day 13 order: claim first, freshness second — so the claim IS held afterwards.
        assertTrue(claimKeys().contains("packet_hash:"
                        + new PacketFingerprintService(new PacketCanonicalizer()).fingerprint(stale)),
                "the freshness gate runs after the claim, so the claim must exist");
    }

    @Test
    void staleReplayIsReportedAsDuplicateNotAsAnotherStaleAttempt() {
        String payload = encryptedPayload();
        IngestionRequest stale = requestFor("bridge-001", NOW.minusSeconds(301), payload);

        assertThrows(StalePacketException.class, () -> ingestionService.ingest(stale, "bridge-001"));
        // Second attempt loses the claim (it was consumed by the first attempt).
        assertThrows(DuplicatePacketException.class, () -> ingestionService.ingest(stale, "bridge-001"));

        verify(decryptionService, never()).decrypt(any());
    }

    // --- Two different fresh packets (required Day 13 test) ------------------------

    @Test
    void twoDifferentFreshPacketsBothClaimAndBothDecrypt() {
        String payloadOne = encryptedPayload();
        String payloadTwo = encryptedPayload();

        IngestionResponse first = ingestionService.ingest(
                requestFor("bridge-A", NOW.minusSeconds(5), payloadOne), "bridge-A");
        IngestionResponse second = ingestionService.ingest(
                requestFor("bridge-B", NOW.minusSeconds(5), payloadTwo), "bridge-B");

        assertEquals("RECEIVED", first.status());
        assertEquals("RECEIVED", second.status());
        verify(decryptionService, times(2)).decrypt(any());
        verify(repository, times(2)).save(any(IngestedPacket.class));
        assertEquals(2, claimKeys().size(), "different packets must be independent claims");
        assertFalse(claimKeys().isEmpty());
    }
}
