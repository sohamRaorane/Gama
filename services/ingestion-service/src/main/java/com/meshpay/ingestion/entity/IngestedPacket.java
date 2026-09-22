package com.meshpay.ingestion.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Represents an encrypted mesh packet received from a bridge.
 * The packet is stored as-is and will be decrypted by downstream services.
 */
@Entity
@Table(name = "packets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class IngestedPacket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "packet_id", unique = true, nullable = false)
    private UUID packetId;

    @Column(name = "encrypted_payload", nullable = false, columnDefinition = "TEXT")
    private String encryptedPayload;

    @Column(name = "bridge_id", nullable = false)
    private String bridgeId;

    @Column(name = "sender_id")
    private String senderId;

    @Column(name = "recipient_id")
    private String recipientId;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "status", nullable = false)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Creates a new IngestedPacket from an incoming request.
     * Assigns a new UUID for the packetId and sets initial status to RECEIVED.
     */
    public static IngestedPacket create(String encryptedPayload, String bridgeId,
                                         String senderId, String recipientId, String type) {
        return new IngestedPacket(
                null,
                UUID.randomUUID(),
                encryptedPayload,
                bridgeId,
                senderId,
                recipientId,
                type,
                "RECEIVED",
                null,
                null
        );
    }
}
