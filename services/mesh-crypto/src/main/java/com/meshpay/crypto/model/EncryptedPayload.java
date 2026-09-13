package com.meshpay.crypto.model;

import java.util.Arrays;

public record EncryptedPayload(
        byte[] iv,
        byte[] ciphertext
) {
    // 1. Compact constructor to perform defensive copying on creation
    public EncryptedPayload {
        iv = iv != null ? Arrays.copyOf(iv, iv.length) : null;
        ciphertext = ciphertext != null ? Arrays.copyOf(ciphertext, ciphertext.length) : null;
    }

    // 2. Custom getters to return safe copies of the arrays
    @Override
    public byte[] iv() {
        return iv != null ? Arrays.copyOf(iv, iv.length) : null;
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext != null ? Arrays.copyOf(ciphertext, ciphertext.length) : null;
    }
}

