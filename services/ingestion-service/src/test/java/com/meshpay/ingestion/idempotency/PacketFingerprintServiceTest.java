package com.meshpay.ingestion.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meshpay.ingestion.dto.IngestionRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day 12 Layer 1 verification: the packet fingerprint must be deterministic,
 * bridge-independent, and sensitive to every meaningful packet field.
 */
class PacketFingerprintServiceTest {

    /** Well-known SHA-256 of the ASCII string "abc" (FIPS 180-2 test vector). */
    private static final String SHA256_OF_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private final PacketCanonicalizer canonicalizer = new PacketCanonicalizer();
    private final PacketFingerprintService fingerprintService = new PacketFingerprintService(canonicalizer);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private IngestionRequest packet(String bridgeId) {
        return packet(bridgeId, "base64encodeddata", "2026-09-14T12:00:00Z",
                "sender-1", "receiver-2", "PAYMENT");
    }

    private IngestionRequest packet(String bridgeId, String encryptedPayload, String timestamp,
                                    String senderId, String recipientId, String type) {
        return IngestionRequest.builder()
                .encryptedPayload(encryptedPayload)
                .bridgeId(bridgeId)
                .timestamp(timestamp)
                .senderId(senderId)
                .recipientId(recipientId)
                .type(type)
                .build();
    }

    // --- Canonicalization contract -------------------------------------------------

    @Test
    void canonicalRepresentationHasFixedFieldOrderAndLengthPrefixedValues() {
        String expected = String.join("\n",
                "encryptedPayload=17:base64encodeddata",
                "timestamp=20:2026-09-14T12:00:00Z",
                "senderId=8:sender-1",
                "recipientId=10:receiver-2",
                "type=7:PAYMENT");

        assertEquals(expected, canonicalizer.canonicalize(packet("bridge-001")));
    }

    @Test
    void canonicalRepresentationExcludesBridgeId() {
        String canonical = canonicalizer.canonicalize(packet("bridge-001"));

        assertFalse(canonical.contains("bridge-001"),
                "bridgeId must not participate in packet identity");
        assertFalse(canonical.contains("bridgeId"),
                "canonical form must not contain a bridgeId field");
    }

    @Test
    void canonicalRepresentationIsStableAcrossInvocations() {
        IngestionRequest request = packet("bridge-001");

        assertEquals(canonicalizer.canonicalize(request), canonicalizer.canonicalize(request));
    }

    @Test
    void nullEmptyAndLiteralNullValuesAreDistinct() {
        String nullSender = canonicalizer.canonicalize(
                packet("bridge-001", "payload", "2026-09-14T12:00:00Z", null, "receiver-2", "PAYMENT"));
        String emptySender = canonicalizer.canonicalize(
                packet("bridge-001", "payload", "2026-09-14T12:00:00Z", "", "receiver-2", "PAYMENT"));
        String literalNullSender = canonicalizer.canonicalize(
                packet("bridge-001", "payload", "2026-09-14T12:00:00Z", "null", "receiver-2", "PAYMENT"));

        assertTrue(nullSender.contains("senderId=null"), "null must be encoded as senderId=null");
        assertTrue(emptySender.contains("senderId=0:"), "empty string must be encoded as senderId=0:");
        assertTrue(literalNullSender.contains("senderId=4:null"), "\"null\" must be encoded with a length prefix");

        assertNotEquals(nullSender, emptySender);
        assertNotEquals(nullSender, literalNullSender);
        assertNotEquals(emptySender, literalNullSender);
    }

    // --- Bridge independence (Day 12 core requirement) -----------------------------

    @Test
    void samePacketFromDifferentBridgesProducesIdenticalHash() {
        String hashA = fingerprintService.fingerprint(packet("bridge-A"));
        String hashB = fingerprintService.fingerprint(packet("bridge-B"));

        assertEquals(hashA, hashB,
                "bridgeId must not be part of the fingerprint: hash(A, P) must equal hash(B, P)");
    }

    // --- Sensitivity to meaningful packet changes ----------------------------------

    @Test
    void differentEncryptedPayloadProducesDifferentHash() {
        assertNotEquals(
                fingerprintService.fingerprint(packet("bridge-A", "payload-1", "2026-09-14T12:00:00Z",
                        "sender-1", "receiver-2", "PAYMENT")),
                fingerprintService.fingerprint(packet("bridge-A", "payload-2", "2026-09-14T12:00:00Z",
                        "sender-1", "receiver-2", "PAYMENT")));
    }

    @Test
    void differentTimestampProducesDifferentHash() {
        assertNotEquals(
                fingerprintService.fingerprint(packet("bridge-A", "payload", "2026-09-14T12:00:00Z",
                        "sender-1", "receiver-2", "PAYMENT")),
                fingerprintService.fingerprint(packet("bridge-A", "payload", "2026-09-14T12:00:01Z",
                        "sender-1", "receiver-2", "PAYMENT")));
    }

    @Test
    void differentSenderIdProducesDifferentHash() {
        assertNotEquals(
                fingerprintService.fingerprint(packet("bridge-A", "payload", "2026-09-14T12:00:00Z",
                        "sender-1", "receiver-2", "PAYMENT")),
                fingerprintService.fingerprint(packet("bridge-A", "payload", "2026-09-14T12:00:00Z",
                        "sender-9", "receiver-2", "PAYMENT")));
    }

    @Test
    void differentRecipientIdProducesDifferentHash() {
        assertNotEquals(
                fingerprintService.fingerprint(packet("bridge-A", "payload", "2026-09-14T12:00:00Z",
                        "sender-1", "receiver-2", "PAYMENT")),
                fingerprintService.fingerprint(packet("bridge-A", "payload", "2026-09-14T12:00:00Z",
                        "sender-1", "receiver-9", "PAYMENT")));
    }

    @Test
    void differentTypeProducesDifferentHash() {
        assertNotEquals(
                fingerprintService.fingerprint(packet("bridge-A", "payload", "2026-09-14T12:00:00Z",
                        "sender-1", "receiver-2", "PAYMENT")),
                fingerprintService.fingerprint(packet("bridge-A", "payload", "2026-09-14T12:00:00Z",
                        "sender-1", "receiver-2", "HEARTBEAT")));
    }

    // --- JSON field order independence ---------------------------------------------

    @Test
    void jsonPropertyOrderDoesNotChangeFingerprint() throws Exception {
        IngestionRequest orderedJson = objectMapper.readValue("""
                {
                    "encryptedPayload": "base64encodeddata",
                    "bridgeId": "bridge-A",
                    "timestamp": "2026-09-14T12:00:00Z",
                    "senderId": "sender-1",
                    "recipientId": "receiver-2",
                    "type": "PAYMENT"
                }
                """, IngestionRequest.class);

        IngestionRequest shuffledJson = objectMapper.readValue("""
                {
                    "type": "PAYMENT",
                    "recipientId": "receiver-2",
                    "bridgeId": "bridge-B",
                    "senderId": "sender-1",
                    "timestamp": "2026-09-14T12:00:00Z",
                    "encryptedPayload": "base64encodeddata"
                }
                """, IngestionRequest.class);

        assertEquals(fingerprintService.fingerprint(orderedJson),
                fingerprintService.fingerprint(shuffledJson),
                "JSON property order (and bridgeId) must not change the fingerprint");
    }

    // --- Hash implementation --------------------------------------------------------

    @Test
    void sha256MatchesJdkReferenceVectorAsLowercaseHex() {
        assertEquals(SHA256_OF_ABC, fingerprintService.sha256Hex("abc"));
    }

    @Test
    void fingerprintIsSixtyFourLowercaseHexCharacters() {
        String hash = fingerprintService.fingerprint(packet("bridge-001"));

        assertEquals(64, hash.length(), "SHA-256 must render as 64 hex characters");
        assertTrue(hash.matches("[0-9a-f]{64}"), "fingerprint must be lowercase hex, was: " + hash);
    }
}
