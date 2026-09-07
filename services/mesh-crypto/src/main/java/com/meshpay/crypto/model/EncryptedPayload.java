package com.meshpay.crypto.model;

public record EncryptedPayload(
        byte[] iv,
        byte[] ciphertext
) {}
