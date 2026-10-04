package com.meshpay.ingestion.service;

import com.meshpay.ingestion.dto.IngestionRequest;
import com.meshpay.ingestion.dto.IngestionResponse;
import com.meshpay.ingestion.entity.IngestedPacket;
import com.meshpay.ingestion.exception.DuplicatePacketException;
import com.meshpay.ingestion.idempotency.IdempotencyService;
import com.meshpay.ingestion.idempotency.PacketFingerprintService;
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
 *
 * Idempotency (Day 12): after identity validation, the packet is fingerprinted
 * (SHA-256 over the canonical representation) and the fingerprint is claimed in
 * Redis with an atomic SET NX EX. Only the first submission of a given packet
 * reaches persistence; duplicates are rejected with 409.
 */
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final IngestedPacketRepository repository;
    private final PacketFingerprintService fingerprintService;
    private final IdempotencyService idempotencyService;

    /**
     * Accepts an encrypted packet from a bridge, validates identity, claims it in
     * Redis for idempotency, persists it, and returns an acknowledgment.
     *
     * Pipeline: identity validation &rarr; canonical fingerprint &rarr; Redis claim
     * &rarr; persistence. Authentication (401) and rate limiting (429) already ran in
     * JwtAuthenticationFilter; bean validation already ran in the controller.
     *
     * @param request the incoming packet submission (includes body bridgeId + timestamp)
     * @param authenticatedBridgeId the bridgeId extracted from the JWT by the auth filter
     * @return acknowledgment with a unique packetId for correlation
     * @throws IllegalArgumentException if body bridgeId does not match authenticated bridgeId
     * @throws DuplicatePacketException if the packet fingerprint is already claimed in Redis
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

        // Day 12, Layer 1: deterministic SHA-256 fingerprint of the logical packet.
        // bridgeId is excluded from the fingerprint, so Bridge A and Bridge B
        // submitting the same packet produce the same packetHash.
        String packetHash = fingerprintService.fingerprint(request);

        // Day 12, Layer 2: atomic SET packet_hash:{packetHash} claimed NX EX ttl.
        // First submission claims the packet and continues; a duplicate never
        // overwrites the claim and never reaches the repository.
        if (!idempotencyService.tryClaim(packetHash)) {
            throw new DuplicatePacketException(packetHash);
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