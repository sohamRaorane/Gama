package com.meshpay.ingestion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * Represents an incoming packet submission from a bridge.
 * The bridge encrypts the payment payload and forwards it here for ingestion.
 *
 * Validation is enforced by Spring's @Valid on the controller method parameter.
 * Failed validation triggers GlobalExceptionHandler → 400 VALIDATION_ERROR.
 *
 * Security note: the bridgeId field in this DTO is validated for format only
 * (length, non-blank). The actual identity verification happens in
 * IngestionService, which compares this value against the authenticated
 * bridgeId from the JWT (SecurityContext). A mismatch is rejected as spoofing.
 */
@Builder
public record IngestionRequest(

    /** Base64-encoded AES-GCM ciphertext of the payment instruction + ephemeral key. */
    @NotBlank(message = "encryptedPayload must not be blank")
    String encryptedPayload,

    /**
     * The bridge's claimed identity. MUST match the JWT sub claim —
     * validated by IngestionService against the authenticated bridgeId.
     * Format: 3-100 characters (e.g. "bridge-001").
     */
    @NotBlank(message = "bridgeId must not be blank")
    @Size(min = 3, max = 100, message = "bridgeId must be between 3 and 100 characters")
    String bridgeId,

    /**
     * Bridge-reported submission time in ISO 8601 format (e.g. "2026-09-14T12:00:00Z").
     * Stored in the packets table for Phase 3 freshness/replay validation.
     * This is NOT a server timestamp — it's what the bridge claims as send time.
     */
    @NotBlank(message = "timestamp must not be blank")
    String timestamp,

    /** Optional sender user ID (max 64 chars). Used for PAYMENT packets. */
    @Size(max = 64, message = "senderId must not exceed 64 characters")
    String senderId,

    /** Optional recipient user ID (max 64 chars). Used for PAYMENT packets. */
    @Size(max = 64, message = "recipientId must not exceed 64 characters")
    String recipientId,

    /** Packet type: PAYMENT (money transfer), HEARTBEAT (liveness), STATUS (device state). */
    @NotBlank(message = "type must not be blank")
    @Pattern(regexp = "PAYMENT|HEARTBEAT|STATUS", message = "type must be one of: PAYMENT, HEARTBEAT, STATUS")
    String type
) {}