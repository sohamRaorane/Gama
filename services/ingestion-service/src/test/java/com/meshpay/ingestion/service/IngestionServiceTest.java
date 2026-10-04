package com.meshpay.ingestion.service;

import com.meshpay.ingestion.dto.IngestionRequest;
import com.meshpay.ingestion.dto.IngestionResponse;
import com.meshpay.ingestion.entity.IngestedPacket;
import com.meshpay.ingestion.exception.DuplicatePacketException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

    private static final String PACKET_HASH = "a4f5c8d9e0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7";

    @Mock
    private IngestedPacketRepository repository;

    @Mock
    private PacketFingerprintService fingerprintService;

    @Mock
    private IdempotencyService idempotencyService;

    @InjectMocks
    private IngestionService ingestionService;

    @BeforeEach
    void setUp() {
        // Default happy path: fingerprint computed, Redis claim granted.
        // Tests that reject earlier (spoofing) never invoke either collaborator.
        lenient().when(fingerprintService.fingerprint(any())).thenReturn(PACKET_HASH);
        lenient().when(idempotencyService.tryClaim(PACKET_HASH)).thenReturn(true);
    }

    @Test
    void shouldCreateEntityAndReturnAcknowledgment() {
        when(repository.save(any(IngestedPacket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IngestionRequest request = new IngestionRequest(
                "encrypted-data-123",
                "bridge-001",
                "2026-09-14T12:00:00Z",
                "user-123",
                "user-456",
                "PAYMENT"
        );

        IngestionResponse response = ingestionService.ingest(request, "bridge-001");

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
        assertEquals("2026-09-14T12:00:00Z", saved.getTimestamp());
    }

    @Test
    void shouldGenerateUniquePacketIds() {
        when(repository.save(any(IngestedPacket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IngestionRequest request = new IngestionRequest(
                "encrypted-data",
                "bridge-001",
                "2026-09-14T12:00:00Z",
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
        IngestionRequest request = new IngestionRequest(
                "encrypted-data",
                "bridge-999",
                "2026-09-14T12:00:00Z",
                "user-123",
                "user-456",
                "PAYMENT"
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ingestionService.ingest(request, "bridge-001"));

        // Should never reach fingerprinting, the Redis claim, or the repository
        verifyNoInteractions(fingerprintService, idempotencyService, repository);
        assertEquals(true, ex.getMessage().contains("mismatch"));
    }

    @Test
    void shouldRejectWhenAuthenticatedBridgeIdIsNull() {
        IngestionRequest request = new IngestionRequest(
                "encrypted-data",
                "bridge-001",
                "2026-09-14T12:00:00Z",
                null,
                null,
                "HEARTBEAT"
        );

        assertThrows(IllegalArgumentException.class,
                () -> ingestionService.ingest(request, null));

        verifyNoInteractions(fingerprintService, idempotencyService, repository);
    }

    @Test
    void shouldRejectDuplicatePacketWhenRedisClaimFails() {
        when(idempotencyService.tryClaim(PACKET_HASH)).thenReturn(false);

        IngestionRequest request = new IngestionRequest(
                "encrypted-data",
                "bridge-001",
                "2026-09-14T12:00:00Z",
                "user-123",
                "user-456",
                "PAYMENT"
        );

        DuplicatePacketException ex = assertThrows(DuplicatePacketException.class,
                () -> ingestionService.ingest(request, "bridge-001"));

        assertEquals(PACKET_HASH, ex.getPacketHash());
        assertEquals("Packet has already been submitted", ex.getMessage());
        verifyNoInteractions(repository);
    }

    @Test
    void shouldRunFingerprintThenClaimThenPersistInOrder() {
        when(repository.save(any(IngestedPacket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IngestionRequest request = new IngestionRequest(
                "encrypted-data",
                "bridge-001",
                "2026-09-14T12:00:00Z",
                "user-123",
                "user-456",
                "PAYMENT"
        );

        ingestionService.ingest(request, "bridge-001");

        InOrder pipeline = inOrder(fingerprintService, idempotencyService, repository);
        pipeline.verify(fingerprintService).fingerprint(request);
        pipeline.verify(idempotencyService).tryClaim(PACKET_HASH);
        pipeline.verify(repository).save(any(IngestedPacket.class));
    }

    @Test
    void shouldOnlyClaimOnceWhenDuplicateIsSubmitted() {
        when(repository.save(any(IngestedPacket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(idempotencyService.tryClaim(PACKET_HASH)).thenReturn(true, false);

        IngestionRequest request = new IngestionRequest(
                "encrypted-data",
                "bridge-001",
                "2026-09-14T12:00:00Z",
                "user-123",
                "user-456",
                "PAYMENT"
        );

        ingestionService.ingest(request, "bridge-001");
        assertThrows(DuplicatePacketException.class,
                () -> ingestionService.ingest(request, "bridge-001"));

        org.mockito.Mockito.verify(repository).save(any(IngestedPacket.class));
    }
}
