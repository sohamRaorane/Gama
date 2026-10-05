package com.meshpay.ingestion.controller;

import com.meshpay.ingestion.dto.IngestionResponse;
import com.meshpay.ingestion.exception.InvalidEncryptedPacketException;
import com.meshpay.ingestion.exception.InvalidPacketTimestampException;
import com.meshpay.ingestion.exception.StalePacketException;
import com.meshpay.ingestion.service.IngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PacketIngestionController.class)
@AutoConfigureMockMvc(addFilters = false)
class PacketIngestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IngestionService ingestionService;

    @Test
    void shouldAcceptValidPacketAndReturn202() throws Exception {
        IngestionResponse response = IngestionResponse.acknowledged("550e8400-e29b-41d4-a716-446655440000");
        when(ingestionService.ingest(any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/packets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "encryptedPayload": "base64encodeddata",
                                    "bridgeId": "bridge-001",
                                    "timestamp": "2026-09-14T12:00:00Z",
                                    "senderId": "user-123",
                                    "recipientId": "user-456",
                                    "type": "PAYMENT"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.packetId").exists())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.receivedAt").exists())
                .andExpect(jsonPath("$.message").value("Packet accepted for processing"));
    }

    @Test
    void shouldReturn400WhenEncryptedPayloadIsBlank() throws Exception {
        mockMvc.perform(post("/api/v1/packets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "encryptedPayload": "",
                                    "bridgeId": "bridge-001",
                                    "timestamp": "2026-09-14T12:00:00Z",
                                    "type": "PAYMENT"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturn400WhenBridgeIdIsBlank() throws Exception {
        mockMvc.perform(post("/api/v1/packets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "encryptedPayload": "base64encodeddata",
                                    "bridgeId": "",
                                    "timestamp": "2026-09-14T12:00:00Z",
                                    "type": "PAYMENT"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturn400WhenTypeIsInvalid() throws Exception {
        mockMvc.perform(post("/api/v1/packets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "encryptedPayload": "base64encodeddata",
                                    "bridgeId": "bridge-001",
                                    "timestamp": "2026-09-14T12:00:00Z",
                                    "type": "INVALID"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturn400WhenBodyIsEmpty() throws Exception {
        mockMvc.perform(post("/api/v1/packets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest());
    }

    // --- Day 13 error mapping --------------------------------------------------------

    private static final String VALID_BODY = """
            {
                "encryptedPayload": "base64encodeddata",
                "bridgeId": "bridge-001",
                "timestamp": "2026-10-05T11:59:50Z",
                "senderId": "user-123",
                "recipientId": "user-456",
                "type": "PAYMENT"
            }
            """;

    @Test
    void shouldReturn400PacketExpiredForStalePacket() throws Exception {
        when(ingestionService.ingest(any(), any())).thenThrow(new StalePacketException());

        mockMvc.perform(post("/api/v1/packets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("PACKET_EXPIRED"))
                .andExpect(jsonPath("$.message").value("Packet has expired"));
    }

    @Test
    void shouldReturn400InvalidPacketTimestampForUnparseableTimestamp() throws Exception {
        when(ingestionService.ingest(any(), any()))
                .thenThrow(new InvalidPacketTimestampException("timestamp is not a valid ISO-8601 instant"));

        mockMvc.perform(post("/api/v1/packets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PACKET_TIMESTAMP"))
                .andExpect(jsonPath("$.message").value("Packet timestamp is invalid"));
    }

    @Test
    void shouldReturn400InvalidEncryptedPacketWithoutLeakingCryptoDetails() throws Exception {
        when(ingestionService.ingest(any(), any()))
                .thenThrow(new InvalidEncryptedPacketException("HybridCryptoService.decrypt failed: AEADBadTagException",
                        new javax.crypto.AEADBadTagException("Tag mismatch!")));

        mockMvc.perform(post("/api/v1/packets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ENCRYPTED_PACKET"))
                .andExpect(jsonPath("$.message").value("Encrypted packet could not be decrypted"))
                // No cryptographic internals may leak to the client.
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("AEAD"))))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("RSA"))));
    }
}
