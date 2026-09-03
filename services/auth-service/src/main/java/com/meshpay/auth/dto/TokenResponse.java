package com.meshpay.auth.dto;

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
public class TokenResponse {

    private String accessToken;
    private String tokenType;
    private long expiresIn;

    public static TokenResponse of(String token, long expiresInSeconds) {
        return TokenResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresIn(expiresInSeconds)
                .build();
    }
}
