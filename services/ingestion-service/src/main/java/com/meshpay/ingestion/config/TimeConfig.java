package com.meshpay.ingestion.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the single JVM-wide clock used for time-based decisions.
 *
 * <p>Business logic (freshness validation) never calls {@code Instant.now()}
 * directly — it receives this {@link Clock} by constructor injection. Production
 * uses {@link Clock#systemUTC()}; tests substitute {@code Clock.fixed(...)} so
 * freshness assertions are deterministic instead of timing-dependent.
 *
 * <p>{@code systemUTC()} guarantees UTC epoch seconds regardless of the machine's
 * default timezone, so freshness behaviour cannot drift between environments.
 * (The application also forces {@code TimeZone.setDefault(UTC)} at startup, but
 * the validator must not depend on that.)
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
