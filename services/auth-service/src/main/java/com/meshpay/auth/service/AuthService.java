package com.meshpay.auth.service;

import com.meshpay.auth.dto.RegisterBridgeRequest;
import com.meshpay.auth.dto.RegisterBridgeResponse;
import com.meshpay.auth.dto.TokenRequest;
import com.meshpay.auth.dto.TokenResponse;
import com.meshpay.auth.entity.Bridge;
import com.meshpay.auth.exception.AuthenticationFailedException;
import com.meshpay.auth.exception.DuplicateResourceException;
import com.meshpay.auth.repository.BridgeRepository;
import com.meshpay.auth.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final BridgeRepository bridgeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public RegisterBridgeResponse register(RegisterBridgeRequest request) {
        if (bridgeRepository.existsByBridgeId(request.getBridgeId())) {
            throw new DuplicateResourceException("Bridge with bridgeId '" + request.getBridgeId() + "' already exists");
        }
        if (bridgeRepository.existsByCredentialIdentifier(request.getCredentialIdentifier())) {
            throw new DuplicateResourceException(
                    "Bridge with credentialIdentifier '" + request.getCredentialIdentifier() + "' already exists");
        }

        String hashed = passwordEncoder.encode(request.getSecret());

        Bridge bridge = Bridge.builder()
                .bridgeId(request.getBridgeId())
                .credentialIdentifier(request.getCredentialIdentifier())
                .credentialHash(hashed)
                .enabled(true)
                .build();

        Bridge saved = bridgeRepository.save(bridge);

        return RegisterBridgeResponse.builder()
                .bridgeId(saved.getBridgeId())
                .credentialIdentifier(saved.getCredentialIdentifier())
                .enabled(saved.getEnabled())
                .createdAt(saved.getCreatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public TokenResponse authenticate(TokenRequest request) {
        Bridge bridge = bridgeRepository
                .findByCredentialIdentifier(request.getCredentialIdentifier())
                .orElseThrow(() -> new AuthenticationFailedException("Invalid credentials"));

        if (Boolean.FALSE.equals(bridge.getEnabled())) {
            throw new AuthenticationFailedException("Invalid credentials");
        }

        if (!passwordEncoder.matches(request.getSecret(), bridge.getCredentialHash())) {
            throw new AuthenticationFailedException("Invalid credentials");
        }

        String token = jwtTokenProvider.generateToken(bridge.getBridgeId());
        long expiresIn = jwtTokenProvider.getExpirationMs() / 1000;

        return TokenResponse.of(token, expiresIn);
    }
}
