package com.meshpay.ingestion.idempotency;

import com.meshpay.ingestion.dto.IngestionRequest;
import org.springframework.stereotype.Component;

/**
 * Builds the canonical representation of a logical packet (Day 12, Layer 1 input).
 *
 * <p>The canonical string must be byte-for-byte identical for the same logical
 * packet, no matter who submitted it or how the request was serialized. It is
 * therefore built only from packet content fields, in a fixed, explicitly written
 * field order:
 *
 * <pre>
 * encryptedPayload=17:base64encodeddata
 * timestamp=20:2026-09-14T12:00:00Z
 * senderId=8:user-123
 * recipientId=8:user-456
 * type=7:PAYMENT
 * </pre>
 *
 * <p>Excluded on purpose: {@code bridgeId} (bridge identity is an authentication /
 * rate-limiting concern, not packet identity), Authorization headers, IP addresses,
 * arrival time, rate-limit state, Redis state, and any server-generated UUID.
 * Because {@code bridgeId} is never read, Bridge A and Bridge B submitting the same
 * packet always produce the same canonical string.
 *
 * <p>Ordering is hard-coded per field, so it cannot drift with reflection order,
 * HashMap iteration, or the JSON property order of the incoming request.
 *
 * <p>Each value is encoded as {@code <length>:<value>}, which keeps the three
 * distinguishable states structurally distinct (a non-null value always contains a
 * colon, the null marker never does):
 * <ul>
 *   <li>{@code null} &rarr; {@code senderId=null}</li>
 *   <li>empty string &rarr; {@code senderId=0:}</li>
 *   <li>literal {@code "null"} &rarr; {@code senderId=4:null}</li>
 * </ul>
 *
 * <p>Callers encode the returned string as UTF-8 before hashing.
 */
@Component
public class PacketCanonicalizer {

    private static final String FIELD_SEPARATOR = "\n";
    private static final String NULL_MARKER = "null";

    /**
     * Returns the canonical representation of the given packet.
     *
     * @param packet the validated ingestion request
     * @return the deterministic canonical string (never null)
     */
    public String canonicalize(IngestionRequest packet) {
        StringBuilder canonical = new StringBuilder();
        // Fixed field order: mirrors IngestionRequest's declaration order with
        // bridgeId removed. Adding a packet field here is a deliberate, reviewable change.
        appendField(canonical, "encryptedPayload", packet.encryptedPayload());
        appendField(canonical, "timestamp", packet.timestamp());
        appendField(canonical, "senderId", packet.senderId());
        appendField(canonical, "recipientId", packet.recipientId());
        appendField(canonical, "type", packet.type());
        return canonical.toString();
    }

    /**
     * Appends {@code name=<length>:<value>} (or {@code name=null}) using the
     * already-built prefix, separated by newlines.
     */
    private void appendField(StringBuilder canonical, String name, String value) {
        if (canonical.length() > 0) {
            canonical.append(FIELD_SEPARATOR);
        }
        canonical.append(name).append('=');
        if (value == null) {
            canonical.append(NULL_MARKER);
        } else {
            // Length prefix is character count; it is only a structural delimiter,
            // never parsed back out, so UTF-16 code units are sufficient here.
            canonical.append(value.length()).append(':').append(value);
        }
    }
}
