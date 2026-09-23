package com.meshpay.ingestion.controller;

import com.meshpay.ingestion.config.SecurityConfig;
import com.meshpay.ingestion.dto.IngestionResponse;
import com.meshpay.ingestion.rate.limiter.RateLimiter;
import com.meshpay.ingestion.security.JwtTokenProvider;
import com.meshpay.ingestion.service.IngestionService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PacketIngestionController.class,
        properties = {
                "app.jwt.secret=test-secret-that-is-at-least-32-bytes-long!",
                "app.jwt.issuer=meshpay-auth"
        })
@Import({SecurityConfig.class, JwtTokenProvider.class})
class PacketIngestionSecurityTest {

    private static final String TEST_SECRET = "test-secret-that-is-at-least-32-bytes-long!";

    private static final String VALID_BODY = """
            {
                "encryptedPayload": "base64encodeddata",
                "bridgeId": "bridge-001",
                "timestamp": "2026-09-14T12:00:00Z",
                "senderId": "user-123",
                "recipientId": "user-456",
                "type": "PAYMENT"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IngestionService ingestionService;

    @MockitoBean
    private RateLimiter rateLimiter;

    private SecretKey key;

    @BeforeEach
    void setUp() {
        key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        org.mockito.Mockito.lenient().when(rateLimiter.attemptConsume(any())).thenReturn(true);
        org.mockito.Mockito.lenient().when(ingestionService.ingest(any()))
                .thenReturn(IngestionResponse.acknowledged("test-packet-id"));
    }

    private String generateToken(String bridgeId) {
        return Jwts.builder()
                .subject(bridgeId)
                .issuer("meshpay-auth")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(key)
                .compact();
    }

    @Test
    void scenario1_requestWithoutJwt_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/packets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        verify(ingestionService, never()).ingest(any());
    }

    @Test
    void scenario2_requestWithInvalidJwt_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/packets")
                        .header("Authorization", "Bearer not-a-real-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        verify(ingestionService, never()).ingest(any());
    }

    @Test
    void scenario3_requestWithValidJwt_succeeds() throws Exception {
        String token = generateToken("bridge-001");

        mockMvc.perform(post("/api/v1/packets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RECEIVED"));

        verify(ingestionService).ingest(any());
    }

    @Test
    void scenario4_rateLimitExceeded_returns429() throws Exception {
        String token = generateToken("bridge-001");
        when(rateLimiter.attemptConsume("bridge-001")).thenReturn(false);

        mockMvc.perform(post("/api/v1/packets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("RATE_LIMIT_EXCEEDED"));

        verify(ingestionService, never()).ingest(any());
    }

    @Test
    void authRunsBeforeRateLimiting_noJwtMeansNoRateLimitConsumed() throws Exception {
        mockMvc.perform(post("/api/v1/packets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());

        verify(rateLimiter, never()).attemptConsume(any());
    }

    @Test
    void day10ValidationPreserved_validJwtButInvalidBodyReturns400() throws Exception {
        String token = generateToken("bridge-001");

        mockMvc.perform(post("/api/v1/packets")
                        .header("Authorization", "Bearer " + token)
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
}