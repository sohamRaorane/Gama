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
 * Utility class for RSA-OAEP key wrapping and unwrapping.
 * Used to protect AES session keys during hybrid encryption in MeshPay.
 *
 * This class encrypts/decrypts small payloads (like AES-256 keys)
 * using RSA with OAEP padding. Larger payloads are handled by AES-GCM
 * through the HybridCryptoService.
 */
public final class RsaOaepCipher {

    // RSA encryption using Optimal Asymmetric Encryption Padding (OAEP)
    // OAEP is chosen over PKCS#1 v1.5 because it provides provable security
    // against chosen-ciphertext attacks
    private static final String TRANSFORMATION = "RSA/ECB/OAEP/NoPadding";

    // 2048-bit RSA key provides ~112 bits of classical security strength,
    // sufficient for protecting session keys in this context
    private static final int RSA_KEY_SIZE_BITS = 2048;

    // Single, thread-safe instance of SecureRandom reused to prevent CPU bottlenecks
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Prevents this helper utility from being instantiated externally
    private RsaOaepCipher() {}

    /**
     * Generates a fresh RSA-2048 key pair suitable for hybrid encryption.
     * The public key wraps AES session keys, the private key unwraps them.
     */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            // Initialize the RSA engine with 2048-bit key size and the secure random pool
            keyGen.initialize(RSA_KEY_SIZE_BITS, SECURE_RANDOM);
            return keyGen.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to generate RSA key pair", e);
        }
    }

    /**
     * Wraps (encrypts) an AES session key using the recipient's RSA public key.
     * The wrapped key can only be decrypted by the corresponding private key.
     *
     * @param aesKey     the AES-256 session key to protect
     * @param publicKey  the recipient's RSA public key used for wrapping
     * @return           the RSA-OAEP wrapped key as a byte array
     */
    public static byte[] wrap(SecretKey aesKey, PublicKey publicKey) {
        validateAesKey(aesKey);
        if (publicKey == null) {
            throw new IllegalArgumentException("RSA public key must not be null");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);

            // OAEPParameterSpec explicitly defines the digest algorithms used in the padding scheme.
            // SHA-256 is used for both the primary digest and the MGF1 mask generation function.
            // An empty label (PSource.PSpecified with zero bytes) is the standard default.
            OAEPParameterSpec oaepSpec = new OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA256,
                    new PSource.PSpecified(new byte[0])
            );

            // Switches the cipher engine to WRAP_MODE using the public key and OAEP parameters.
            // WRAP_MODE encrypts a SecretKey object directly rather than raw bytes.
            cipher.init(Cipher.WRAP_MODE, publicKey, oaepSpec);

            // Wraps the AES SecretKey into an opaque byte array.
            // The output includes the RSA-encrypted representation of the AES key material.
            return cipher.wrap(aesKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA-OAEP wrap failed", e);
        }
    }

    /**
     * Unwraps (decrypts) a wrapped AES session key using the backend's RSA private key.
     * This reverses the wrap() operation performed with the corresponding public key.
     *
     * @param wrappedKey  the RSA-OAEP encrypted AES key from MeshPacket
     * @param privateKey  the RSA private key used for unwrapping
     * @return            the recovered AES-256 session key
     */
    public static SecretKey unwrap(byte[] wrappedKey, PrivateKey privateKey) {
        if (wrappedKey == null || wrappedKey.length == 0) {
            throw new IllegalArgumentException("Wrapped key must not be null or empty");
        }
        if (privateKey == null) {
            throw new IllegalArgumentException("RSA private key must not be null");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);

            // OAEPParameterSpec must match the parameters used during wrap() exactly.
            // SHA-256 digest, MGF1 with SHA-256, and an empty label.
            OAEPParameterSpec oaepSpec = new OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA256,
                    new PSource.PSpecified(new byte[0])
            );

            // Switches the cipher engine to UNWRAP_MODE using the private key and OAEP parameters.
            // UNWRAP_MODE decrypts the RSA ciphertext back into a SecretKey object.
            cipher.init(Cipher.UNWRAP_MODE, privateKey, oaepSpec);

            // Unwraps the byte array back into a usable AES SecretKey instance.
            // The algorithm hint "AES" tells the JCA to reconstruct the key as an AES key.
            return (SecretKey) cipher.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA-OAEP unwrap failed", e);
        }
    }

    /**
     * Validates that the provided AES key is suitable for wrapping.
     */
    private static void validateAesKey(SecretKey aesKey) {
        if (aesKey == null) {
            throw new IllegalArgumentException("AES key must not be null");
        }
        if (!"AES".equals(aesKey.getAlgorithm())) {
            throw new IllegalArgumentException("Key must be an AES key, got " + aesKey.getAlgorithm());
        }
        byte[] encoded = aesKey.getEncoded();
        if (encoded != null && encoded.length != 32) {
            throw new IllegalArgumentException("AES key must be 256 bits (32 bytes), got " +
                    (encoded.length * 8) + " bits");
        }
    }
}
