package com.meshpay.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder
public record TokenRequest(
        @NotBlank(message = "credentialIdentifier must not be blank")
        String credentialIdentifier,

        @NotBlank(message = "secret must not be blank")
        String secret
) {


}
