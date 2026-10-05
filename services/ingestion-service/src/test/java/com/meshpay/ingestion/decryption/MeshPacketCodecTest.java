package com.meshpay.ingestion.decryption;

import com.meshpay.crypto.HybridCryptoService;
import com.meshpay.crypto.RsaOaepCipher;
import com.meshpay.crypto.model.MeshPacket;
import com.meshpay.crypto.model.PaymentInstruction;
import com.meshpay.ingestion.exception.InvalidEncryptedPacketException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Transport encoding only: {@code encryptedPayload} = Base64(JSON of MeshPacket).
 * No cryptography is exercised here beyond generating a real packet to encode —
 * the crypto round-trip itself is proven in {@code PacketDecryptionServiceTest}.
 */
class MeshPacketCodecTest {

    private final MeshPacketCodec codec = new MeshPacketCodec();

    private MeshPacket realEncryptedPacket() {
        KeyPair keyPair = RsaOaepCipher.generateKeyPair();
        PaymentInstruction instruction = new PaymentInstruction(
                "user-1", "user-2", 1500L, "nonce-codec-1", Instant.parse("2026-10-05T11:59:50Z"));
        return HybridCryptoService.encrypt(instruction, keyPair.getPublic());
    }

    @Test
    void encodeThenDecodeRoundTripsEveryMeshPacketField() {
        MeshPacket original = realEncryptedPacket();

        MeshPacket decoded = codec.decode(codec.encode(original));

        assertEquals(original.packetId(), decoded.packetId());
        assertArrayEquals(original.wrappedAesKey(), decoded.wrappedAesKey());
        assertArrayEquals(original.iv(), decoded.iv());
        assertArrayEquals(original.ciphertext(), decoded.ciphertext());
        assertEquals(original.ttl(), decoded.ttl());
        assertEquals(original.createdAt(), decoded.createdAt());
    }

    @Test
    void encodedPayloadIsBase64ThatDecodesToJson() throws Exception {
        MeshPacket original = realEncryptedPacket();

        byte[] json = Base64.getDecoder().decode(codec.encode(original));

        String asJson = new String(json, StandardCharsets.UTF_8);
        assertNotNull(asJson);
        assertEquals(true, asJson.contains("wrappedAesKey"), "JSON must carry the wrapped AES key field");
        assertEquals(true, asJson.contains("ciphertext"), "JSON must carry the ciphertext field");
        assertEquals(true, asJson.contains("packetId"), "JSON must carry the packetId field");
    }

    @Test
    void decodeRejectsNonBase64Payload() {
        // "encrypted-data-123" contains '-' which is not in the standard Base64 alphabet.
        InvalidEncryptedPacketException ex = assertThrows(InvalidEncryptedPacketException.class,
                () -> codec.decode("encrypted-data-123"));
        assertEquals("Encrypted packet could not be decrypted", ex.getMessage());
    }

    @Test
    void decodeRejectsBase64OfNonJsonGarbage() {
        String garbage = Base64.getEncoder().encodeToString("definitely not json".getBytes(StandardCharsets.UTF_8));

        assertThrows(InvalidEncryptedPacketException.class, () -> codec.decode(garbage));
    }

    @Test
    void decodeRejectsJsonThatIsNotAMeshPacket() {
        String json = Base64.getEncoder().encodeToString(
                "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8));

        assertThrows(InvalidEncryptedPacketException.class, () -> codec.decode(json));
    }

    @Test
    void decodeRejectsMeshPacketMissingCryptographicFields() {
        String json = Base64.getEncoder().encodeToString(
                "{\"packetId\":\"550e8400-e29b-41d4-a716-446655440000\",\"ttl\":300}"
                        .getBytes(StandardCharsets.UTF_8));

        InvalidEncryptedPacketException ex = assertThrows(InvalidEncryptedPacketException.class,
                () -> codec.decode(json));
        assertEquals(true, ex.getDetail().contains("missing cryptographic fields"));
    }

    @Test
    void decodeRejectsBlankPayload() {
        assertThrows(InvalidEncryptedPacketException.class, () -> codec.decode(" "));
        assertThrows(InvalidEncryptedPacketException.class, () -> codec.decode(null));
    }

    @Test
    void encodeRejectsNullPacket() {
        assertThrows(IllegalArgumentException.class, () -> codec.encode(null));
    }
}
