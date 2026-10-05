package com.meshpay.ingestion.decryption;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meshpay.crypto.PaymentSerializer;
import com.meshpay.crypto.model.MeshPacket;
import com.meshpay.ingestion.exception.InvalidEncryptedPacketException;
import java.io.IOException;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Converts the wire representation of a MeshPacket into the crypto model that
 * {@code HybridCryptoService.decrypt(...)} expects.
 *
 * <p><b>Wire format:</b> {@code IngestionRequest.encryptedPayload} =
 * Base64(JSON serialization of {@link MeshPacket}), i.e. exactly the packet
 * {@code HybridCryptoService.encrypt(...)} produced
 * ({@code packetId, wrappedAesKey, iv, ciphertext, ttl, createdAt}).
 *
 * <p>This is a transport encoding only — it adds no fields, changes no crypto
 * structure, and performs no cryptography. The byte arrays are rendered by
 * Jackson's default Base64 handling and {@code createdAt} as an ISO-8601 instant.
 *
 * <p>The ObjectMapper starts from mesh-crypto's {@link PaymentSerializer} mapper
 * (JavaTime module, ISO-8601 dates), so encoding here and decoding on the bridge
 * side cannot drift apart. Deserialization ignores derived non-component fields
 * (such as {@code expired}); no second mapper is configured in this service.
 *
 * <p>All decode failures surface as {@link InvalidEncryptedPacketException}
 * (HTTP 400) — Base64 and JSON parser details never reach the client.
 */
@Component
public class MeshPacketCodec {

    private final ObjectMapper objectMapper;

    public MeshPacketCodec() {
        // Start from mesh-crypto's shared mapper (JavaTime module, ISO-8601 dates) so
        // this codec cannot drift from how packets are serialized elsewhere, then COPY
        // it and relax unknown-property handling for deserialization only.
        //
        // Why the copy: MeshPacket.isExpired() is a derived getter, so Jackson writes
        // an extra "expired" property that is not a record component. A round-trip
        // must ignore that derived field instead of failing the decode. Structural
        // strictness is preserved below by requiring wrappedAesKey/iv/ciphertext.
        this.objectMapper = PaymentSerializer.objectMapper().copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Encodes a MeshPacket into the bridge-facing Base64(JSON) payload.
     * Used by tests and tooling that build real packets with
     * {@code HybridCryptoService.encrypt(...)}.
     *
     * @param packet the encrypted packet exactly as produced by the crypto layer
     * @return Base64-encoded JSON
     */
    public String encode(MeshPacket packet) {
        if (packet == null) {
            throw new IllegalArgumentException("MeshPacket must not be null");
        }
        try {
            return Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(packet));
        } catch (IOException ex) {
            throw new InvalidEncryptedPacketException("MeshPacket could not be serialized", ex);
        }
    }

    /**
     * Decodes the bridge-supplied payload back into a MeshPacket.
     *
     * @param payload Base64(JSON) payload from {@code IngestionRequest.encryptedPayload}
     * @return the decoded MeshPacket, guaranteed to carry the three cryptographic fields
     * @throws InvalidEncryptedPacketException if the payload is not valid Base64/JSON
     *         or is missing wrappedAesKey / iv / ciphertext
     */
    public MeshPacket decode(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new InvalidEncryptedPacketException("encryptedPayload is blank");
        }

        byte[] json;
        try {
            // Strict decoder: rejects malformed Base64 instead of silently skipping bad characters.
            json = Base64.getDecoder().decode(payload.trim());
        } catch (IllegalArgumentException ex) {
            throw new InvalidEncryptedPacketException("encryptedPayload is not valid Base64", ex);
        }

        MeshPacket packet;
        try {
            packet = objectMapper.readValue(json, MeshPacket.class);
        } catch (IOException ex) {
            throw new InvalidEncryptedPacketException("encryptedPayload is not a serialized MeshPacket", ex);
        }

        if (packet == null || packet.wrappedAesKey() == null || packet.iv() == null
                || packet.ciphertext() == null) {
            // A structurally incomplete packet can never decrypt; reject it here so the
            // crypto layer is only ever handed decodable material.
            throw new InvalidEncryptedPacketException("MeshPacket is missing cryptographic fields");
        }
        return packet;
    }
}
