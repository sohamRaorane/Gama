package com.meshpay.auth.dto;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


public record RegisterBridgeResponse(
        String bridgeId,
        String credentialIdentifier,
        Boolean enabled,
        Instant createdAt
) {}
