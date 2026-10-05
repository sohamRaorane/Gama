package com.meshpay.ingestion.exception;

/**
 * Thrown when the packet timestamp cannot be trusted: it is either not a valid
 * ISO-8601 instant, or it lies further in the future than the allowed clock skew
 * (a future timestamp must never bypass the age calculation).
 *
 * <p>Handled by {@link GlobalExceptionHandler} as HTTP 400 BAD_REQUEST with
 * error code {@code INVALID_PACKET_TIMESTAMP}. Raised by the freshness gate,
 * therefore always BEFORE decryption.
 *
 * <p>Only the fixed message reaches the client. The parse/skew {@code reason}
 * stays server-side for diagnostics (returned by {@link #getReason()}, logged
 * by the handler, never serialized into the response body).
 */
public class InvalidPacketTimestampException extends RuntimeException {

    private final transient String reason;

    public InvalidPacketTimestampException(String reason) {
        super("Packet timestamp is invalid");
        this.reason = reason;
    }

    /**
     * @return server-side diagnostic detail (e.g. "unparseable timestamp" / "too far in the future")
     */
    public String getReason() {
        return reason;
    }
}
