package com.meshpay.crypto.model;

import java.time.Instant;
import java.util.Objects;

public record PaymentInstruction(
        String senderId,
        String receiverId,
        long amountMinor,
        String nonce,
        Instant signedAt
) {
    // Compact constructor for business logic validation
    public PaymentInstruction {
        Objects.requireNonNull(senderId, "senderId cannot be null");
        Objects.requireNonNull(receiverId, "receiverId cannot be null");
        Objects.requireNonNull(nonce, "nonce cannot be null");
        Objects.requireNonNull(signedAt, "signedAt cannot be null");

        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero");
        }
    }
}
