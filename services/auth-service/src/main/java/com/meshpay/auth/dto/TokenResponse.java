package com.meshpay.auth.dto;

public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {

    public static TokenResponse of(String token, long expiresInSeconds) {
        return new TokenResponse(
                token,
                "Bearer",
                expiresInSeconds
        );
    }
}