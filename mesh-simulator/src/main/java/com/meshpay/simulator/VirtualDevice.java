package com.meshpay.simulator;

import com.meshpay.crypto.model.MeshPacket;

import java.util.*;

/**
 * Represents a single device in the simulated mesh network.
 *
 * Each VirtualDevice maintains its own local packet store and a list of neighboring devices.
 * When a packet is received, the device checks for duplicates, stores the packet,
 * and if TTL allows, relays it to all neighbors.
 *
 * Deduplication is LOCAL to each device — the same packetId can exist on multiple
 * devices, but never twice on the same device.
 */
public class VirtualDevice {

    // Unique identifier for this simulated device
    private final String deviceId;

    // Whether this device has internet connectivity.
    // Not used in Day 8 — exists for future online/offline device distinction.
    private final boolean hasInternet;

    // Local packet store keyed by packetId for O(1) duplicate detection.
    // A packet with the same packetId is never stored twice on the same device.
    private final Map<UUID, MeshPacket> packetStore = new LinkedHashMap<>();

    // Direct neighbors in the mesh topology.
    // Packets are relayed to all neighbors during the relay step.
    private final List<VirtualDevice> neighbors = new ArrayList<>();

    /**
     * Creates a new simulated device.
     *
     * @param deviceId    unique string identifier for this device
     * @param hasInternet whether the device has internet access (reserved for future use)
     */
    public VirtualDevice(String deviceId, boolean hasInternet) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId must not be null or blank");
        }
        this.deviceId = deviceId;
        this.hasInternet = hasInternet;
    }

    /**
     * Receives a MeshPacket and processes it through the relay pipeline.
     *
     * The flow is:
     * 1. Check if this packetId already exists in the local store (deduplication)
     * 2. If duplicate → drop immediately, do not relay
     * 3. Store the packet locally
     * 4. If TTL <= 0 → stop, do not relay further
     * 5. Create a new packet with TTL decremented by one
     * 6. Relay the decremented packet to all neighbors
     *
     * @param packet the MeshPacket to receive
     */
    public void receive(MeshPacket packet) {
        if (packet == null) {
            throw new IllegalArgumentException("packet must not be null");
        }

        UUID packetId = packet.packetId();

        // Step 1: Local deduplication — check if we already have this packet.
        // If the packetId is already in our store, this is a duplicate and we drop it.
        if (packetStore.containsKey(packetId)) {
            return;
        }

        // Step 2: Store the packet locally.
        // The device "owns" this packet even if its TTL is about to expire.
        packetStore.put(packetId, packet);

        // Step 3: TTL check — if TTL is zero or below, stop relaying.
        // The packet is stored but not forwarded to neighbors.
        if (packet.ttl() <= 0) {
            return;
        }

        // Step 4: Decrement TTL and relay to all neighbors.
        // MeshPacket is immutable (Java record), so we create a new instance
        // with TTL reduced by one. The packetId remains identical across relay hops.
        MeshPacket relayPacket = packet.withDecrementedTtl();

        for (VirtualDevice neighbor : neighbors) {
            neighbor.receive(relayPacket);
        }
    }

    /**
     * Adds a bidirectional neighbor connection.
     * Both devices will relay packets to each other.
     *
     * @param neighbor the device to connect to
     */
    public void addNeighbor(VirtualDevice neighbor) {
        if (neighbor == null) {
            throw new IllegalArgumentException("neighbor must not be null");
        }
        if (neighbor == this) {
            throw new IllegalArgumentException("a device cannot be its own neighbor");
        }
        if (!neighbors.contains(neighbor)) {
            neighbors.add(neighbor);
        }
    }

    /**
     * Returns an unmodifiable view of the local packet store.
     * Allows inspection without exposing internal mutability.
     */
    public Map<UUID, MeshPacket> getPacketStore() {
        return Collections.unmodifiableMap(packetStore);
    }

    /**
     * Returns an unmodifiable view of the neighbor list.
     */
    public List<VirtualDevice> getNeighbors() {
        return Collections.unmodifiableList(neighbors);
    }

    /**
     * Checks whether this device has stored a packet with the given ID.
     */
    public boolean hasPacket(UUID packetId) {
        return packetStore.containsKey(packetId);
    }

    /**
     * Retrieves the stored packet for the given ID, or null if not found.
     */
    public MeshPacket getPacket(UUID packetId) {
        return packetStore.get(packetId);
    }

    public String getDeviceId() {
        return deviceId;
    }

    public boolean hasInternet() {
        return hasInternet;
    }
}
