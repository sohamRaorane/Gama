package com.meshpay.ingestion.exception;

/**
 * Thrown when a packet's timestamp is older than the configured freshness
 * window, i.e. the packet arrived too late to be trusted as a live submission.
 *
 * <p>Handled by {@link GlobalExceptionHandler} as HTTP 400 BAD_REQUEST with
 * error code {@code PACKET_EXPIRED}. A stale packet is rejected BEFORE any
 * cryptographic decryption runs, so freshness validation is cheaper than
 * decryption and always the first time-based gate.
 *
 * <p>The response intentionally reveals neither the window size nor the
 * configured clock skew — only that the packet has expired.
 */
public class StalePacketException extends RuntimeException {

    public StalePacketException() {
        super("Packet has expired");
    }
}
