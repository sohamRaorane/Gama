package com.meshpay.ingestion.decryption;

import com.meshpay.crypto.HybridCryptoService;
import com.meshpay.crypto.RsaOaepCipher;
import com.meshpay.crypto.model.MeshPacket;
import com.meshpay.crypto.model.PaymentInstruction;
import com.meshpay.ingestion.dto.IngestionRequest;
import com.meshpay.ingestion.exception.InvalidEncryptedPacketException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

/**
 * Day 13 decryption boundary tests.
 *
 * <ul>
 *   <li>Real cryptographic round-trips (no mocked crypto): a genuine
 *       HybridCryptoService.encrypt(...) packet must decode and decrypt back to
 *       the original PaymentInstruction.</li>
 *   <li>Tampered / wrong-key / undecodable material must surface as HTTP-400
 *       InvalidEncryptedPacketException, never as a leaked crypto exception.</li>
 *   <li>A static-mock test proves this class DELEGATES to HybridCryptoService —
 *       i.e. the ingestion service reuses the crypto phase instead of
 *       implementing a second decryptor.</li>
 * </ul>
 */
class PacketDecryptionServiceTest {

    private KeyPair backendKeyPair;
    private MeshPacketCodec codec;
    private PacketDecryptionService decryptionService;

    private static final PaymentInstruction INSTRUCTION = new PaymentInstruction(
            "user-123", "user-456", 4200L, "nonce-day13-001",
            Instant.parse("2026-10-05T11:59:45Z"));

    @BeforeEach
    void setUp() {
        backendKeyPair = RsaOaepCipher.generateKeyPair();
        codec = new MeshPacketCodec();
        decryptionService = new PacketDecryptionService(codec, backendKeyPair.getPrivate());
    }

    private IngestionRequest requestFor(MeshPacket packet) {
        return IngestionRequest.builder()
                .encryptedPayload(codec.encode(packet))
                .bridgeId("bridge-001")
                .timestamp(Instant.parse("2026-10-05T11:59:50Z").toString())
                .senderId("user-123")
                .recipientId("user-456")
                .type("PAYMENT")
                .build();
    }

    private MeshPacket encryptForBackend() {
        return HybridCryptoService.encrypt(INSTRUCTION, backendKeyPair.getPublic());
    }

    // --- Real round-trip ------------------------------------------------------------

    @Test
    void freshPacketDecryptsToTheOriginalPaymentInstruction() {
        MeshPacket packet = encryptForBackend();

        PaymentInstruction recovered = decryptionService.decrypt(requestFor(packet));

        assertEquals(INSTRUCTION, recovered,
                "the recovered instruction must be the real object produced by HybridCryptoService");
        assertEquals("nonce-day13-001", recovered.nonce(), "the sender's nonce must survive the round trip");
    }

    // --- Rejections -----------------------------------------------------------------

    @Test
    void tamperedCiphertextIsRejectedAsInvalidEncryptedPacket() {
        MeshPacket packet = encryptForBackend();
        byte[] tampered = Arrays.copyOf(packet.ciphertext(), packet.ciphertext().length);
        tampered[0] ^= 0xFF;
        MeshPacket tamperedPacket = new MeshPacket(packet.packetId(), packet.wrappedAesKey(),
                packet.iv(), tampered, packet.ttl(), packet.createdAt());

        InvalidEncryptedPacketException ex = assertThrows(InvalidEncryptedPacketException.class,
                () -> decryptionService.decrypt(requestFor(tamperedPacket)));

        assertEquals("Encrypted packet could not be decrypted", ex.getMessage());
        assertEquals(true, ex.getDetail().contains("HybridCryptoService.decrypt failed"));
        // The AEAD detail stays on the server as the cause; the public message is generic.
        assertEquals(true, ex.getMessage().length() < 80);
        assertTrue(ex.getCause() != null && ex.getCause().getClass().getName().contains("AEAD"),
                "cause must retain the AEAD failure for server-side diagnosis");
    }

    @Test
    void tamperedWrappedKeyIsRejectedAsInvalidEncryptedPacket() {
        MeshPacket packet = encryptForBackend();
        byte[] tampered = Arrays.copyOf(packet.wrappedAesKey(), packet.wrappedAesKey().length);
        tampered[0] ^= 0xFF;
        MeshPacket tamperedPacket = new MeshPacket(packet.packetId(), tampered,
                packet.iv(), packet.ciphertext(), packet.ttl(), packet.createdAt());

        assertThrows(InvalidEncryptedPacketException.class,
                () -> decryptionService.decrypt(requestFor(tamperedPacket)));
    }

    @Test
    void wrongPrivateKeyIsRejectedAsInvalidEncryptedPacket() {
        MeshPacket packet = encryptForBackend(); // encrypted for backendKeyPair's public key
        KeyPair unrelatedKeyPair = RsaOaepCipher.generateKeyPair(); // backend does NOT hold this private key
        PacketDecryptionService wrongKeyService =
                new PacketDecryptionService(codec, unrelatedKeyPair.getPrivate());

        InvalidEncryptedPacketException ex = assertThrows(InvalidEncryptedPacketException.class,
                () -> wrongKeyService.decrypt(requestFor(packet)));

        assertEquals("Encrypted packet could not be decrypted", ex.getMessage());
        assertTrue(ex.getCause() != null, "cause must be retained for server-side logging");
    }

    @Test
    void undecodablePayloadIsRejectedBeforeAnyKeyIsUsed() {
        IngestionRequest request = IngestionRequest.builder()
                .encryptedPayload("not-a-real-mesh-packet")
                .bridgeId("bridge-001")
                .timestamp("2026-10-05T11:59:50Z")
                .type("PAYMENT")
                .build();

        InvalidEncryptedPacketException ex = assertThrows(InvalidEncryptedPacketException.class,
                () -> decryptionService.decrypt(request));

        assertEquals("Encrypted packet could not be decrypted", ex.getMessage());
    }

    @Test
    void nullRequestIsRejected() {
        assertThrows(InvalidEncryptedPacketException.class, () -> decryptionService.decrypt(null));
    }

    // --- Reuse of the crypto phase --------------------------------------------------

    @Test
    void delegatesToHybridCryptoServiceAndToNothingElse() {
        MeshPacket packet = encryptForBackend();
        PrivateKey privateKey = backendKeyPair.getPrivate();

        try (MockedStatic<HybridCryptoService> hybrid = mockStatic(HybridCryptoService.class)) {
            PaymentInstruction expected = INSTRUCTION;
            hybrid.when(() -> HybridCryptoService.decrypt(any(MeshPacket.class), any(PrivateKey.class)))
                    .thenReturn(expected);

            PaymentInstruction actual = decryptionService.decrypt(requestFor(packet));

            // The static crypto entry point from the crypto phase is the one invoked,
            // exactly once, with the configured private key.
            ArgumentCaptor<MeshPacket> packetCaptor = ArgumentCaptor.forClass(MeshPacket.class);
            ArgumentCaptor<PrivateKey> keyCaptor = ArgumentCaptor.forClass(PrivateKey.class);
            hybrid.verify(() -> HybridCryptoService.decrypt(packetCaptor.capture(), keyCaptor.capture()));

            MeshPacket usedPacket = packetCaptor.getValue();
            assertArrayEquals(packet.ciphertext(), usedPacket.ciphertext(),
                    "the decoded ciphertext must be handed to HybridCryptoService unchanged");
            assertArrayEquals(packet.wrappedAesKey(), usedPacket.wrappedAesKey());
            assertArrayEquals(packet.iv(), usedPacket.iv());
            assertSame(privateKey, keyCaptor.getValue(), "the configured backend private key must be used");
            assertSame(expected, actual, "the value returned by HybridCryptoService must be passed through unchanged");
        }
    }
}
