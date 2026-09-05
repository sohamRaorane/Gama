package com.meshpay.auth.config;

import com.meshpay.auth.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // Stateless APIs do not use browser CSRF protection.
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // Never create server-side sessions.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, exAuth) ->
                                writeError(
                                        response,
                                        HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHORIZED",
                                        "Authentication required",
                                        request.getRequestURI()
                                ))
                        .accessDeniedHandler((request, response, exDenied) ->
                                writeError(
                                        response,
                                        HttpServletResponse.SC_FORBIDDEN,
                                        "FORBIDDEN",
                                        "Access denied",
                                        request.getRequestURI()
                                )))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/bridges/register",
                                "/api/v1/auth/login",
                                "/actuator/health"
                        ).permitAll() // Registration, login, and health checks are public.
                        .anyRequest().authenticated()) // Every other endpoint requires a valid JWT.
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class) // Validate JWT before standard authentication.
                .httpBasic(AbstractHttpConfigurer::disable) // Disable HTTP Basic authentication.
                .formLogin(AbstractHttpConfigurer::disable); // Disable browser form-based login.

        return http.build(); // Build the security filter chain.
    }

    private void writeError(
            HttpServletResponse response,
            int status,
            String error,
            String message,
            String path
    ) throws IOException {

        response.setStatus(status); // Set the HTTP status code.
        response.setContentType(MediaType.APPLICATION_JSON_VALUE); // Return JSON.

        objectMapper.writeValue(
                response.getWriter(),
                Map.of(
                        "timestamp", Instant.now().toString(),
                        "status", status,
                        "error", error,
                        "message", message,
                        "path", path
                )
        ); // Serialize the security error as JSON.
    }
}