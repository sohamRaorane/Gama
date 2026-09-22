package com.meshpay.ingestion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * Represents an incoming packet submission from a bridge.
 * The bridge encrypts the payment payload and forwards it here for ingestion.
 */
@Builder
public record IngestionRequest(

    @NotBlank(message = "encryptedPayload must not be blank")
    String encryptedPayload,

    @NotBlank(message = "bridgeId must not be blank")
    @Size(min = 3, max = 100, message = "bridgeId must be between 3 and 100 characters")
    String bridgeId,

    @NotBlank(message = "timestamp must not be blank")
    String timestamp,

    @Size(max = 64, message = "senderId must not exceed 64 characters")
    String senderId,

    @Size(max = 64, message = "recipientId must not exceed 64 characters")
    String recipientId,

    @NotBlank(message = "type must not be blank")
    @Pattern(regexp = "PAYMENT|HEARTBEAT|STATUS", message = "type must be one of: PAYMENT, HEARTBEAT, STATUS")
    String type
) {}
