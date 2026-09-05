package com.meshpay.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordEncoderConfig {

    //Creates and registers a password hashing tool within the java application
    //Bcrypt hashing algo is used
    // the method returns a  generic  password encoder interface,
    // which can be used to hash and verify passwords in the application
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
