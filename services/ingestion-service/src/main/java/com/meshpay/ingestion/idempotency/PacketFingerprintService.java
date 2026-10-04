package com.meshpay.ingestion.idempotency;

import com.meshpay.ingestion.dto.IngestionRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Computes the deterministic SHA-256 packet fingerprint (Day 12, Layer 1).
 *
 * <p>Flow: {@link IngestionRequest} &rarr; {@link PacketCanonicalizer} &rarr;
 * canonical UTF-8 bytes &rarr; SHA-256 &rarr; 64-character lowercase hex string.
 *
 * <p>The fingerprint identifies the <em>logical packet</em>, not the submission:
 * it is independent of the submitting bridge, so two bridges resubmitting the same
 * packet produce the same hash and therefore compete for the same idempotency claim.
 *
 * <p>This is a deterministic fingerprint for duplicate detection — it is NOT a
 * password hash and NOT a message authentication code; it proves no authenticity.
 */
@Service
@RequiredArgsConstructor
public class PacketFingerprintService {

    private static final String HASH_ALGORITHM = "SHA-256";

    private final PacketCanonicalizer canonicalizer;

    /**
     * Fingerprints the given packet.
     *
     * @param packet the validated ingestion request
     * @return lowercase hexadecimal SHA-256 of the packet's canonical representation
     */
    public String fingerprint(IngestionRequest packet) {
        return sha256Hex(canonicalizer.canonicalize(packet));
    }

    /**
     * SHA-256 over an already-canonical representation, rendered as lowercase hex.
     * Package-private so hashing can be verified independently of canonicalization.
     */
    String sha256Hex(String canonicalRepresentation) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(canonicalRepresentation.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            // Every compliant JVM ships SHA-256 (JCA standard algorithm).
            throw new IllegalStateException("SHA-256 is not available in this JVM", ex);
        }
    }
}
