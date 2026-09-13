package com.meshpay.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Utility class responsible for managing and sharing a centrally configured,
 * thread-safe Jackson ObjectMapper instance for all payment-related tasks.
 */
public final class PaymentSerializer {

    // Eagerly instantiates the single, thread-safe global ObjectMapper instance at class loading time
    private static final ObjectMapper MAPPER = createObjectMapper();

    // Private constructor blocks external instantiation via "new PaymentSerializer()"
    private PaymentSerializer() {}

    /**
     * Public accessor method to obtain the globally shared, pre-configured ObjectMapper instance.
     * Reusing this instance is essential for application performance.
     */
    public static ObjectMapper objectMapper() {
        return MAPPER;
    }

    /**
     * Internal factory method that spins up and configs the specific rule parameters for Jackson.
     */
    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Registers support for modern Java 8+ Date/Time APIs (e.g., Instant, LocalDateTime, LocalDate)
        mapper.registerModule(new JavaTimeModule());

        // Disables numeric array timestamps; forces dates to serialize as clean ISO-8601 strings (e.g., "2026-09-12T16:59:00Z")
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        return mapper;
    }
}
