package com.meshpay.auth.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.meshpay.auth.security.JwtProperties;
import com.meshpay.auth.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import java.lang.reflect.Field;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

    private JwtTokenProvider provider;
    private JwtProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        properties = new JwtProperties();
        properties.setSecret("test-secret-key-that-is-at-least-256-bits-long-for-hs256-testing-1234567890");
        properties.setIssuer("meshpay-auth");
        properties.setExpirationMs(3600000);
        provider = new JwtTokenProvider(properties);
        // manually invoke @PostConstruct
        var init = JwtTokenProvider.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(provider);
    }

    @Test
    void generateAndValidateToken_success() {
        String token = provider.generateToken("bridge-001");
        assertThat(token).isNotBlank();
        assertThat(provider.validateToken(token)).isTrue();
    }

    @Test
    void getBridgeIdFromToken_returnsSubject() {
        String token = provider.generateToken("bridge-123");
        String bridgeId = provider.getBridgeIdFromToken(token);
        assertThat(bridgeId).isEqualTo("bridge-123");
    }

    @Test
    void tokenContainsIssuerAndExpiration() {
        String token = provider.generateToken("bridge-xyz");
        Claims claims = provider.parseClaims(token);
        assertThat(claims.getIssuer()).isEqualTo("meshpay-auth");
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
        assertThat(claims.getSubject()).isEqualTo("bridge-xyz");
    }

    @Test
    void validateTamperedToken_fails() {
        String token = provider.generateToken("bridge-001");
        String tampered = token + "tamper";
        assertThat(provider.validateToken(tampered)).isFalse();
    }

    @Test
    void expiredToken_fails() throws Exception {
        properties.setExpirationMs(1);
        // re-init to ensure expiration used (signing key unchanged)
        String token = provider.generateToken("bridge-exp");
        Thread.sleep(10);
        assertThat(provider.validateToken(token)).isFalse();
    }

    @Test
    void validateTokenWithWrongIssuer_fails() throws Exception {
        JwtProperties otherProps = new JwtProperties();
        otherProps.setSecret("another-secret-key-that-is-at-least-256-bits-long-for-hs256-testing-0987654321");
        otherProps.setIssuer("different-issuer");
        otherProps.setExpirationMs(3600000);
        JwtTokenProvider otherProvider = new JwtTokenProvider(otherProps);
        var init = JwtTokenProvider.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(otherProvider);

        String tokenFromOther = otherProvider.generateToken("bridge-001");
        // our provider validates issuer meshpay-auth, other token has different issuer -> should fail
        assertThat(provider.validateToken(tokenFromOther)).isFalse();
    }

    @Test
    void signingKeyIsAtLeast256Bits() {
        SecretKey key = provider.getSigningKey();
        assertThat(key.getEncoded().length).isGreaterThanOrEqualTo(32);
    }

    @Test
    void tokenIsSignedAndNotPlaintext() {
        String token = provider.generateToken("bridge-001");
        // JWT has 3 parts separated by dots
        assertThat(token.split("\\.")).hasSize(3);
    }
}
