package com.meshpay.crypto;

import com.meshpay.crypto.model.MeshPacket;
import com.meshpay.crypto.model.PaymentInstruction;
import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class HybridCryptoServiceTest {

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
        // Generate a fresh RSA key pair for this test.
        // In production the public key lives on the sender side,
        // the private key lives on the backend.
        KeyPair keyPair = RsaOaepCipher.generateKeyPair();
        PaymentInstruction original = sampleInstruction();

        // Encrypt: PaymentInstruction → AES-GCM → ciphertext + RSA-OAEP wrapped key → MeshPacket
        MeshPacket packet = HybridCryptoService.encrypt(original, keyPair.getPublic());

        // Verify that the MeshPacket contains all the expected pieces
        assertNotNull(packet.wrappedAesKey(), "MeshPacket must contain a wrapped AES key");
        assertNotNull(packet.iv(), "MeshPacket must contain an IV");
        assertNotNull(packet.ciphertext(), "MeshPacket must contain ciphertext");

        // Decrypt: MeshPacket → RSA-OAEP unwrap → AES session key → AES-GCM decrypt → PaymentInstruction
        PaymentInstruction decrypted = HybridCryptoService.decrypt(packet, keyPair.getPrivate());

        assertEquals(original, decrypted, "Decrypted instruction must equal the original");
    }

    @Test
    void wrongPrivateKeyIsRejected() {
        // Generate two separate RSA key pairs.
        // keyPairA is used for encryption, keyPairB is an unrelated key.
        KeyPair keyPairA = RsaOaepCipher.generateKeyPair();
        KeyPair keyPairB = RsaOaepCipher.generateKeyPair();
        PaymentInstruction instruction = sampleInstruction();

        // Encrypt with keyPairA's public key
        MeshPacket packet = HybridCryptoService.encrypt(instruction, keyPairA.getPublic());

        // Attempting to decrypt with keyPairB's private key must fail.
        // The RSA-OAEP unwrap step cannot recover the AES session key
        // because the wrapped key was encrypted for keyPairA's public key.
        assertThrows(Exception.class, () ->
                HybridCryptoService.decrypt(packet, keyPairB.getPrivate())
        );
    }

    @Test
    void tamperedCiphertextIsRejected() throws Exception {
        KeyPair keyPair = RsaOaepCipher.generateKeyPair();
        PaymentInstruction instruction = sampleInstruction();

        MeshPacket packet = HybridCryptoService.encrypt(instruction, keyPair.getPublic());

        // Tamper with the ciphertext by flipping bits in the first byte.
        // This will cause the AES-GCM authentication tag check to fail.
        byte[] tamperedCiphertext = Arrays.copyOf(
                packet.ciphertext(), packet.ciphertext().length);
        tamperedCiphertext[0] ^= 0xFF;

        MeshPacket tamperedPacket = new MeshPacket(
                packet.packetId(),
                packet.wrappedAesKey(),
                packet.iv(),
                tamperedCiphertext,
                packet.ttl(),
                packet.createdAt()
        );

        // AES-GCM must reject the tampered ciphertext via authentication tag mismatch
        assertThrows(AEADBadTagException.class, () ->
                HybridCryptoService.decrypt(tamperedPacket, keyPair.getPrivate())
        );
    }

    @Test
    void tamperedWrappedKeyIsRejected() {
        KeyPair keyPair = RsaOaepCipher.generateKeyPair();
        PaymentInstruction instruction = sampleInstruction();

        MeshPacket packet = HybridCryptoService.encrypt(instruction, keyPair.getPublic());

        // Tamper with the RSA-wrapped AES key by flipping bits.
        // The RSA-OAEP unwrap step must fail because the padding check detects corruption.
        byte[] tamperedWrappedKey = Arrays.copyOf(
                packet.wrappedAesKey(), packet.wrappedAesKey().length);
        tamperedWrappedKey[0] ^= 0xFF;

        MeshPacket tamperedPacket = new MeshPacket(
                packet.packetId(),
                tamperedWrappedKey,
                packet.iv(),
                packet.ciphertext(),
                packet.ttl(),
                packet.createdAt()
        );

        assertThrows(Exception.class, () ->
                HybridCryptoService.decrypt(tamperedPacket, keyPair.getPrivate())
        );
    }

    @Test
    void freshAesKeyPerEncryption() {
        KeyPair keyPair = RsaOaepCipher.generateKeyPair();
        PaymentInstruction instruction = sampleInstruction();

        // Two encryptions of the same instruction must produce different wrapped AES keys.
        // This proves a fresh AES session key is generated each time.
        MeshPacket first = HybridCryptoService.encrypt(instruction, keyPair.getPublic());
        MeshPacket second = HybridCryptoService.encrypt(instruction, keyPair.getPublic());

        assertFalse(Arrays.equals(first.wrappedAesKey(), second.wrappedAesKey()),
                "Each encryption must use a different AES session key");
    }

    @Test
    void freshIvPerEncryption() {
        KeyPair keyPair = RsaOaepCipher.generateKeyPair();
        PaymentInstruction instruction = sampleInstruction();

        // Two encryptions of the same instruction must produce different IVs.
        // This proves a fresh random IV is generated each time,
        // preventing IV reuse attacks on AES-GCM.
        MeshPacket first = HybridCryptoService.encrypt(instruction, keyPair.getPublic());
        MeshPacket second = HybridCryptoService.encrypt(instruction, keyPair.getPublic());

        assertFalse(Arrays.equals(first.iv(), second.iv()),
                "Each encryption must use a different IV");
    }
}
