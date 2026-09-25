package com.meshpay.ingestion.controller;

import com.meshpay.ingestion.dto.IngestionRequest;
import com.meshpay.ingestion.dto.IngestionResponse;
import com.meshpay.ingestion.service.IngestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller that accepts encrypted mesh packets from bridges.
 * Returns 202 Accepted with a correlation ID for async processing.
 *
 * The authenticated bridgeId (from JWT) is extracted from the SecurityContext
 * and passed to the service layer, which validates it against the request body.
 * This prevents bridge identity spoofing — a bridge authenticated as bridge-001
 * cannot submit a packet claiming to be bridge-999.
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
     * @param request validated packet submission (bridgeId in body must match JWT sub claim)
     * @return 202 Accepted with packetId and status, or 400 on bridgeId mismatch
     */
    @PostMapping
    public ResponseEntity<IngestionResponse> ingest(@Valid @RequestBody IngestionRequest request) {
        // Extract the authenticated bridgeId from the JWT (set by JwtAuthenticationFilter).
        // This is the identity the bridge proved via its Bearer token.
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String authenticatedBridgeId = (authentication != null) ? authentication.getPrincipal().toString() : null;

        // Pass the authenticated identity to the service so it can validate
        // against the body's bridgeId and reject spoofed requests.
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ingestionService.ingest(request, authenticatedBridgeId));
    }
}
