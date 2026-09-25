package com.meshpay.ingestion.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meshpay.ingestion.ratelimiter.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that enforces JWT authentication and per-bridge rate limiting
 * BEFORE any request reaches the controller.
 *
 * Processing order (per request):
 * 1. Skip actuator endpoints (health checks need no auth)
 * 2. Extract Bearer token from Authorization header
 * 3. Validate JWT → 401 UNAUTHORIZED if missing or invalid
 * 4. Rate limit check via Redis → 429 RATE_LIMIT_EXCEEDED if bucket empty
 * 5. Set SecurityContext with authenticated bridgeId → continue to controller
 *
 * Critical design decisions:
 * - Auth BEFORE rate limiting: prevents unauthenticated requests from consuming
 *   rate-limit tokens (a spoofed request shouldn't drain a real bridge's quota)
 * - Rejection short-circuits the filter chain: 401/429 responses never reach
 *   the controller, so business logic (and database writes) never run on
 *   rejected requests
 * - Writes JSON directly (not via GlobalExceptionHandler): the filter runs
 *   before Spring MVC, so there's no controller advice to leverage
 * - The authenticated bridgeId (from JWT sub) is placed in SecurityContext
 *   and later used by IngestionService to prevent identity spoofing
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String BRIDGE_ROLE = "ROLE_BRIDGE";

    private final JwtTokenProvider jwtTokenProvider;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                   RateLimiter rateLimiter,
                                   ObjectMapper objectMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // Actuator endpoints (health checks) bypass auth and rate limiting entirely.
        // Docker healthchecks hit /actuator/health without a JWT.
        if (path.startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Step 1: Extract and validate the Bearer token from Authorization header.
        // Returns null if header is missing, malformed, or JWT is invalid/expired.
        String token = resolveToken(request);
        String bridgeId = (token != null) ? jwtTokenProvider.validateAndGetBridgeId(token) : null;

        // Step 2: Reject if no valid identity — 401 BEFORE rate limiting so that
        // unauthenticated requests cannot consume a real bridge's rate-limit quota.
        if (bridgeId == null) {
            writeJsonError(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "Missing or invalid Authorization header");
            return;
        }

        // Step 3: Per-bridge rate limiting via Redis token bucket.
        // Key: rate_limit:{bridgeId}, capacity=10, refill=5/sec (configurable).
        // Returns 429 without reaching controller — business logic does NOT run.
        if (!rateLimiter.attemptConsume(bridgeId)) {
            writeJsonError(request, response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED",
                    "Too many requests. Try again later.");
            return;
        }

        // Step 4: Authentication succeeded — set SecurityContext with the bridgeId.
        // The controller/service layer reads this via SecurityContextHolder to
        // verify the request body's bridgeId matches the authenticated identity.
        var authentication = new UsernamePasswordAuthenticationToken(
                bridgeId, null, List.of(new SimpleGrantedAuthority(BRIDGE_ROLE)));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // Continue to Spring Security authorization, then DispatcherServlet → controller.
        filterChain.doFilter(request, response);
    }

    /**
     * Extracts the raw JWT from the Authorization header.
     * Expected format: "Authorization: Bearer eyJhbGci..."
     *
     * @return the token string, or null if header is missing or lacks Bearer prefix
     */
    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (StringUtils.hasText(authorization) && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }

    /**
     * Writes a JSON error response directly to the servlet response.
     * Format matches GlobalExceptionHandler's error shape for consistency:
     * {timestamp, status, error, message, path}
     *
     * This runs before Spring MVC, so we serialize manually with ObjectMapper.
     */
    private void writeJsonError(HttpServletRequest request, HttpServletResponse response,
                                HttpStatus status, String error, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        body.put("path", request.getRequestURI());
        objectMapper.writeValue(response.getWriter(), body);
    }
}