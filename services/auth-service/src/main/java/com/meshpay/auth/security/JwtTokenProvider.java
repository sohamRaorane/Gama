package com.meshpay.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtProperties jwtProperties;
    private SecretKey signingKey;

    @PostConstruct
    void init() {
        byte[] keyBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);

        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT secret must be at least 32 bytes (256 bits) for HS256"
            );
        }
        // Initialize the HMAC signing key.
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(String bridgeId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtProperties.getExpirationMs());

        return Jwts.builder()
                .subject(bridgeId)
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact(); // Create a signed JWT.
    }

    public String generateToken(String bridgeId, Map<String, Object> extraClaims) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtProperties.getExpirationMs());

        return Jwts.builder()
                .subject(bridgeId)
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiration(expiry)
                .claims(extraClaims)
                .signWith(signingKey)
                .compact(); // Create a signed JWT with additional claims.
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token); // Cryptographically verify and parse the JWT.
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return false; // Reject invalid, expired, malformed, or incorrectly signed tokens.
        }
    }

    public String getBridgeIdFromToken(String token) {
        Claims claims = parseClaims(token); // Parse and verify the JWT.
        return claims.getSubject(); // Return the bridge identity.
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(jwtProperties.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload(); // Verify signature, issuer, expiration, and parse claims.
    }

    public long getExpirationMs() {
        return jwtProperties.getExpirationMs();
    }

    public SecretKey getSigningKey() { return signingKey; }
}