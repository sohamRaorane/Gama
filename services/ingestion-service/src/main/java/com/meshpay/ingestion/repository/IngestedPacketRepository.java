package com.meshpay.ingestion.repository;

import com.meshpay.ingestion.entity.IngestedPacket;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IngestedPacketRepository extends JpaRepository<IngestedPacket, UUID> {

    Optional<IngestedPacket> findByPacketId(UUID packetId);
}
