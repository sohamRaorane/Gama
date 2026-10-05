package com.meshpay.ingestion.exception;

/**
 * Thrown when a fresh, already-claimed packet still cannot be turned into a
 * PaymentInstruction: the payload is not a decodable MeshPacket, or
 * {@code HybridCryptoService.decrypt(...)} rejected it (tampered ciphertext,
 * wrong RSA private key, corrupt wrapped AES key).
 *
 * <p>Handled by {@link GlobalExceptionHandler} as HTTP 400 BAD_REQUEST with
 * error code {@code INVALID_ENCRYPTED_PACKET}. The underlying cryptographic
 * exception is attached as the cause for server-side logging only — no RSA/AES
 * detail, stack trace, or key material is ever serialized to the client.
 *
 * <p>This is only reachable AFTER a successful idempotency claim and a passing
 * freshness check; a duplicate or stale packet never produces this error.
 */
public class InvalidEncryptedPacketException extends RuntimeException {

    /** Server-side diagnostic only (exception class / stage that failed). */
    private final transient String detail;

    public InvalidEncryptedPacketException(String detail, Throwable cause) {
        super("Encrypted packet could not be decrypted", cause);
        this.detail = detail;
    }

    public InvalidEncryptedPacketException(String detail) {
        super("Encrypted packet could not be decrypted");
        this.detail = detail;
    }

    /**
     * @return server-side diagnostic detail describing which stage failed
     */
    public String getDetail() {
        return detail;
    }
}
