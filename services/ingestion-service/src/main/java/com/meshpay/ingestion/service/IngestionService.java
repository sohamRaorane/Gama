package com.meshpay.ingestion.service;

import com.meshpay.crypto.model.PaymentInstruction;
import com.meshpay.ingestion.decryption.PacketDecryptionService;
import com.meshpay.ingestion.dto.IngestionRequest;
import com.meshpay.ingestion.dto.IngestionResponse;
import com.meshpay.ingestion.entity.IngestedPacket;
import com.meshpay.ingestion.exception.DuplicatePacketException;
import com.meshpay.ingestion.freshness.PacketFreshnessValidator;
import com.meshpay.ingestion.idempotency.IdempotencyService;
import com.meshpay.ingestion.idempotency.PacketFingerprintService;
import com.meshpay.ingestion.repository.IngestedPacketRepository;
import java.util.logging.Logger;
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
 * Pipeline (Day 10 → Day 13), in this exact order:
 *
 * <pre>
 *   identity check          (400 spoofing)
 *       ↓
 *   canonical fingerprint   (Day 12, SHA-256)
 *       ↓
 *   Redis idempotency claim (Day 12, SET NX EX → 409 duplicate)
 *       ↓                    ★ claim failure STOPS here: decryption is never reached
 *   freshness validation    (Day 13, clock + window → 400 stale/invalid timestamp)
 *       ↓                    ★ staleness STOPS here: decryption is never reached
 *   HybridCryptoService     (Day 13, decrypt → PaymentInstruction)
 *       ↓
 *   persistence             (existing Day 10 processing)
 * </pre>
 *
 * Authentication (401) and rate limiting (429) already ran in
 * JwtAuthenticationFilter; bean validation already ran in the controller.
 *
 * The ordering is architectural, not documentary: a duplicate packet costs one
 * Redis round-trip and never triggers RSA/AES work, and a stale packet is
 * rejected before any cryptography runs.
 */
@Service
@RequiredArgsConstructor
public class IngestionService {

    private static final Logger log = Logger.getLogger(IngestionService.class.getName());

    private final IngestedPacketRepository repository;
    private final PacketFingerprintService fingerprintService;
    private final IdempotencyService idempotencyService;
    private final PacketFreshnessValidator freshnessValidator;
    private final PacketDecryptionService decryptionService;

    /**
     * Accepts an encrypted packet from a bridge, validates identity, claims it in
     * Redis for idempotency, validates freshness, decrypts it, persists it, and
     * returns an acknowledgment.
     *
     * @param request the incoming packet submission (includes body bridgeId + timestamp)
     * @param authenticatedBridgeId the bridgeId extracted from the JWT by the auth filter
     * @return acknowledgment with a unique packetId for correlation
     * @throws IllegalArgumentException if body bridgeId does not match authenticated bridgeId
     * @throws DuplicatePacketException if the packet fingerprint is already claimed in Redis
     * @throws com.meshpay.ingestion.exception.StalePacketException if the packet is outside the freshness window
     * @throws com.meshpay.ingestion.exception.InvalidPacketTimestampException if the timestamp is invalid or too far in the future
     * @throws com.meshpay.ingestion.exception.InvalidEncryptedPacketException if a fresh, claimed packet cannot be decrypted
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
        // overwrites the claim, never reaches freshness, and never reaches decryption.
        if (!idempotencyService.tryClaim(packetHash)) {
            throw new DuplicatePacketException(packetHash);
        }

        // Day 13, Layer 1: freshness gate (UTC instants, injected Clock, configurable window).
        // Runs AFTER the claim and BEFORE decryption: a stale packet is rejected
        // with 400 without spending any cryptographic work.
        freshnessValidator.validate(request.timestamp());

        // Day 13, Layer 2: decryption — the most expensive step, reachable only when
        // the packet is both unseen (claim won) and fresh. Delegates to
        // HybridCryptoService; failures surface as 400, never as leaked crypto detail.
        PaymentInstruction instruction = decryptionService.decrypt(request);
        // The recovered instruction is the real object produced by HybridCryptoService,
        // not fabricated data. It is intentionally not persisted: settlement/ledger
        // storage is out of scope for this phase (no plaintext column exists), and its
        // contents (ids, amount, nonce) are deliberately kept out of the logs.
        log.fine("PaymentInstruction recovered for packet " + packetHash.substring(0, 12)
                + " (signedAt=" + instruction.signedAt() + ")");

        // Persist the packet. bridgeId is now guaranteed to match the JWT identity.
        // timestamp is stored for downstream correlation with the freshness decision.
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
