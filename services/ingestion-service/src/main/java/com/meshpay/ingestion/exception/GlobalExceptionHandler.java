package com.meshpay.ingestion.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global exception handler that translates exceptions into consistent JSON error responses.
 *
 * Response format (matches across all error types):
 * {
 *   "timestamp": "2026-09-14T12:00:00Z",
 *   "status": 400,
 *   "error": "VALIDATION_ERROR",
 *   "message": "encryptedPayload: must not be blank",
 *   "path": "/api/v1/packets"
 * }
 *
 * Error types handled:
 * - VALIDATION_ERROR (400): Bean Validation failures from @Valid on IngestionRequest
 * - MALFORMED_REQUEST (400): Missing or unparseable JSON body
 * - BRIDGE_ID_MISMATCH (400): Bridge submitted packet claiming a different identity than JWT
 * - DUPLICATE_PACKET (409): Packet fingerprint already claimed in Redis idempotency window
 * - PACKET_EXPIRED (400): Packet timestamp outside the Day 13 freshness window
 * - INVALID_PACKET_TIMESTAMP (400): Timestamp unparseable or beyond the allowed clock skew
 * - INVALID_ENCRYPTED_PACKET (400): Fresh claimed packet that HybridCryptoService could not decrypt
 * - INTERNAL_ERROR (500): Any unexpected exception (catch-all)
 *
 * Note: 401 and 429 responses are NOT handled here — they are written directly by
 * JwtAuthenticationFilter, which runs before the controller layer and never lets
 * rejected requests reach this handler.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = Logger.getLogger(GlobalExceptionHandler.class.getName());

    /**
     * Handles Bean Validation failures from @Valid @RequestBody.
     * Aggregates all field errors into a single comma-separated message
     * so the bridge knows exactly which fields failed validation.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex,
                                                                HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));

        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request.getRequestURI());
    }

    /**
     * Handles requests with missing, empty, or unparseable JSON bodies.
     * Occurs when Content-Type is application/json but the body is not valid JSON.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMalformed(HttpMessageNotReadableException ex,
                                                               HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is missing or malformed", request.getRequestURI());
    }

    /**
     * Handles bridge identity mismatch (spoofing attempt) from IngestionService.
     * A bridge authenticated as bridge-001 tried to submit a packet claiming
     * to be a different bridge — rejected with 400 before persisting to database.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex,
                                                                HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "BRIDGE_ID_MISMATCH", ex.getMessage(), request.getRequestURI());
    }

    /**
     * Handles duplicate packet submissions from the Redis idempotency claim (Day 12).
     * The packet fingerprint was already claimed within the TTL window, so the
     * submission is rejected before any database write. Redis internals are not
     * exposed — only the packetHash already known to the caller is retained.
     */
    @ExceptionHandler(DuplicatePacketException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicatePacketException ex,
                                                               HttpServletRequest request) {
        return buildResponse(HttpStatus.CONFLICT, "DUPLICATE_PACKET", ex.getMessage(), request.getRequestURI());
    }

    /**
     * Handles Day 13 freshness rejection: the packet timestamp is older than
     * meshpay.packet.freshness-window-seconds. Rejected with 400 (a stale packet
     * is a client-side problem, not a server fault) and, architecturally, before
     * HybridCryptoService is ever invoked.
     */
    @ExceptionHandler(StalePacketException.class)
    public ResponseEntity<Map<String, Object>> handleStale(StalePacketException ex,
                                                           HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "PACKET_EXPIRED", ex.getMessage(), request.getRequestURI());
    }

    /**
     * Handles Day 13 timestamp validation failure: unparseable timestamp, or a
     * timestamp further in the future than meshpay.packet.clock-skew-seconds.
     * The diagnostic reason stays in the server log only — the client receives
     * the fixed public message.
     */
    @ExceptionHandler(InvalidPacketTimestampException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidTimestamp(InvalidPacketTimestampException ex,
                                                                      HttpServletRequest request) {
        log.warning("Rejected packet with invalid timestamp: " + ex.getReason());
        return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_PACKET_TIMESTAMP", ex.getMessage(),
                request.getRequestURI());
    }

    /**
     * Handles decryption failure on an otherwise claimed + fresh packet (Day 13):
     * undecodable payload, tampered ciphertext, or a key mismatch.
     * The cryptographic exception type, its message, and any key material stay
     * server-side (logged as detail); the client only sees a generic 400.
     */
    @ExceptionHandler(InvalidEncryptedPacketException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidEncryptedPacket(InvalidEncryptedPacketException ex,
                                                                            HttpServletRequest request) {
        log.warning("Rejected undecryptable packet: " + ex.getDetail());
        return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_ENCRYPTED_PACKET", ex.getMessage(),
                request.getRequestURI());
    }

    /**
     * Catch-all handler for any unhandled exception.
     * Returns 500 without leaking stack traces or internal details to the client.
     * Server-side logging provides the full diagnostic information.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", request.getRequestURI());
    }

    /**
     * Builds the standard error response body used by all handlers.
     * Uses LinkedHashMap to preserve field ordering for consistent JSON output.
     */
    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String error, String message, String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        body.put("path", path);
        return ResponseEntity.status(status).body(body);
    }
}