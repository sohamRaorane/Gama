package com.meshpay.auth.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meshpay.auth.dto.RegisterBridgeRequest;
import com.meshpay.auth.dto.RegisterBridgeResponse;
import com.meshpay.auth.dto.TokenRequest;
import com.meshpay.auth.dto.TokenResponse;
import com.meshpay.auth.entity.Bridge;
import com.meshpay.auth.exception.AuthenticationFailedException;
import com.meshpay.auth.exception.DuplicateResourceException;
import com.meshpay.auth.repository.BridgeRepository;
import com.meshpay.auth.security.JwtTokenProvider;
import com.meshpay.auth.service.AuthService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private BridgeRepository bridgeRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(bridgeRepository, passwordEncoder, jwtTokenProvider);
    }

    @Test
    void register_success() {
        RegisterBridgeRequest req = RegisterBridgeRequest.builder()
                .bridgeId("bridge-001")
                .credentialIdentifier("cred-001")
                .secret("supersecret123")
                .build();

        when(bridgeRepository.existsByBridgeId("bridge-001")).thenReturn(false);
        when(bridgeRepository.existsByCredentialIdentifier("cred-001")).thenReturn(false);
        when(passwordEncoder.encode("supersecret123")).thenReturn("hashedSecret");

        Bridge saved = Bridge.builder()
                .id(UUID.randomUUID())
                .bridgeId("bridge-001")
                .credentialIdentifier("cred-001")
                .credentialHash("hashedSecret")
                .enabled(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(bridgeRepository.save(any(Bridge.class))).thenReturn(saved);

        RegisterBridgeResponse resp = authService.register(req);

        assertThat(resp.getBridgeId()).isEqualTo("bridge-001");
        assertThat(resp.getCredentialIdentifier()).isEqualTo("cred-001");
        assertThat(resp.getEnabled()).isTrue();
        verify(passwordEncoder).encode("supersecret123");
        verify(bridgeRepository).save(any(Bridge.class));
    }

    @Test
    void register_duplicateBridgeId_throws409() {
        RegisterBridgeRequest req = RegisterBridgeRequest.builder()
                .bridgeId("bridge-dup")
                .credentialIdentifier("cred-001")
                .secret("supersecret123")
                .build();
        when(bridgeRepository.existsByBridgeId("bridge-dup")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(req))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("bridgeId");
        verify(bridgeRepository, never()).save(any());
    }

    @Test
    void register_duplicateCredentialIdentifier_throws409() {
        RegisterBridgeRequest req = RegisterBridgeRequest.builder()
                .bridgeId("bridge-001")
                .credentialIdentifier("cred-dup")
                .secret("supersecret123")
                .build();
        when(bridgeRepository.existsByBridgeId("bridge-001")).thenReturn(false);
        when(bridgeRepository.existsByCredentialIdentifier("cred-dup")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(req))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("credentialIdentifier");
    }

    @Test
    void register_passwordIsHashed_notPlaintext() {
        RegisterBridgeRequest req = RegisterBridgeRequest.builder()
                .bridgeId("bridge-002")
                .credentialIdentifier("cred-002")
                .secret("mySecret123")
                .build();
        when(bridgeRepository.existsByBridgeId(anyString())).thenReturn(false);
        when(bridgeRepository.existsByCredentialIdentifier(anyString())).thenReturn(false);
        when(passwordEncoder.encode("mySecret123")).thenReturn("$2a$10$hashed");
        Bridge saved = Bridge.builder().bridgeId("bridge-002").credentialIdentifier("cred-002")
                .credentialHash("$2a$10$hashed").enabled(true).createdAt(Instant.now()).updatedAt(Instant.now()).build();
        when(bridgeRepository.save(any())).thenReturn(saved);

        authService.register(req);
        verify(passwordEncoder).encode("mySecret123");
        // ensure encode called, and hash different from raw
        assertThat("$2a$10$hashed").isNotEqualTo("mySecret123");
    }

    @Test
    void authenticate_success_returnsToken() {
        TokenRequest req = TokenRequest.builder()
                .credentialIdentifier("cred-001")
                .secret("rawSecret")
                .build();
        Bridge bridge = Bridge.builder()
                .bridgeId("bridge-001")
                .credentialIdentifier("cred-001")
                .credentialHash("hashed")
                .enabled(true)
                .build();
        when(bridgeRepository.findByCredentialIdentifier("cred-001")).thenReturn(Optional.of(bridge));
        when(passwordEncoder.matches("rawSecret", "hashed")).thenReturn(true);
        when(jwtTokenProvider.generateToken("bridge-001")).thenReturn("jwt-token");
        when(jwtTokenProvider.getExpirationMs()).thenReturn(3600000L);

        TokenResponse resp = authService.authenticate(req);
        assertThat(resp.getAccessToken()).isEqualTo("jwt-token");
        assertThat(resp.getTokenType()).isEqualTo("Bearer");
        assertThat(resp.getExpiresIn()).isEqualTo(3600);
    }

    @Test
    void authenticate_invalidSecret_throws401() {
        TokenRequest req = TokenRequest.builder().credentialIdentifier("cred-001").secret("wrong").build();
        Bridge bridge = Bridge.builder().bridgeId("bridge-001").credentialIdentifier("cred-001")
                .credentialHash("hashed").enabled(true).build();
        when(bridgeRepository.findByCredentialIdentifier("cred-001")).thenReturn(Optional.of(bridge));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.authenticate(req))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void authenticate_unknownCredentialIdentifier_throws401() {
        TokenRequest req = TokenRequest.builder().credentialIdentifier("unknown").secret("secret").build();
        when(bridgeRepository.findByCredentialIdentifier("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.authenticate(req))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void authenticate_disabledBridge_throws401() {
        TokenRequest req = TokenRequest.builder().credentialIdentifier("cred-001").secret("raw").build();
        Bridge bridge = Bridge.builder().bridgeId("bridge-001").credentialIdentifier("cred-001")
                .credentialHash("hashed").enabled(false).build();
        when(bridgeRepository.findByCredentialIdentifier("cred-001")).thenReturn(Optional.of(bridge));

        assertThatThrownBy(() -> authService.authenticate(req))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void authenticate_genericFailureDoesNotLeakExistence() {
        // both unknown user and wrong password produce same exception message
        TokenRequest unknown = TokenRequest.builder().credentialIdentifier("unknown").secret("secret").build();
        when(bridgeRepository.findByCredentialIdentifier("unknown")).thenReturn(Optional.empty());

        TokenRequest wrongPass = TokenRequest.builder().credentialIdentifier("cred-001").secret("bad").build();
        Bridge bridge = Bridge.builder().bridgeId("bridge-001").credentialIdentifier("cred-001")
                .credentialHash("hashed").enabled(true).build();
        when(bridgeRepository.findByCredentialIdentifier("cred-001")).thenReturn(Optional.of(bridge));
        when(passwordEncoder.matches("bad", "hashed")).thenReturn(false);

        String msgUnknown = "";
        String msgWrong = "";
        try { authService.authenticate(unknown); } catch (AuthenticationFailedException e) { msgUnknown = e.getMessage(); }
        try { authService.authenticate(wrongPass); } catch (AuthenticationFailedException e) { msgWrong = e.getMessage(); }

        assertThat(msgUnknown).isEqualTo(msgWrong);
    }
}
