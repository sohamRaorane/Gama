package com.meshpay.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meshpay.crypto.model.EncryptedPayload;
import com.meshpay.crypto.model.PaymentInstruction;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Utility class for secure AES-GCM 256-bit encryption and decryption.
 * Designed specifically for protecting PaymentInstruction payloads.
 */
public final class AesGcmCipher {

    // AES engine using Galois/Counter Mode (GCM) with no padding required
    private static final String ALGORITHM = "AES/GCM/NoPadding";

    // 12 bytes (96 bits) is the standard and recommended IV length for GCM
    private static final int GCM_IV_LENGTH_BYTES = 12;

    // 128-bit authentication tag provides the highest level of tamper protection in GCM
    private static final int GCM_TAG_LENGTH_BITS = 128;

    // Targeted symmetric strength level (AES-256)
    private static final int AES_KEY_LENGTH_BITS = 256;

    // Single, thread-safe instance of SecureRandom reused to prevent CPU bottlenecks
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Jackson object mapper to convert Java objects to/from JSON data
    private static final ObjectMapper MAPPER = PaymentSerializer.objectMapper();

    // Prevents this helper utility from being instantiated externally via "new AesGcmCipher()"
    private AesGcmCipher() {}

    /**
     * Generates a brand new, cryptographically strong AES-256 SecretKey.
     */
    public static SecretKey generateKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            // Initialization of the key generator engine with 256 bits using the secure random pool
            keyGen.init(AES_KEY_LENGTH_BITS, SECURE_RANDOM);
            // Returns a securely generated SecretKey object
            return keyGen.generateKey();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to generate AES key", e);
        }
    }

    /**
     * Restores/loads an existing key from a raw database or vault byte[] array.
     */
    public static SecretKey keyFromBytes(byte[] keyBytes) {
        // Enforce that the incoming key array evaluates to exactly 32 bytes (256 bits)
        if (keyBytes == null || keyBytes.length != 32) {
            throw new IllegalArgumentException("AES-256 key must be exactly 32 bytes, got " +
                    (keyBytes == null ? 0 : keyBytes.length));
        }
        // Wraps the raw byte array into a standard JCA-compliant AES SecretKeySpec container
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Encryption operation: Serializes the payment instruction and encrypts it using AES-GCM.
     */
    public static EncryptedPayload encrypt(PaymentInstruction instruction, SecretKey key) {
        // Run health check validations on the encryption key before usage
        validateKey(key);
        try {
            // Java object --> JSON string --> byte[] array
            byte[] plaintext = MAPPER.writeValueAsBytes(instruction);

            // 12-byte placeholder allocated for the unique initialization vector (IV)
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];

            // Populates the placeholder array container with cryptographically secure random bytes
            // CRITICAL RULE: Never reuse the same IV with the same key in GCM mode
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            // Switches the cipher engine to ENCRYPT_MODE using the key and the GCM specifications (tag length + IV)
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            /*
             * Processes data array stream, automatically computes the 128-bit authentication integrity tag,
             * appends it to the end of the ciphertext stream, and outputs the final encrypted byte array.
             */
            byte[] ciphertext = cipher.doFinal(plaintext);

            // Wraps the unique IV array alongside the ciphertext value into the structural data holder object
            return new EncryptedPayload(iv, ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed due to a cryptographic error", e);
        } catch (IOException e) {
            throw new IllegalStateException("Encryption failed due to a JSON serialization error", e);
        }
    }

    /**
     * Decryption operation: Reverses ciphertext data safely back into a PaymentInstruction object.
     * Explicitly throws AEADBadTagException so calling code can catch tampering attempts.
     */
    public static PaymentInstruction decrypt(EncryptedPayload payload, SecretKey key)
            throws AEADBadTagException {
        // Run health check validations on the decryption key before usage
        validateKey(key);
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            // Switches the cipher engine to DECRYPT_MODE using the same parameters extracted from the payload
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, payload.iv()));

            /*
             * Java automatically isolates the appended tag trailing at the end of the array,
             * runs checksum verification across data blocks, and validates structural integrity.
             * If even a single bit was changed or tampered with, an AEADBadTagException is thrown here.
             */
            byte[] plaintext = cipher.doFinal(payload.ciphertext());

            // Deserialization: byte[] array --> JSON string --> Java Object representation
            return MAPPER.readValue(plaintext, PaymentInstruction.class);
        } catch (AEADBadTagException e) {
            // Rethrow explicitly so the application can catch context manipulation or tampering
            throw e;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed due to a cryptographic error", e);
        } catch (IOException e) {
            throw new IllegalStateException("Decryption failed due to a JSON deserialization error", e);
        }
    }

    /**
     * Validates the integrity of the provided SecretKey.
     */
    private static void validateKey(SecretKey key) {
        if (key == null) {
            throw new IllegalArgumentException("AES key must not be null");
        }
        if (!"AES".equals(key.getAlgorithm())) {
            throw new IllegalArgumentException("Key must be an AES key, got " + key.getAlgorithm());
        }

        // JCA spec allows getEncoded() to return null if keys are unexportable (e.g., inside an HSM or Android KeyStore)
        byte[] encoded = key.getEncoded();

        // FIXED: Only check length if the provider exposes raw access to the underlying bytes
        if (encoded != null && encoded.length != 32) {
            throw new IllegalArgumentException("AES key must be 256 bits (32 bytes), got " +
                    (encoded.length * 8) + " bits");
        }
    }
}
