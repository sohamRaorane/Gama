package com.meshpay.crypto;

import com.meshpay.crypto.model.EncryptedPayload;
import com.meshpay.crypto.model.MeshPacket;
import com.meshpay.crypto.model.PaymentInstruction;

import javax.crypto.SecretKey;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.UUID;

/**
 * Single high-level entry point for all MeshPay encryption and decryption.
 * Orchestrates AES-256-GCM payload encryption and RSA-OAEP session key wrapping.
 *
 * Other MeshPay components should use this service rather than calling
 * AES-GCM or RSA-OAEP directly. This keeps the cryptographic implementation
 * details hidden behind a simple encrypt/decrypt API.
 *
 * The hybrid approach works like this:
 * - A fresh AES-256 session key is generated for each encryption
 * - The PaymentInstruction is encrypted with AES-256-GCM (efficient for arbitrary sizes)
 * - The AES session key is wrapped with RSA-OAEP using the recipient's public key
 * - Both the wrapped key and the AES ciphertext are bundled into a MeshPacket
 *
 * Decryption reverses both layers:
 * - RSA-OAEP unwraps the AES session key using the private key
 * - AES-GCM decrypts the ciphertext using the recovered session key
 */
public final class HybridCryptoService {

    // Mesh packets live for 5 minutes before expiring.
    // This prevents replay attacks by ensuring old packets cannot be resubmitted indefinitely.
    private static final int DEFAULT_TTL_SECONDS = 300;

    // Prevents this utility class from being instantiated externally
    private HybridCryptoService() {}

    /**
     * Encrypts a PaymentInstruction using hybrid cryptography.
     *
     * The flow is:
     * 1. Serialize the instruction to JSON bytes
     * 2. Generate a fresh AES-256 session key
     * 3. Encrypt the serialized instruction with AES-256-GCM
     * 4. RSA-OAEP wrap the AES session key using the recipient's public key
     * 5. Bundle everything into a MeshPacket
     *
     * @param instruction   the plaintext payment instruction to encrypt
     * @param recipientKey  the recipient's RSA public key for wrapping the AES session key
     * @return              a MeshPacket containing the encrypted payload and wrapped session key
     */
    public static MeshPacket encrypt(PaymentInstruction instruction, PublicKey recipientKey) {
        if (instruction == null) {
            throw new IllegalArgumentException("PaymentInstruction must not be null");
        }
        if (recipientKey == null) {
            throw new IllegalArgumentException("Recipient public key must not be null");
        }

        // Step 1: Generate a fresh 256-bit AES session key for this specific encryption.
        // Each encryption operation uses a unique key, so compromising one session
        // does not affect any other session.
        SecretKey aesSessionKey = AesGcmCipher.generateKey();

        // Step 2: Encrypt the PaymentInstruction using AES-256-GCM.
        // This produces a ciphertext that includes an authentication tag to detect tampering.
        EncryptedPayload payload = AesGcmCipher.encrypt(instruction, aesSessionKey);

        // Step 3: Wrap (encrypt) the AES session key using RSA-OAEP with the recipient's public key.
        // Only the holder of the corresponding private key can recover the AES session key.
        byte[] wrappedKey = RsaOaepCipher.wrap(aesSessionKey, recipientKey);

        // Step 4: Assemble the MeshPacket with all the pieces needed for decryption.
        // The raw AES session key is never stored or returned to the caller.
        return new MeshPacket(
                UUID.randomUUID(),
                wrappedKey,
                payload.iv(),
                payload.ciphertext(),
                DEFAULT_TTL_SECONDS,
                Instant.now()
        );
    }

    /**
     * Decrypts a MeshPacket back into a PaymentInstruction.
     *
     * The flow is:
     * 1. Extract the wrapped AES session key from the MeshPacket
     * 2. RSA-OAEP unwrap it using the private key to recover the AES session key
     * 3. Rebuild the AES-encrypted payload from IV and ciphertext
     * 4. AES-GCM decrypt the ciphertext using the recovered session key
     * 5. Deserialize the plaintext back into a PaymentInstruction
     *
     * @param packet  the MeshPacket containing the encrypted payload and wrapped key
     * @param privateKey  the RSA private key used to unwrap the AES session key
     * @return        the decrypted PaymentInstruction
     * @throws javax.crypto.AEADBadTagException if the ciphertext has been tampered with
     */
    public static PaymentInstruction decrypt(MeshPacket packet, PrivateKey privateKey)
            throws javax.crypto.AEADBadTagException {
        if (packet == null) {
            throw new IllegalArgumentException("MeshPacket must not be null");
        }
        if (privateKey == null) {
            throw new IllegalArgumentException("RSA private key must not be null");
        }

        // Step 1: RSA-OAEP unwrap the AES session key from the MeshPacket using the private key.
        // If the wrapped key was tampered with or the wrong private key is used,
        // this step will fail with a security exception.
        SecretKey aesSessionKey = RsaOaepCipher.unwrap(packet.wrappedAesKey(), privateKey);

        // Step 2: Rebuild the EncryptedPayload from the IV and ciphertext stored in the MeshPacket.
        // Defensive copies are handled inside EncryptedPayload's constructor.
        EncryptedPayload payload = new EncryptedPayload(packet.iv(), packet.ciphertext());

        // Step 3: AES-GCM decrypt the payload using the recovered session key.
        // If the ciphertext was tampered with, the GCM authentication tag check will fail
        // and throw an AEADBadTagException.
        return AesGcmCipher.decrypt(payload, aesSessionKey);
    }
}
