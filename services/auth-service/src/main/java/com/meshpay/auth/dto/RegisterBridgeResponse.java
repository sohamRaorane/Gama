package com.meshpay.auth.dto;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterBridgeResponse {

    private String bridgeId;
    private String credentialIdentifier;
    private Boolean enabled;
    private Instant createdAt;
}
