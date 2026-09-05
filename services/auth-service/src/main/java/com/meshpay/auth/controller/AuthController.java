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
@RequestMapping("/api/v1/auth") //so the path for this controller
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/bridges/register")
    public ResponseEntity<RegisterBridgeResponse> register(@Valid @RequestBody RegisterBridgeRequest request) {
        RegisterBridgeResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> token(@Valid @RequestBody TokenRequest request) {
        TokenResponse response = authService.authenticate(request);
        return ResponseEntity.ok(response);
    }
    /*
    So this endpoint can validate that :
    Jwt --> JwtAuthenticationFilter --> Jwt Validated --> bridgeId extracted --> Auth created --> controller receives auth --> /me returns the bridgeId
     */
    //Who is the current authenticated bridge? This endpoint returns the bridgeId of the currently authenticated bridge.
    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> me(Authentication authentication) {
        //authentication.getPrinicpal()  -> uske andar bridgeId hoga kyuki humne JwtAuthenticationFilter me set kiya hai
        String bridgeId = (String) authentication.getPrincipal();
        return ResponseEntity.ok(Map.of("bridgeId", bridgeId));
    }
    /*
    Map.of:
    It cannot be changed (Immutable): You cannot add, remove, or modify elements after creating it.
    If you try to do capitals.put("France", "Paris")
    , Java will throw an UnsupportedOperationException
    .No nulls allowed: Neither the keys nor the values can be null.
    Passing a null will instantly trigger a NullPointerException.
    No duplicate keys: Every key you pass into Map.of() must be unique.
     If you pass the same key twice, Java will throw an IllegalArgumentException.
     */
}
