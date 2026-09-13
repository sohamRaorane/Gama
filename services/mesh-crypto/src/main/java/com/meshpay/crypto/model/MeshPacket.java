package com.meshpay.crypto.model;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

public record MeshPacket(
        UUID packetId,
        byte[] wrappedAesKey,
        byte[] iv,
        byte[] ciphertext,
        int ttl,
        Instant createdAt
) {
    public MeshPacket {
        wrappedAesKey = wrappedAesKey != null ? Arrays.copyOf(wrappedAesKey, wrappedAesKey.length) : null;
        iv = iv != null ? Arrays.copyOf(iv, iv.length) : null;
        ciphertext = ciphertext != null ? ciphertext.clone() : null;
    }

    @Override
    public byte[] wrappedAesKey() {
        return wrappedAesKey != null ? Arrays.copyOf(wrappedAesKey, wrappedAesKey.length) : null;
    }

    @Override
    public byte[] iv() {
        return iv != null ? Arrays.copyOf(iv, iv.length) : null;
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext != null ? ciphertext.clone() : null;
    }

    public boolean isExpired() {
        if (createdAt == null) return true;
        return Instant.now().isAfter(createdAt.plusSeconds(ttl));
    }
}
