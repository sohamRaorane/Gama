package com.meshpay.ingestion.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Validates HS256 JWT tokens issued by the MeshPay Auth Service.
 *
 * Unlike auth-service's JwtTokenProvider (which both generates and validates),
 * this class ONLY validates tokens — the ingestion service never issues JWTs.
 *
 * Validation checks:
 * 1. Signature: HMAC-SHA256 with shared secret (JWT_SECRET env var)
 * 2. Issuer: must be "meshpay-auth" (prevents token reuse from other services)
 * 3. Expiration: JJWT's parseSignedClaims rejects expired tokens automatically
 *
 * Security notes:
 * - The secret must be ≥ 32 bytes (checked in @PostConstruct init)
 * - Same JWT_SECRET is used by both auth-service and ingestion-service
 *   (shared symmetric key — no JWKS/public key endpoint exists yet)
 * - Returns null on any validation failure (never throws to callers)
 */
@Component
public class JwtTokenProvider {

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.issuer}")
    private String issuer;

    private SecretKey signingKey;

    /**
     * Derives the HMAC signing key from the shared secret.
     * Throws IllegalStateException if the secret is too short for HS256.
     */
    @PostConstruct
    void init() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 bytes for HS256");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Validates a JWT and extracts the bridgeId (sub claim).
     *
     * @param token the raw JWT string from the Authorization: Bearer header
     * @return the bridgeId if valid, or null if the token is expired, tampered,
     *         has wrong issuer, or is otherwise malformed
     */
    public String validateAndGetBridgeId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.getSubject();
        } catch (JwtException | IllegalArgumentException ex) {
            return null;
        }
    }
}