package com.meshpay.auth.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {
    //Setting up the JWT properties
    private String secret;
    private String issuer = "meshpay-auth";
    private long expirationMs = 3600000;
}
