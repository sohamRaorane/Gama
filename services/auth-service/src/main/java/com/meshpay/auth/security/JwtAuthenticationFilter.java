package com.meshpay.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    //Constant Definitions
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String BRIDGE_ROLE = "ROLE_BRIDGE";

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String token = resolveToken(request); // Extract the Bearer token from the request.

        if (StringUtils.hasText(token)
                && SecurityContextHolder.getContext().getAuthentication() == null
                && jwtTokenProvider.validateToken(token)) { // Authenticate only an unverified request with a valid JWT.

            String bridgeId = jwtTokenProvider.getBridgeIdFromToken(token); // Extract the bridge identity from the JWT.

            var authentication = new UsernamePasswordAuthenticationToken(
                    bridgeId,
                    null,
                    List.of(new SimpleGrantedAuthority(BRIDGE_ROLE))
            ); // Create Spring Security authentication for the bridge.

            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request)
            ); // Attach request metadata to the authentication.

            SecurityContextHolder.getContext().setAuthentication(authentication); // Store authentication for this request.
        }

        filterChain.doFilter(request, response); // Continue processing the request.
    }


    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization"); // Read the Authorization header.

        if (StringUtils.hasText(authorization)
                && authorization.startsWith(BEARER_PREFIX)) { // Check for the Bearer authentication scheme.
            return authorization.substring(BEARER_PREFIX.length()).trim(); // Return only the JWT.
        }

        return null; // No usable JWT was provided.
    }
}