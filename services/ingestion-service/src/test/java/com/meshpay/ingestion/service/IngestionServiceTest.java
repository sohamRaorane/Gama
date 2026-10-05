package com.meshpay.ingestion.service;

import com.meshpay.crypto.model.PaymentInstruction;
import com.meshpay.ingestion.decryption.PacketDecryptionService;
import com.meshpay.ingestion.dto.IngestionRequest;
import com.meshpay.ingestion.dto.IngestionResponse;
import com.meshpay.ingestion.entity.IngestedPacket;
import com.meshpay.ingestion.exception.DuplicatePacketException;
import com.meshpay.ingestion.exception.InvalidEncryptedPacketException;
import com.meshpay.ingestion.exception.StalePacketException;
import com.meshpay.ingestion.freshness.PacketFreshnessValidator;
import com.meshpay.ingestion.idempotency.IdempotencyService;
import com.meshpay.ingestion.idempotency.PacketFingerprintService;
import com.meshpay.ingestion.repository.IngestedPacketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

    private static final String PACKET_HASH = "a4f5c8d9e0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7";

    private static final PaymentInstruction DECRYPTED_INSTRUCTION = new PaymentInstruction(
            "user-123", "user-456", 1500L, "nonce-unit-001", Instant.parse("2026-10-05T11:59:45Z"));

    @Mock
    private IngestedPacketRepository repository;

    @Mock
    private PacketFingerprintService fingerprintService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private PacketFreshnessValidator freshnessValidator;

    @Mock
    private PacketDecryptionService decryptionService;

    @InjectMocks
    private IngestionService ingestionService;

    @BeforeEach
    void setUp() {
        // Default happy path: fingerprint computed, Redis claim granted, packet fresh,
        // decryption succeeds. Tests that reject earlier (spoofing, duplicate, stale,
        // undecryptable) never reach the collaborators they are asserted against.
        lenient().when(fingerprintService.fingerprint(any())).thenReturn(PACKET_HASH);
        lenient().when(idempotencyService.tryClaim(PACKET_HASH)).thenReturn(true);
        lenient().when(decryptionService.decrypt(any())).thenReturn(DECRYPTED_INSTRUCTION);
    }

    private IngestionRequest paymentRequest() {
        return paymentRequest("bridge-001");
    }

    private IngestionRequest paymentRequest(String bridgeId) {
        return new IngestionRequest(
                "encrypted-data-123",
                bridgeId,
                "2026-10-05T11:59:50Z",
                "user-123",
                "user-456",
                "PAYMENT"
        );
    }

    @Test
    void shouldCreateEntityAndReturnAcknowledgment() {
        when(repository.save(any(IngestedPacket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IngestionResponse response = ingestionService.ingest(paymentRequest(), "bridge-001");

        assertNotNull(response);
        assertNotNull(response.packetId());
        assertEquals("RECEIVED", response.status());
        assertEquals("Packet accepted for processing", response.message());
        assertNotNull(response.receivedAt());

        ArgumentCaptor<IngestedPacket> captor = ArgumentCaptor.forClass(IngestedPacket.class);
        verify(repository).save(captor.capture());

        IngestedPacket saved = captor.getValue();
        assertEquals("encrypted-data-123", saved.getEncryptedPayload());
        assertEquals("bridge-001", saved.getBridgeId());
        assertEquals("user-123", saved.getSenderId());
        assertEquals("user-456", saved.getRecipientId());
        assertEquals("PAYMENT", saved.getType());
        assertEquals("RECEIVED", saved.getStatus());
        assertEquals("2026-10-05T11:59:50Z", saved.getTimestamp());
    }

    @Test
    void shouldGenerateUniquePacketIds() {
        when(repository.save(any(IngestedPacket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IngestionRequest request = new IngestionRequest(
                "encrypted-data",
                "bridge-001",
                "2026-10-05T11:59:50Z",
                null,
                null,
                "HEARTBEAT"
        );

        IngestionResponse first = ingestionService.ingest(request, "bridge-001");
        IngestionResponse second = ingestionService.ingest(request, "bridge-001");

        assertNotNull(first.packetId());
        assertNotNull(second.packetId());
        assertEquals(false, first.packetId().equals(second.packetId()));
    }

    @Test
    void shouldRejectSpoofedBridgeIdentity() {
        // Bridge authenticated as bridge-001 tries to claim bridge-999 in body
        IngestionRequest request = paymentRequest("bridge-999");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ingestionService.ingest(request, "bridge-001"));

        // Should never reach fingerprinting, the Redis claim, freshness, decryption or the repository
        verifyNoInteractions(fingerprintService, idempotencyService, freshnessValidator, decryptionService, repository);
        assertEquals(true, ex.getMessage().contains("mismatch"));
    }

    @Test
    void shouldRejectWhenAuthenticatedBridgeIdIsNull() {
        IngestionRequest request = paymentRequest();

        assertThrows(IllegalArgumentException.class,
                () -> ingestionService.ingest(request, null));

        verifyNoInteractions(fingerprintService, idempotencyService, freshnessValidator, decryptionService, repository);
    }

    @Test
    void shouldRejectDuplicatePacketWhenRedisClaimFails() {
        when(idempotencyService.tryClaim(PACKET_HASH)).thenReturn(false);

        IngestionRequest request = paymentRequest();

        DuplicatePacketException ex = assertThrows(DuplicatePacketException.class,
                () -> ingestionService.ingest(request, "bridge-001"));

        assertEquals(PACKET_HASH, ex.getPacketHash());
        assertEquals("Packet has already been submitted", ex.getMessage());
        verifyNoInteractions(repository);
        // Day 13 core rule: a duplicate must never reach decryption.
        verifyNoInteractions(freshnessValidator, decryptionService);
    }

    @Test
    void shouldRunFingerprintThenClaimThenFreshnessThenDecryptThenPersistInOrder() {
        when(repository.save(any(IngestedPacket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IngestionRequest request = paymentRequest();

        ingestionService.ingest(request, "bridge-001");

        // The mandated Day 13 pipeline order, asserted with a single InOrder.
        InOrder pipeline = inOrder(fingerprintService, idempotencyService, freshnessValidator,
                decryptionService, repository);
        pipeline.verify(fingerprintService).fingerprint(request);
        pipeline.verify(idempotencyService).tryClaim(PACKET_HASH);
        pipeline.verify(freshnessValidator).validate(request.timestamp());
        pipeline.verify(decryptionService).decrypt(request);
        pipeline.verify(repository).save(any(IngestedPacket.class));

        // Exactly one pass through each stage — no hidden second decrypt.
        verify(decryptionService, times(1)).decrypt(any());
        verify(repository, times(1)).save(any(IngestedPacket.class));
    }

    @Test
    void shouldOnlyClaimOnceWhenDuplicateIsSubmitted() {
        when(repository.save(any(IngestedPacket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(idempotencyService.tryClaim(PACKET_HASH)).thenReturn(true, false);

        IngestionRequest request = paymentRequest();

        ingestionService.ingest(request, "bridge-001");
        assertThrows(DuplicatePacketException.class,
                () -> ingestionService.ingest(request, "bridge-001"));

        verify(repository).save(any(IngestedPacket.class));
        // First submission decrypts exactly once; the rejected duplicate decrypts zero times.
        verify(decryptionService, times(1)).decrypt(any());
    }

    // --- Day 13: freshness + decryption ordering -----------------------------------

    @Test
    void shouldRejectStalePacketBeforeDecryption() {
        doThrow(new StalePacketException()).when(freshnessValidator).validate(any());

        IngestionRequest request = paymentRequest();

        StalePacketException ex = assertThrows(StalePacketException.class,
                () -> ingestionService.ingest(request, "bridge-001"));

        assertEquals("Packet has expired", ex.getMessage());
        // The claim already succeeded (Day 13 order), but decryption and persistence must not run.
        verify(idempotencyService).tryClaim(PACKET_HASH);
        verify(freshnessValidator).validate(request.timestamp());
        verifyNoInteractions(decryptionService);
        verifyNoInteractions(repository);
    }

    @Test
    void shouldRejectUndecryptablePacketBeforePersistence() {
        doThrow(new InvalidEncryptedPacketException("tampered", new IllegalStateException("boom")))
                .when(decryptionService).decrypt(any());

        IngestionRequest request = paymentRequest();

        InvalidEncryptedPacketException ex = assertThrows(InvalidEncryptedPacketException.class,
                () -> ingestionService.ingest(request, "bridge-001"));

        assertEquals("Encrypted packet could not be decrypted", ex.getMessage());
        verify(freshnessValidator).validate(request.timestamp());
        verify(decryptionService).decrypt(request);
        verifyNoInteractions(repository);
    }
}
