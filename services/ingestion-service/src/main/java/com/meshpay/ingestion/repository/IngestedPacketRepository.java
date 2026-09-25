package com.meshpay.ingestion.repository;

import com.meshpay.ingestion.entity.IngestedPacket;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the IngestedPacket entity.
 * Provides standard CRUD operations plus a lookup by the externally-visible
 * packetId (the one returned to bridges in IngestionResponse for correlation).
 */
@Repository
public interface IngestedPacketRepository extends JpaRepository<IngestedPacket, UUID> {

    /**
     * Finds a packet by its correlation ID (packetId column, not the primary key).
     * Used by downstream services and bridges for status lookup and retry logic.
     */
    Optional<IngestedPacket> findByPacketId(UUID packetId);
}