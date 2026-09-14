package com.meshpay.simulator;

import com.meshpay.crypto.HybridCryptoService;
import com.meshpay.crypto.RsaOaepCipher;
import com.meshpay.crypto.model.MeshPacket;
import com.meshpay.crypto.model.PaymentInstruction;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test proving a payment can cross the entire simulated mesh
 * as ciphertext and be decrypted successfully by the backend.
 *
 * Topology: sender → relayA → relayB → bridge (hasInternet=true) → backend
 *
 * The packet remains encrypted at every hop. Only the backend decrypts.
 */
class BridgeFlushTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-09-07T12:00:00Z");

    @Test
    void senderToBridgeTraversalWithDecryption() throws Exception {
        // ---------------------------------------------------------------
        // Step 1: Generate backend RSA key pair.
        // The public key is used for encryption, the private key for decryption.
        // No mesh device receives the private key.
        // ---------------------------------------------------------------
        KeyPair backendKeyPair = RsaOaepCipher.generateKeyPair();

        // ---------------------------------------------------------------
        // Step 2: Create the original PaymentInstruction.
        // ---------------------------------------------------------------
        PaymentInstruction original = new PaymentInstruction(
                "bridge-sender-001",
                "bridge-receiver-001",
                12500L,
                "nonce-phase2-001",
                FIXED_TIME
        );

        // ---------------------------------------------------------------
        // Step 3: Encrypt the PaymentInstruction BEFORE it enters the mesh.
        // The mesh receives only the encrypted MeshPacket.
        // ---------------------------------------------------------------
        MeshPacket encryptedPacket = HybridCryptoService.encrypt(
                original, backendKeyPair.getPublic());

        // ---------------------------------------------------------------
        // Step 4: Build the mesh topology.
        //
        //   sender (offline) ↔ relayA (offline) ↔ relayB (offline) ↔ bridge (online)
        //
        // The bridge has internet and will flush packets to the backend.
        // ---------------------------------------------------------------
        VirtualDevice sender  = new VirtualDevice("sender", false);
        VirtualDevice relayA  = new VirtualDevice("relayA", false);
        VirtualDevice relayB  = new VirtualDevice("relayB", false);
        VirtualDevice bridge  = new VirtualDevice("bridge", true);

        sender.addNeighbor(relayA);
        relayA.addNeighbor(sender);
        relayA.addNeighbor(relayB);
        relayB.addNeighbor(relayA);
        relayB.addNeighbor(bridge);
        bridge.addNeighbor(relayB);

        // ---------------------------------------------------------------
        // Step 5: Set up the backend flush simulation.
        // The bridge calls this listener after storing a packet.
        // This lambda represents: bridge → HTTPS → backend
        // ---------------------------------------------------------------
        List<MeshPacket> flushedPackets = new ArrayList<>();
        bridge.setFlushListener(flushedPackets::add);

        // ---------------------------------------------------------------
        // Step 6: Inject the encrypted packet into the sender with TTL=3.
        //
        //   sender stores TTL=3 → relays TTL=2
        //   relayA stores TTL=2 → relays TTL=1
        //   relayB stores TTL=1 → relays TTL=0
        //   bridge stores TTL=0 → flushes → does NOT relay
        // ---------------------------------------------------------------
        sender.receive(encryptedPacket);

        // ---------------------------------------------------------------
        // ASSERTION 1: All four devices stored the packet.
        // ---------------------------------------------------------------
        assertTrue(sender.hasPacket(encryptedPacket.packetId()),
                "sender must store the packet");
        assertTrue(relayA.hasPacket(encryptedPacket.packetId()),
                "relayA must store the packet");
        assertTrue(relayB.hasPacket(encryptedPacket.packetId()),
                "relayB must store the packet");
        assertTrue(bridge.hasPacket(encryptedPacket.packetId()),
                "bridge must store the packet");

        // ---------------------------------------------------------------
        // ASSERTION 2: Bridge flushed exactly once.
        // ---------------------------------------------------------------
        assertEquals(1, flushedPackets.size(),
                "bridge must flush exactly one packet to the backend");

        // ---------------------------------------------------------------
        // ASSERTION 3: Packet remained ciphertext at every mesh hop.
        // At every device, the stored packet must still contain all
        // encrypted fields — proving no device decrypted it.
        // ---------------------------------------------------------------
        MeshPacket storedBySender = sender.getPacket(encryptedPacket.packetId());
        MeshPacket storedByRelayA = relayA.getPacket(encryptedPacket.packetId());
        MeshPacket storedByRelayB = relayB.getPacket(encryptedPacket.packetId());
        MeshPacket storedByBridge = bridge.getPacket(encryptedPacket.packetId());

        assertPacketIsEncrypted(storedBySender, "sender");
        assertPacketIsEncrypted(storedByRelayA, "relayA");
        assertPacketIsEncrypted(storedByRelayB, "relayB");
        assertPacketIsEncrypted(storedByBridge, "bridge");

        // ---------------------------------------------------------------
        // ASSERTION 4: Flushed packet is still ciphertext.
        // The bridge forwarded the exact encrypted MeshPacket to the backend.
        // ---------------------------------------------------------------
        MeshPacket flushedPacket = flushedPackets.get(0);
        assertPacketIsEncrypted(flushedPacket, "flushed to backend");

        // ---------------------------------------------------------------
        // ASSERTION 5: Backend decryption succeeds.
        // The backend uses its RSA private key to decrypt the flushed packet.
        // The resulting PaymentInstruction must equal the original.
        // ---------------------------------------------------------------
        PaymentInstruction decrypted = HybridCryptoService.decrypt(
                flushedPacket, backendKeyPair.getPrivate());

        assertEquals(original, decrypted,
                "backend must recover the original PaymentInstruction after decryption");
    }

    /**
     * Verifies that a MeshPacket still contains all encrypted fields.
     * This proves the packet was never decrypted during mesh transit.
     */
    private void assertPacketIsEncrypted(MeshPacket packet, String location) {
        assertNotNull(packet.wrappedAesKey(),
                location + " must have wrappedAesKey (packet is still encrypted)");
        assertNotNull(packet.iv(),
                location + " must have IV (packet is still encrypted)");
        assertNotNull(packet.ciphertext(),
                location + " must have ciphertext (packet is still encrypted)");
    }
}
