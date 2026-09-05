package com.meshpay.auth.dto;

import java.time.Instant;

public record RegisterBridgeResponse(
        String bridgeId,
        String credentialIdentifier,
        Boolean enabled,
        Instant createdAt
) {}
