package com.meshpay.ingestion.service;

import com.meshpay.ingestion.dto.IngestionRequest;
import com.meshpay.ingestion.dto.IngestionResponse;
import com.meshpay.ingestion.entity.IngestedPacket;
import com.meshpay.ingestion.repository.IngestedPacketRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

    @Mock
    private IngestedPacketRepository repository;

    @InjectMocks
    private IngestionService ingestionService;

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

        // Should never reach the repository
        org.mockito.Mockito.verifyNoInteractions(repository);
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

        org.mockito.Mockito.verifyNoInteractions(repository);
    }
}