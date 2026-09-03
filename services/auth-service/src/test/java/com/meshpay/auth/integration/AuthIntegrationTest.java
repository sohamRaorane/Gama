package com.meshpay.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meshpay.auth.dto.RegisterBridgeRequest;
import com.meshpay.auth.dto.TokenRequest;
import com.meshpay.auth.entity.Bridge;
import com.meshpay.auth.repository.BridgeRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18")
            .withDatabaseName("auth_db")
            .withUsername("soham")
            .withPassword("soham1234");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret", () -> "integration-test-secret-key-that-is-at-least-256-bits-long-for-hs256-1234567890");
        registry.add("app.jwt.issuer", () -> "meshpay-auth");
        registry.add("app.jwt.expiration-ms", () -> "3600000");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BridgeRepository bridgeRepository;

    @Test
    void register_persistsBridge_andHashesSecret() throws Exception {
        RegisterBridgeRequest req = RegisterBridgeRequest.builder()
                .bridgeId("bridge-int-001")
                .credentialIdentifier("cred-int-001")
                .secret("supersecret123")
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/bridges/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bridgeId").value("bridge-int-001"))
                .andExpect(jsonPath("$.credentialIdentifier").value("cred-int-001"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andReturn();

        // verify persisted
        Optional<Bridge> persisted = bridgeRepository.findByBridgeId("bridge-int-001");
        assertThat(persisted).isPresent();
        Bridge bridge = persisted.get();
        assertThat(bridge.getCredentialHash()).isNotEqualTo("supersecret123");
        assertThat(bridge.getCredentialHash()).startsWith("$2a$");
        assertThat(bridge.getEnabled()).isTrue();
        assertThat(bridge.getCreatedAt()).isNotNull();
        assertThat(bridge.getUpdatedAt()).isNotNull();

        // ensure response does not contain hash or secret
        String json = result.getResponse().getContentAsString();
        assertThat(json).doesNotContain("credentialHash");
        assertThat(json).doesNotContain("supersecret123");
        assertThat(json).doesNotContain("secret");
    }

    @Test
    void register_duplicateBridgeId_returns409() throws Exception {
        RegisterBridgeRequest req = RegisterBridgeRequest.builder()
                .bridgeId("bridge-dup-001")
                .credentialIdentifier("cred-dup-001")
                .secret("supersecret123")
                .build();
        mockMvc.perform(post("/api/v1/auth/bridges/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        RegisterBridgeRequest dup = RegisterBridgeRequest.builder()
                .bridgeId("bridge-dup-001")
                .credentialIdentifier("cred-dup-002")
                .secret("anothersecret123")
                .build();
        mockMvc.perform(post("/api/v1/auth/bridges/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dup)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void register_duplicateCredentialIdentifier_returns409() throws Exception {
        RegisterBridgeRequest req = RegisterBridgeRequest.builder()
                .bridgeId("bridge-dup2-001")
                .credentialIdentifier("cred-dup2-001")
                .secret("supersecret123")
                .build();
        mockMvc.perform(post("/api/v1/auth/bridges/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        RegisterBridgeRequest dup = RegisterBridgeRequest.builder()
                .bridgeId("bridge-dup2-002")
                .credentialIdentifier("cred-dup2-001")
                .secret("anothersecret123")
                .build();
        mockMvc.perform(post("/api/v1/auth/bridges/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dup)))
                .andExpect(status().isConflict());
    }

    @Test
    void register_invalidRequest_returns400() throws Exception {
        RegisterBridgeRequest invalid = RegisterBridgeRequest.builder()
                .bridgeId("")
                .credentialIdentifier("cred-invalid")
                .secret("short")
                .build();
        mockMvc.perform(post("/api/v1/auth/bridges/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void token_success_returnsJwt() throws Exception {
        RegisterBridgeRequest reg = RegisterBridgeRequest.builder()
                .bridgeId("bridge-token-001")
                .credentialIdentifier("cred-token-001")
                .secret("validsecret123")
                .build();
        mockMvc.perform(post("/api/v1/auth/bridges/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated());

        TokenRequest tokenReq = TokenRequest.builder()
                .credentialIdentifier("cred-token-001")
                .secret("validsecret123")
                .build();
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tokenReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andReturn();

        String response = result.getResponse().getContentAsString();
        assertThat(response).contains("accessToken");
        // JWT has 3 parts
        String token = objectMapper.readTree(response).get("accessToken").asText();
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void token_invalidSecret_returns401() throws Exception {
        RegisterBridgeRequest reg = RegisterBridgeRequest.builder()
                .bridgeId("bridge-token-fail-001")
                .credentialIdentifier("cred-token-fail-001")
                .secret("validsecret123")
                .build();
        mockMvc.perform(post("/api/v1/auth/bridges/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated());

        TokenRequest bad = TokenRequest.builder()
                .credentialIdentifier("cred-token-fail-001")
                .secret("wrongsecret123")
                .build();
        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTHENTICATION_FAILED"));
    }

    @Test
    void token_unknownCredential_returns401Generic() throws Exception {
        TokenRequest req = TokenRequest.builder()
                .credentialIdentifier("nonexistent-cred")
                .secret("whatever123")
                .build();
        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    @Test
    void protectedEndpoint_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());

        // register and get token then access protected
        RegisterBridgeRequest reg = RegisterBridgeRequest.builder()
                .bridgeId("bridge-protected-001")
                .credentialIdentifier("cred-protected-001")
                .secret("validsecret123")
                .build();
        mockMvc.perform(post("/api/v1/auth/bridges/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated());

        TokenRequest tokenReq = TokenRequest.builder()
                .credentialIdentifier("cred-protected-001")
                .secret("validsecret123")
                .build();
        MvcResult tokenResult = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tokenReq)))
                .andExpect(status().isOk()).andReturn();
        String token = objectMapper.readTree(tokenResult.getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bridgeId").value("bridge-protected-001"));

        // invalid token should be unauthorized (filter will not set auth, spring returns 401/403)
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer invalid.token.here"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorHealth_isAccessible() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void disabledBridge_cannotAuthenticate() throws Exception {
        RegisterBridgeRequest reg = RegisterBridgeRequest.builder()
                .bridgeId("bridge-disabled-001")
                .credentialIdentifier("cred-disabled-001")
                .secret("validsecret123")
                .build();
        mockMvc.perform(post("/api/v1/auth/bridges/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated());

        // disable via repository
        Bridge bridge = bridgeRepository.findByBridgeId("bridge-disabled-001").orElseThrow();
        bridge.setEnabled(false);
        bridgeRepository.save(bridge);

        TokenRequest req = TokenRequest.builder()
                .credentialIdentifier("cred-disabled-001")
                .secret("validsecret123")
                .build();
        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }
}
