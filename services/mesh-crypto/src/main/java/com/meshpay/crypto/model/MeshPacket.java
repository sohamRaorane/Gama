package com.meshpay.crypto.model;

import java.time.Instant;
import java.util.UUID;

public record MeshPacket(
        UUID packetId,
        byte[] wrappedAesKey,
        byte[] iv,
        byte[] ciphertext,
        int ttl,
        Instant createdAt
) {}
