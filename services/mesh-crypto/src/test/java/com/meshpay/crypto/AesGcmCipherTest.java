package com.meshpay.crypto;

import com.meshpay.crypto.model.EncryptedPayload;
import com.meshpay.crypto.model.PaymentInstruction;
import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class AesGcmCipherTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-09-07T12:00:00Z");

    private PaymentInstruction sampleInstruction() {
        return new PaymentInstruction(
                "bridge-sender-001",
                "bridge-receiver-001",
                12500L,
                "nonce-001",
                FIXED_TIME
        );
    }

    @Test
    void encryptDecryptRoundTrip() throws Exception {
        PaymentInstruction original = sampleInstruction();
        SecretKey key = AesGcmCipher.generateKey();

        EncryptedPayload encrypted = AesGcmCipher.encrypt(original, key);
        PaymentInstruction decrypted = AesGcmCipher.decrypt(encrypted, key);

        assertEquals(original, decrypted);
    }

    @Test
    void tamperedCiphertextIsRejected() {
        PaymentInstruction original = sampleInstruction();
        SecretKey key = AesGcmCipher.generateKey();

        EncryptedPayload encrypted = AesGcmCipher.encrypt(original, key);

        byte[] tampered = Arrays.copyOf(encrypted.ciphertext(), encrypted.ciphertext().length);
        tampered[0] ^= 0xFF;

        EncryptedPayload tamperedPayload = new EncryptedPayload(encrypted.iv(), tampered);

        assertThrows(AEADBadTagException.class, () ->
            AesGcmCipher.decrypt(tamperedPayload, key)
        );
    }

    @Test
    void repeatedEncryptionProducesDifferentIvs() {
        PaymentInstruction instruction = sampleInstruction();
        SecretKey key = AesGcmCipher.generateKey();

        EncryptedPayload first = AesGcmCipher.encrypt(instruction, key);
        EncryptedPayload second = AesGcmCipher.encrypt(instruction, key);

        assertFalse(Arrays.equals(first.iv(), second.iv()),
                "IVs must be different for each encryption operation");
    }
}
