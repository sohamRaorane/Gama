package com.meshpay.ingestion.decryption;

import com.meshpay.crypto.HybridCryptoService;
import com.meshpay.crypto.model.MeshPacket;
import com.meshpay.crypto.model.PaymentInstruction;
import com.meshpay.ingestion.dto.IngestionRequest;
import com.meshpay.ingestion.exception.InvalidEncryptedPacketException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.util.logging.Logger;
import org.springframework.stereotype.Service;

/**
 * Day 13 decryption boundary: the ONLY place in the ingestion service that
 * invokes {@link HybridCryptoService}.
 *
 * <p>Responsibility split:
 * <ul>
 *   <li>{@link MeshPacketCodec} — transport decoding (Base64/JSON &rarr; MeshPacket)</li>
 *   <li>{@code HybridCryptoService.decrypt(...)} — all actual cryptography
 *       (RSA-OAEP unwrap + AES-GCM decrypt), reused verbatim from the crypto
 *       phase. This class contains no AES, no RSA, no key handling logic of its
 *       own — it only supplies the configured private key.</li>
 * </ul>
 *
 * <p><b>Call ordering is enforced by {@code IngestionService}, not here:</b>
 * the Redis idempotency claim and the freshness check must both succeed before
 * this service is reached. This class therefore never sees duplicates or stale
 * packets, and it must never be moved earlier in the pipeline — decryption is
 * deliberately the most expensive step in the ingestion path.
 *
 * <p><b>Error handling:</b> any failure (tampered ciphertext, wrong private key,
 * corrupt wrapped key, structurally invalid payload, or a plaintext that does
 * not deserialize into a valid PaymentInstruction) is converted into
 * {@link InvalidEncryptedPacketException} → HTTP 400. Cryptographic exception
 * types and their messages stay server-side; the client only learns that the
 * encrypted packet could not be decrypted.
 */
@Service
public class PacketDecryptionService {

    private static final Logger log = Logger.getLogger(PacketDecryptionService.class.getName());

    private final MeshPacketCodec codec;
    private final PrivateKey privateKey;

    public PacketDecryptionService(MeshPacketCodec codec, PrivateKey privateKey) {
        this.codec = codec;
        this.privateKey = privateKey;
    }

    /**
     * Decodes and decrypts a claimed, freshness-validated packet.
     *
     * @param request the ingestion request whose {@code encryptedPayload} holds the MeshPacket
     * @return the PaymentInstruction recovered by HybridCryptoService (never a fabricated object)
     * @throws InvalidEncryptedPacketException if decoding or decryption fails
     */
    public PaymentInstruction decrypt(IngestionRequest request) {
        if (request == null) {
            throw new InvalidEncryptedPacketException("request is null");
        }

        // Transport decoding first: cheap, and guarantees the crypto layer only
        // receives a structurally complete MeshPacket.
        MeshPacket packet = codec.decode(request.encryptedPayload());

        try {
            // Single crypto entry point — no decryption logic is duplicated here.
            return HybridCryptoService.decrypt(packet, privateKey);
        } catch (GeneralSecurityException | RuntimeException ex) {
            // AEADBadTagException (tampered ciphertext), RSA-OAEP padding failures,
            // wrong private key, and malformed plaintext all land here.
            // Only the exception type is logged/returned: no key material, no ciphertext.
            log.warning("HybridCryptoService.decrypt failed for bridge " + request.bridgeId()
                    + ": " + ex.getClass().getName());
            throw new InvalidEncryptedPacketException("HybridCryptoService.decrypt failed: "
                    + ex.getClass().getSimpleName(), ex);
        }
    }
}
