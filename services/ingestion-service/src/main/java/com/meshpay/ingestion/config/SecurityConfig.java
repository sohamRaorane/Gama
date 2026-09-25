package com.meshpay.ingestion.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meshpay.ingestion.ratelimiter.RateLimiter;
import com.meshpay.ingestion.security.JwtAuthenticationFilter;
import com.meshpay.ingestion.security.JwtTokenProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration for the Ingestion Service.
 *
 * Security model:
 * - Stateless (no HTTP sessions): each request carries its own Bearer JWT
 * - CSRF disabled: API-to-API communication (bridges) does not use cookies/forms
 * - Actuator health endpoint is public for Docker healthchecks
 * - All other endpoints require a valid JWT with issuer=meshpay-auth
 *
 * Filter chain order (critical):
 * 1. JwtAuthenticationFilter runs BEFORE UsernamePasswordAuthenticationFilter
 *    - Validates Bearer token (401 if missing/invalid)
 *    - Checks Redis rate limiter (429 if exceeded)
 *    - Sets SecurityContext with authenticated bridgeId on success
 * 2. Spring Security authorization (authenticated/permitAll)
 * 3. DispatcherServlet routes to controller
 *
 * This ordering guarantees: auth BEFORE rate limiting BEFORE business logic.
 * The Day 11 tests (PacketIngestionSecurityTest) assert this ordering.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Creates the JWT authentication + rate-limiting filter as a bean.
     * Dependencies (JwtTokenProvider, RateLimiter, ObjectMapper) are injected
     * by Spring, allowing them to be mocked independently in tests.
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                                           RateLimiter rateLimiter,
                                                           ObjectMapper objectMapper) {
        return new JwtAuthenticationFilter(jwtTokenProvider, rateLimiter, objectMapper);
    }

    /**
     * Defines the Spring Security filter chain.
     *
     * Key decisions:
     * - STATELESS: no server-side session; JWT is validated per-request
     * - /actuator/** permitAll: Docker healthchecks need unauthenticated access
     * - anyRequest().authenticated(): everything else requires valid JWT
     * - addFilterBefore: our filter runs before Spring's default auth filter
     *   so we can write custom 401/429 JSON responses (not Spring's defaults)
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}