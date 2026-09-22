package com.meshpay.ingestion.controller;

import com.meshpay.ingestion.dto.IngestionRequest;
import com.meshpay.ingestion.dto.IngestionResponse;
import com.meshpay.ingestion.service.IngestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller that accepts encrypted mesh packets from bridges.
 * Returns 202 Accepted with a correlation ID for async processing.
 */
@RestController
@RequestMapping("/api/v1/packets")
@RequiredArgsConstructor
public class PacketIngestionController {

    private final IngestionService ingestionService;

    /**
     * Accepts an encrypted mesh packet for ingestion.
     * The bridge submits the packet here after encrypting it with HybridCryptoService.
     *
     * @param request validated packet submission
     * @return 202 Accepted with packetId and status
     */
    @PostMapping
    public ResponseEntity<IngestionResponse> ingest(@Valid @RequestBody IngestionRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ingestionService.ingest(request));
    }
}
