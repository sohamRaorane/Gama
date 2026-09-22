package com.meshpay.ingestion.dto;

/**
 * Lifecycle states of an ingested packet.
 * Starts as RECEIVED once the bridge submits it, then transitions
 * through PROCESSING as downstream services consume the event.
 */
public enum PacketStatus {
    RECEIVED,
    PROCESSING,
    COMPLETED,
    FAILED
}
