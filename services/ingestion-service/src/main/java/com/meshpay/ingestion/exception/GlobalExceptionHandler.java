package com.meshpay.ingestion.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * - INTERNAL_ERROR (500): Any unexpected exception (catch-all)
 *
 * Note: 401 and 429 responses are NOT handled here — they are written directly by
 * JwtAuthenticationFilter, which runs before the controller layer and never lets
 * rejected requests reach this handler.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

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