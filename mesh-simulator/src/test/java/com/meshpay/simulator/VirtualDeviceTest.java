package com.meshpay.simulator;

import com.meshpay.crypto.model.MeshPacket;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VirtualDeviceTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-09-07T12:00:00Z");

    /**
     * Creates a test MeshPacket with a known packetId and the given TTL.
     * The crypto fields (wrappedAesKey, iv, ciphertext) are dummy values
     * because the simulator treats MeshPacket as an opaque transport object.
     */
    private MeshPacket createPacket(UUID packetId, int ttl) {
        return new MeshPacket(
                packetId,
                new byte[]{0x01, 0x02},   // dummy wrappedAesKey
                new byte[]{0x0A},         // dummy IV
                new byte[]{0x0B, 0x0C},   // dummy ciphertext
                ttl,
                FIXED_TIME
        );
    }

    // ---------------------------------------------------------------
    // Test 1: Multi-hop relay  A → B → C
    // ---------------------------------------------------------------
    @Test
    void multiHopRelay() {
        // Create three offline devices
        VirtualDevice a = new VirtualDevice("device-A", false);
        VirtualDevice b = new VirtualDevice("device-B", false);
        VirtualDevice c = new VirtualDevice("device-C", false);

        // Connect the linear topology: A ↔ B ↔ C
        a.addNeighbor(b);
        b.addNeighbor(a);
        b.addNeighbor(c);
        c.addNeighbor(b);

        // Inject a packet into A with TTL = 2 (enough for two relay hops)
        UUID packetId = UUID.randomUUID();
        MeshPacket packet = createPacket(packetId, 2);
        a.receive(packet);

        // All three devices must have stored the packet
        assertTrue(a.hasPacket(packetId), "A must store the packet");
        assertTrue(b.hasPacket(packetId), "B must store the packet (relayed from A)");
        assertTrue(c.hasPacket(packetId), "C must store the packet (relayed from B)");

        // Verify TTL was decremented at each hop
        assertEquals(2, a.getPacket(packetId).ttl(), "A stores packet with original TTL=2");
        assertEquals(1, b.getPacket(packetId).ttl(), "B stores packet with TTL=1 (decremented once)");
        assertEquals(0, c.getPacket(packetId).ttl(), "C stores packet with TTL=0 (decremented twice)");
    }

    // ---------------------------------------------------------------
    // Test 2: Duplicate packets are dropped locally
    // ---------------------------------------------------------------
    @Test
    void duplicatePacketsDropped() {
        VirtualDevice a = new VirtualDevice("device-A", false);
        VirtualDevice b = new VirtualDevice("device-B", false);

        a.addNeighbor(b);
        b.addNeighbor(a);

        UUID packetId = UUID.randomUUID();
        MeshPacket packet = createPacket(packetId, 3);

        // Send the packet to B via A's relay
        a.receive(packet);

        // B should have exactly one copy of the packet
        assertEquals(1, b.getPacketStore().size(),
                "B must store exactly one copy after first relay");

        // Now send the same logical packet to B directly (simulating a duplicate from another path)
        MeshPacket duplicate = createPacket(packetId, 3);
        b.receive(duplicate);

        // B still has only one packet — the duplicate was dropped
        assertEquals(1, b.getPacketStore().size(),
                "B must not store a second copy of the same packetId");
    }

    // ---------------------------------------------------------------
    // Test 3: TTL expiration stops propagation
    // ---------------------------------------------------------------
    @Test
    void ttlExpirationStopsPropagation() {
        VirtualDevice a = new VirtualDevice("device-A", false);
        VirtualDevice b = new VirtualDevice("device-B", false);
        VirtualDevice c = new VirtualDevice("device-C", false);

        a.addNeighbor(b);
        b.addNeighbor(a);
        b.addNeighbor(c);
        c.addNeighbor(b);

        // TTL = 1: A relays to B with TTL=0, B stores but does NOT relay to C
        UUID packetId = UUID.randomUUID();
        MeshPacket packet = createPacket(packetId, 1);
        a.receive(packet);

        assertTrue(a.hasPacket(packetId), "A must store the packet");
        assertTrue(b.hasPacket(packetId), "B must store the packet (TTL=0 after relay)");
        assertFalse(c.hasPacket(packetId),
                "C must NOT receive the packet because B's relay TTL was 0");
    }

    // ---------------------------------------------------------------
    // Test 4: Bidirectional relay loop terminates via deduplication
    // ---------------------------------------------------------------
    @Test
    void relayLoopTerminates() {
        // Two devices connected bidirectionally.
        // When A sends to B, B tries to relay back to A,
        // but A already has the packet and drops it.
        VirtualDevice a = new VirtualDevice("device-A", false);
        VirtualDevice b = new VirtualDevice("device-B", false);

        a.addNeighbor(b);
        b.addNeighbor(a);

        UUID packetId = UUID.randomUUID();
        MeshPacket packet = createPacket(packetId, 5);

        // This must complete without stack overflow or infinite loop
        assertDoesNotThrow(() -> a.receive(packet),
                "Relay loop must terminate through local deduplication");

        // Both devices have exactly one copy
        assertEquals(1, a.getPacketStore().size(), "A has one packet");
        assertEquals(1, b.getPacketStore().size(), "B has one packet");
    }
}
