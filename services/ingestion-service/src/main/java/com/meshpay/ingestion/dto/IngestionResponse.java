package com.meshpay.ingestion.dto;

import java.time.Instant;

/**
 * Response returned to the bridge after a packet is accepted for ingestion.
 * The bridge uses the packetId for correlation and retry logic.
 */
public record IngestionResponse(
        String packetId,
        String status,
        Instant receivedAt,
        String message
) {

    /**
     * Creates an acknowledgment response for a successfully accepted packet.
     */
    public static IngestionResponse acknowledged(String packetId) {
        return new IngestionResponse(
                packetId,
                "RECEIVED",
                Instant.now(),
                "Packet accepted for processing"
        );
    }
}
