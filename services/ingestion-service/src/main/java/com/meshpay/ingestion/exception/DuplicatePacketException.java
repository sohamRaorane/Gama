package com.meshpay.ingestion.exception;

/**
 * Thrown when a packet fingerprint has already been claimed in Redis within the
 * idempotency window, i.e. the same logical packet was submitted more than once.
 *
 * <p>Handled by {@link GlobalExceptionHandler} as HTTP 409 CONFLICT. The duplicate
 * is rejected before any database write; the existing claim is never overwritten.
 */
public class DuplicatePacketException extends RuntimeException {

    private final transient String packetHash;

    public DuplicatePacketException(String packetHash) {
        super("Packet has already been submitted");
        this.packetHash = packetHash;
    }

    /**
     * @return the SHA-256 packet fingerprint that is already claimed
     */
    public String getPacketHash() {
        return packetHash;
    }
}
