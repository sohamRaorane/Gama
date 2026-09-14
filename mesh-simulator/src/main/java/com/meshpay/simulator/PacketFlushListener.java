package com.meshpay.simulator;

import com.meshpay.crypto.model.MeshPacket;

/**
 * Callback interface used by internet-connected bridge devices to flush
 * stored encrypted MeshPackets to the backend.
 *
 * This is an abstraction boundary — the simulator itself does not implement
 * HTTP, HTTPS, or any real networking. The bridge calls this listener after
 * storing a packet, and the implementation decides what happens next
 * (e.g., send over HTTPS, add to a queue, collect in a list for testing).
 *
 * The MeshPacket passed to this callback is the exact encrypted packet
 * that was received by the bridge. It must not be modified, decrypted,
 * or re-encrypted before being passed along.
 */
@FunctionalInterface
public interface PacketFlushListener {
    void onPacketFlushed(MeshPacket packet);
}
