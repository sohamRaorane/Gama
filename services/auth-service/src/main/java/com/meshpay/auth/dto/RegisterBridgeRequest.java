package com.meshpay.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
public class RegisterBridgeRequest {

    @NotBlank(message = "bridgeId must not be blank")
    @Size(min = 3, max = 100, message = "bridgeId must be between 3 and 100 characters")
    private String bridgeId;

    @NotBlank(message = "credentialIdentifier must not be blank")
    @Size(min = 3, max = 100, message = "credentialIdentifier must be between 3 and 100 characters")
    private String credentialIdentifier;

    @NotBlank(message = "secret must not be blank")
    @Size(min = 8, max = 100, message = "secret must be between 8 and 100 characters")
    private String secret;
}
