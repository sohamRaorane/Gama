package com.meshpay.ingestion.service;

import com.meshpay.ingestion.dto.IngestionRequest;
import com.meshpay.ingestion.dto.IngestionResponse;
import com.meshpay.ingestion.entity.IngestedPacket;
import com.meshpay.ingestion.repository.IngestedPacketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core service that handles incoming encrypted mesh packets from bridges.
 * Creates a database record and returns an acknowledgment with a correlation ID.
 */
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final IngestedPacketRepository repository;

    /**
     * Accepts an encrypted packet from a bridge, persists it, and returns an acknowledgment.
     *
     * @param request the incoming packet submission
     * @return acknowledgment with a unique packetId for correlation
     */
    @Transactional
    public IngestionResponse ingest(IngestionRequest request) {
        IngestedPacket packet = IngestedPacket.create(
                request.encryptedPayload(),
                request.bridgeId(),
                request.senderId(),
                request.recipientId(),
                request.type()
        );

        repository.save(packet);

        return IngestionResponse.acknowledged(packet.getPacketId().toString());
    }
}
