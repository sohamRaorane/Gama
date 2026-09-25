package com.meshpay.ingestion;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the MeshPay Ingestion Service.
 *
 * Responsibilities:
 * - Loads .env file variables into system properties before Spring starts
 * - Forces JVM timezone to UTC for consistent timestamp handling across services
 * - Bootstraps the Spring Boot application (web server, JPA, Flyway, security, Redis)
 *
 * The service exposes POST /api/v1/packets for bridges to submit encrypted mesh packets.
 * Authentication (JWT HS256) and per-bridge rate limiting (Redis token bucket)
 * are enforced by JwtAuthenticationFilter before any request reaches the controller.
 */
@SpringBootApplication
public class IngestionServiceApplication {

	static {
		// Load .env file into system properties before Spring context initializes.
		// This ensures DB_URL, JWT_SECRET, etc. are available for ${...} placeholders.
		DotenvLoader.load();
	}

	public static void main(String[] args) {
		// Force UTC as the default timezone. All timestamps in the database (created_at,
		// updated_at) and all JWT expiry calculations use UTC to avoid timezone drift
		// between services running in different environments.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(IngestionServiceApplication.class, args);
	}
}