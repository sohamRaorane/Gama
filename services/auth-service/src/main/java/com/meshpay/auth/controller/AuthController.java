package com.meshpay.auth.controller;

import com.meshpay.auth.dto.RegisterBridgeRequest;
import com.meshpay.auth.dto.RegisterBridgeResponse;
import com.meshpay.auth.dto.TokenRequest;
import com.meshpay.auth.dto.TokenResponse;
import com.meshpay.auth.service.AuthService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/bridges/register")
    public ResponseEntity<RegisterBridgeResponse> register(@Valid @RequestBody RegisterBridgeRequest request) {
        RegisterBridgeResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/token")
    public ResponseEntity<TokenResponse> token(@Valid @RequestBody TokenRequest request) {
        TokenResponse response = authService.authenticate(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> me(Authentication authentication) {
        String bridgeId = (String) authentication.getPrincipal();
        return ResponseEntity.ok(Map.of("bridgeId", bridgeId));
    }
}
