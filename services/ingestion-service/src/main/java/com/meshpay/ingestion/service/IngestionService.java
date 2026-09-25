package com.meshpay.ingestion.service;

import com.meshpay.ingestion.dto.IngestionRequest;
import com.meshpay.ingestion.dto.IngestionResponse;
import com.meshpay.ingestion.entity.IngestedPacket;
import com.meshpay.ingestion.repository.IngestedPacketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core service that handles incoming encrypted mesh packets from bridges.
 * Creates a database record and returns an acknowledgment with a correlation ID.
 *
 * Security: validates the authenticated bridgeId (from JWT) against the
 * request body's bridgeId to prevent identity spoofing. A bridge authenticated
 * as bridge-001 cannot submit a packet claiming to be bridge-999.
 */
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final IngestedPacketRepository repository;

    /**
     * Accepts an encrypted packet from a bridge, validates identity, persists it,
     * and returns an acknowledgment.
     *
     * @param request the incoming packet submission (includes body bridgeId + timestamp)
     * @param authenticatedBridgeId the bridgeId extracted from the JWT by the auth filter
     * @return acknowledgment with a unique packetId for correlation
     * @throws IllegalArgumentException if body bridgeId does not match authenticated bridgeId
     */
    @Transactional
    public IngestionResponse ingest(IngestionRequest request, String authenticatedBridgeId) {
        // Bridge identity spoofing prevention:
        // The authenticated bridgeId comes from the verified JWT (set by JwtAuthenticationFilter).
        // The body bridgeId is what the bridge claims in its request payload.
        // If they disagree, the bridge is attempting to impersonate another identity.
        if (authenticatedBridgeId == null || !authenticatedBridgeId.equals(request.bridgeId())) {
            throw new IllegalArgumentException(
                    "Bridge identity mismatch: authenticated as '" + authenticatedBridgeId
                            + "' but request claims '" + request.bridgeId() + "'");
        }

        // Persist the packet. bridgeId is now guaranteed to match the JWT identity.
        // timestamp is stored for Phase 3 freshness/replay validation.
        IngestedPacket packet = IngestedPacket.create(
                request.encryptedPayload(),
                request.bridgeId(),
                request.senderId(),
                request.recipientId(),
                request.type(),
                request.timestamp()
        );

        repository.save(packet);

        return IngestionResponse.acknowledged(packet.getPacketId().toString());
    }
}