package com.meshpay.ingestion.service;

import com.meshpay.crypto.HybridCryptoService;
import com.meshpay.crypto.RsaOaepCipher;
import com.meshpay.crypto.model.PaymentInstruction;
import com.meshpay.ingestion.decryption.MeshPacketCodec;
import com.meshpay.ingestion.decryption.PacketDecryptionService;
import com.meshpay.ingestion.dto.IngestionRequest;
import com.meshpay.ingestion.dto.IngestionResponse;
import com.meshpay.ingestion.entity.IngestedPacket;
import com.meshpay.ingestion.exception.DuplicatePacketException;
import com.meshpay.ingestion.exception.InvalidPacketTimestampException;
import com.meshpay.ingestion.exception.StalePacketException;
import com.meshpay.ingestion.freshness.PacketFreshnessValidator;
import com.meshpay.ingestion.idempotency.IdempotencyService;
import com.meshpay.ingestion.idempotency.PacketCanonicalizer;
import com.meshpay.ingestion.idempotency.PacketFingerprintService;
import com.meshpay.ingestion.repository.IngestedPacketRepository;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Day 13's central security property, asserted against the STATIC crypto entry
 * point itself:
 *
 * <pre>
 *   HybridCryptoService.decrypt(...) is never reached
 *     - by a duplicate packet   (Redis claim failed)
 *     - by a stale packet       (freshness failed)
 *     - by a future-dated packet(clock skew exceeded)
 *   and is reached exactly once by a fresh, unseen packet.
 * </pre>
 *
 * The pipeline under test is assembled from the REAL production components
 * (real fingerprinter, real freshness validator on a fixed clock, real
 * PacketDecryptionService + MeshPacketCodec); only the Redis-backed claim is a
 * stateful stand-in here, because atomic SET NX EX semantics are proven against
 * a real Redis container in {@code DecryptionGatingRedisIT}.
 */
class IdempotencyGatedDecryptionTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final long WINDOW_SECONDS = 300;

    private static final PaymentInstruction INSTRUCTION = new PaymentInstruction(
            "user-123", "user-456", 4200L, "nonce-gated-001", Instant.parse("2026-10-05T11:59:45Z"));

    private KeyPair backendKeyPair;
    private MeshPacketCodec codec;
    private PacketDecryptionService decryptionService;
    private PacketFingerprintService fingerprintService;
    private PacketFreshnessValidator freshnessValidator;
    private IdempotencyService idempotencyService;
    private IngestedPacketRepository repository;
    private IngestionService ingestionService;

    /** Counts how many times HybridCryptoService.decrypt(...) was actually reached. */
    private final AtomicInteger decryptCalls = new AtomicInteger();

    /** One claim per packet hash: first add wins, later attempts lose (SET NX semantics). */
    private final Set<String> claimedHashes = new LinkedHashSet<>();

    @BeforeEach
    void setUp() {
        backendKeyPair = RsaOaepCipher.generateKeyPair();
        codec = new MeshPacketCodec();
        decryptionService = new PacketDecryptionService(codec, backendKeyPair.getPrivate());
        fingerprintService = new PacketFingerprintService(new PacketCanonicalizer());
        freshnessValidator = new PacketFreshnessValidator(Clock.fixed(NOW, ZoneOffset.UTC), WINDOW_SECONDS, 30);
        repository = mock(IngestedPacketRepository.class);
        when(repository.save(any(IngestedPacket.class))).thenAnswer(inv -> inv.getArgument(0));

        idempotencyService = mock(IdempotencyService.class);
        when(idempotencyService.tryClaim(any())).thenAnswer(inv -> claimedHashes.add(inv.getArgument(0)));

        ingestionService = new IngestionService(repository, fingerprintService, idempotencyService,
                freshnessValidator, decryptionService);
        decryptCalls.set(0);
        claimedHashes.clear();
    }

    /**
     * Opens a static mock of HybridCryptoService that counts every decrypt call
     * and returns the expected instruction — proving *reachability* of the crypto
     * layer rather than merely that some collaborator was skipped.
     */
    private MockedStatic<HybridCryptoService> countingCrypto() {
        MockedStatic<HybridCryptoService> hybrid = mockStatic(HybridCryptoService.class);
        hybrid.when(() -> HybridCryptoService.decrypt(any(), any())).thenAnswer(inv -> {
            decryptCalls.incrementAndGet();
            return INSTRUCTION;
        });
        return hybrid;
    }

    private IngestionRequest requestAt(Instant timestamp, String encryptedPayload) {
        return IngestionRequest.builder()
                .encryptedPayload(encryptedPayload)
                .bridgeId("bridge-001")
                .timestamp(timestamp.toString())
                .senderId("user-123")
                .recipientId("user-456")
                .type("PAYMENT")
                .build();
    }

    private String freshEncryptedPayload() {
        return codec.encode(HybridCryptoService.encrypt(INSTRUCTION, backendKeyPair.getPublic()));
    }

    // --- Fresh packet ---------------------------------------------------------------

    @Test
    void freshUnseenPacketReachesHybridCryptoServiceExactlyOnce() {
        String payload = freshEncryptedPayload();

        try (MockedStatic<HybridCryptoService> ignored = countingCrypto()) {
            IngestionResponse response = ingestionService.ingest(
                    requestAt(NOW.minusSeconds(10), payload), "bridge-001");

            assertEquals("RECEIVED", response.status());
            assertEquals(1, decryptCalls.get(), "a fresh unseen packet must decrypt exactly once");
            verify(repository).save(any(IngestedPacket.class));
        }
    }

    // --- Duplicate ------------------------------------------------------------------

    @Test
    void duplicatePacketIsRejectedBeforeHybridCryptoServiceRuns() {
        String payload = freshEncryptedPayload();
        IngestionRequest request = requestAt(NOW.minusSeconds(10), payload);

        try (MockedStatic<HybridCryptoService> ignored = countingCrypto()) {
            ingestionService.ingest(request, "bridge-001");
            assertEquals(1, decryptCalls.get(), "first submission decrypts once");

            DuplicatePacketException ex = assertThrows(DuplicatePacketException.class,
                    () -> ingestionService.ingest(request, "bridge-001"));

            assertEquals("Packet has already been submitted", ex.getMessage());
            assertEquals(1, decryptCalls.get(),
                    "the duplicate must NOT trigger a second HybridCryptoService.decrypt call");
            verify(repository, times(1)).save(any(IngestedPacket.class));
        }
    }

    // --- Stale ----------------------------------------------------------------------

    @Test
    void stalePacketIsRejectedBeforeHybridCryptoServiceRuns() {
        // 301s old: outside the 300s window, but the Redis claim would still succeed.
        String payload = freshEncryptedPayload();
        IngestionRequest staleRequest = requestAt(NOW.minusSeconds(301), payload);

        try (MockedStatic<HybridCryptoService> ignored = countingCrypto()) {
            StalePacketException ex = assertThrows(StalePacketException.class,
                    () -> ingestionService.ingest(staleRequest, "bridge-001"));

            assertEquals("Packet has expired", ex.getMessage());
            assertEquals(0, decryptCalls.get(),
                    "a stale packet must be rejected without any cryptographic work");
            verifyNoInteractions(repository);
        }
    }

    @Test
    void futureDatedPacketBeyondClockSkewIsRejectedBeforeHybridCryptoServiceRuns() {
        String payload = freshEncryptedPayload();
        IngestionRequest futureRequest = requestAt(NOW.plusSeconds(3600), payload);

        try (MockedStatic<HybridCryptoService> ignored = countingCrypto()) {
            assertThrows(InvalidPacketTimestampException.class,
                    () -> ingestionService.ingest(futureRequest, "bridge-001"));

            assertEquals(0, decryptCalls.get(), "an out-of-skew future packet must never decrypt");
            verifyNoInteractions(repository);
        }
    }

    // --- Two different packets are not a global lock --------------------------------

    @Test
    void twoDifferentFreshPacketsBothReachDecryption() {
        String payloadOne = freshEncryptedPayload();
        String payloadTwo = freshEncryptedPayload();

        try (MockedStatic<HybridCryptoService> ignored = countingCrypto()) {
            ingestionService.ingest(requestAt(NOW.minusSeconds(5), payloadOne), "bridge-001");
            ingestionService.ingest(requestAt(NOW.minusSeconds(5), payloadTwo), "bridge-001");

            assertEquals(2, decryptCalls.get(),
                    "idempotency must be per packet, not a global lock — both packets decrypt");
            verify(repository, times(2)).save(any(IngestedPacket.class));
        }
    }
}
