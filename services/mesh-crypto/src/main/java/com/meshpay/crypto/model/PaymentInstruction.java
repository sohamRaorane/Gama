package com.meshpay.crypto.model;

import java.time.Instant;

public record PaymentInstruction(
        String senderId,
        String receiverId,
        long amountMinor,
        String nonce,
        Instant signedAt
) {}
