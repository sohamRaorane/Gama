package com.meshpay.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


public record TokenRequest(
        @NotBlank(message = "credentialIdentifier must not be blank")
        String credentialIdentifier,

        @NotBlank(message = "secret must not be blank")
        String secret
) {


}
