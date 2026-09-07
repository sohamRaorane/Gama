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
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

public final class AesGcmCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int AES_KEY_LENGTH_BITS = 256;

    private static final ObjectMapper MAPPER = PaymentSerializer.objectMapper();

    private AesGcmCipher() {}

    public static SecretKey generateKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(AES_KEY_LENGTH_BITS, new SecureRandom());
            return keyGen.generateKey();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to generate AES key", e);
        }
    }

    public static SecretKey keyFromBytes(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != 32) {
            throw new IllegalArgumentException("AES-256 key must be exactly 32 bytes, got " +
                    (keyBytes == null ? 0 : keyBytes.length));
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    public static EncryptedPayload encrypt(PaymentInstruction instruction, SecretKey key) {
        validateKey(key);
        try {
            byte[] plaintext = MAPPER.writeValueAsBytes(instruction);

            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            return new EncryptedPayload(iv, ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public static PaymentInstruction decrypt(EncryptedPayload payload, SecretKey key)
            throws AEADBadTagException {
        validateKey(key);
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, payload.iv()));
            byte[] plaintext = cipher.doFinal(payload.ciphertext());

            return MAPPER.readValue(plaintext, PaymentInstruction.class);
        } catch (AEADBadTagException e) {
            throw e;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed", e);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    private static void validateKey(SecretKey key) {
        if (key == null) {
            throw new IllegalArgumentException("AES key must not be null");
        }
        if (!"AES".equals(key.getAlgorithm())) {
            throw new IllegalArgumentException("Key must be an AES key, got " + key.getAlgorithm());
        }
        if (key.getEncoded().length != 32) {
            throw new IllegalArgumentException("AES key must be 256 bits (32 bytes), got " +
                    (key.getEncoded().length * 8) + " bits");
        }
    }
}
