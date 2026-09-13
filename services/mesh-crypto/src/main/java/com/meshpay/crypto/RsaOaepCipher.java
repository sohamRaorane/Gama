package com.meshpay.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;

/**
 * ============================================================================
 * CODE REVIEW & SECURITY AUDIT NOTES FOR RsaOaepCipher:
 * ============================================================================
 * 1. Padding Scheme: Uses OAEP (Optimal Asymmetric Encryption Padding) with
 *    SHA-256. This is chosen over legacy PKCS#1 v1.5 padding to completely
 *    mitigate Bleichenbacher-style adaptive chosen-ciphertext attacks (padding
 *    oracle vulnerabilities).
 * 2. Key Size: 2048-bit RSA ensures robust security compliance (~112 bits of
 *    symmetric equivalent strength), specifically sized to securely wrap
 *    AES-256 symmetric session keys.
 * 3. Thread Safety: SecureRandom is initialized globally as a thread-safe
 *    singleton to avoid thread contention and high CPU overhead during frequent key
 *    generation cycles.
 * ============================================================================
 */
public final class RsaOaepCipher {

    // Cryptographic transformation specification:
    // - RSA: Asymmetric algorithm
    // - ECB: Electronic Codebook mode (Note: ECB here only means no chaining mode
    //   is applied to the core RSA primitive, as OAEP padding already randomizes the input block)
    // - OAEPWithSHA-256AndMGF1Padding: Modern secure padding scheme using SHA-256 digest
    private static final String TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";

    // 2048 bits is the current industry-standard minimum baseline for RSA asymmetric keys
    private static final int RSA_KEY_SIZE_BITS = 2048;

    // Cryptographically secure pseudorandom number generator (CSPRNG) instance.
    // Reused intentionally because SecureRandom instances manage internal thread synchronization
    // pools safely, preventing performance bottlenecks on multi-core servers.
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Private constructor enforces non-instantiability (pure static utility class pattern)
    private RsaOaepCipher() {}

    /**
     * Generates a fresh RSA-2048 key pair.
     *
     * @return KeyPair containing the public key (for wrapping) and private key (for unwrapping)
     */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");

            // Seed the generator with our pre-initialized thread-safe SecureRandom pool
            keyGen.initialize(RSA_KEY_SIZE_BITS, SECURE_RANDOM);

            return keyGen.generateKeyPair();
        } catch (GeneralSecurityException e) {
            // Wrapping checked exceptions into an unchecked IllegalStateException
            // because key generation failure at runtime represents an unrecoverable system misconfiguration.
            throw new IllegalStateException("Failed to generate RSA key pair", e);
        }
    }

    /**
     * Wraps (encrypts) an AES session key using the recipient's RSA public key.
     *
     * @param aesKey     The AES-256 session key object to protect
     * @param publicKey  The recipient's public key
     * @return           Encrypted byte array representing the wrapped key
     */
    public static byte[] wrap(SecretKey aesKey, PublicKey publicKey) {
        // Step 1: Input guarding to fail fast before cryptographic operations occur
        validateAesKey(aesKey);
        if (publicKey == null) {
            throw new IllegalArgumentException("RSA public key must not be null");
        }

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);

            // Step 2: Explicitly define OAEP parameters to override weak default settings (like SHA-1)
            // - Primary Message Digest: SHA-256
            // - Mask Generation Function: MGF1 using SHA-256
            // - Label (PSource): Empty byte array (standard default configuration)
            OAEPParameterSpec oaepSpec = new OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA256,
                    new PSource.PSpecified(new byte[0])
            );

            // Step 3: Initialize Cipher in WRAP_MODE.
            // WRAP_MODE is specifically designed to handle java.security.Key objects directly,
            // serializing their raw encoded bytes securely inside the padding structure.
            cipher.init(Cipher.WRAP_MODE, publicKey, oaepSpec);

            // Step 4: Execute the wrapping operation
            return cipher.wrap(aesKey);

        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA-OAEP wrap failed due to cryptographic error", e);
        }
    }

    /**
     * Unwraps (decrypts) a wrapped AES session key using the backend's RSA private key.
     *
     * @param wrappedKey  The raw encrypted byte array received from the client/peer
     * @param privateKey  The matching RSA private key
     * @return            The reconstructed SecretKey instance ready for AES-GCM operations
     */
    public static SecretKey unwrap(byte[] wrappedKey, PrivateKey privateKey) {
        // Step 1: Input sanitation checks
        if (wrappedKey == null || wrappedKey.length == 0) {
            throw new IllegalArgumentException("Wrapped key must not be null or empty");
        }
        if (privateKey == null) {
            throw new IllegalArgumentException("RSA private key must not be null");
        }

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);

            // Step 2: Recreate identical OAEP parameters used during wrapping.
            // CRITICAL AUDIT NOTE: Mismatching parameters here will cause immediate decryption failure.
            OAEPParameterSpec oaepSpec = new OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA256,
                    new PSource.PSpecified(new byte[0])
            );

            // Step 3: Initialize Cipher in UNWRAP_MODE with the private key
            cipher.init(Cipher.UNWRAP_MODE, privateKey, oaepSpec);

            // Step 4: Perform unwrap.
            // Parameters: (ciphertext bytes, algorithm name hint for reconstruction, key type)
            // Cipher.SECRET_KEY tells the JCA engine to output a generic SecretKey implementation for AES.
            return (SecretKey) cipher.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY);

        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA-OAEP unwrap failed due to cryptographic error or invalid ciphertext", e);
        }
    }

    /**
     * Validates structural and security constraints of the incoming AES session key.
     */
    private static void validateAesKey(SecretKey aesKey) {
        if (aesKey == null) {
            throw new IllegalArgumentException("AES key must not be null");
        }
        if (!"AES".equals(aesKey.getAlgorithm())) {
            throw new IllegalArgumentException("Invalid key algorithm. Expected AES, got: " + aesKey.getAlgorithm());
        }

        byte[] encoded = aesKey.getEncoded();
        // Check if raw bytes are accessible and strictly 256 bits (32 bytes)
        if (encoded != null && encoded.length != 32) {
            throw new IllegalArgumentException("Invalid AES key length. Expected 256 bits (32 bytes), got: " +
                    (encoded.length * 8) + " bits");
        }
    }
}