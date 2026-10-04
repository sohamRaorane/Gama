package com.meshpay.ingestion.idempotency;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day 12 Layer 2 verification against a real Redis container, because the behaviour
 * under test is the atomicity and expiry of {@code SET key value NX EX ttl}.
 * A mocked Redis would not prove that SET NX EX semantics are actually used.
 */
@Testcontainers
class IdempotencyServiceTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.10.1")
            .withExposedPorts(6379);

    private static final long TTL_SECONDS = 300;
    private static final String HASH_1 = "a".repeat(64);
    private static final String HASH_2 = "b".repeat(64);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        // The container is shared across this class, so each test starts without any
        // inherited claim and cannot be affected by the order tests run in.
        redisTemplate.delete(List.of("packet_hash:" + HASH_1, "packet_hash:" + HASH_2));
        idempotencyService = new IdempotencyService(redisTemplate, TTL_SECONDS);
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void firstClaimSucceedsAndSecondClaimFails() {
        assertTrue(idempotencyService.tryClaim(HASH_1), "first submission must win the claim");
        assertFalse(idempotencyService.tryClaim(HASH_1), "duplicate submission must lose the claim");

        String key = "packet_hash:" + HASH_1;
        assertTrue(redisTemplate.hasKey(key), "claim must exist after the first submission");
        assertEquals("claimed", redisTemplate.opsForValue().get(key));
    }

    @Test
    void claimUsesPacketHashKeyAndNeverTouchesRateLimitNamespace() {
        assertTrue(idempotencyService.tryClaim(HASH_1));

        Set<String> packetKeys = redisTemplate.keys("packet_hash:*");
        assertEquals(Set.of("packet_hash:" + HASH_1), packetKeys,
                "key must be exactly packet_hash:{packetHash}");
        assertTrue(redisTemplate.keys("rate_limit:*").isEmpty(),
                "idempotency must not write into the Day 11 rate_limit namespace");
        assertTrue(packetKeys.stream().noneMatch(k -> k.contains("bridge")),
                "bridge identity must not appear in the idempotency key");
    }

    @Test
    void claimHasConfiguredExpiration() {
        assertTrue(idempotencyService.tryClaim(HASH_1));

        Long ttl = redisTemplate.getExpire("packet_hash:" + HASH_1);
        assertTrue(ttl != null && ttl > 0, "claim must expire, but TTL was: " + ttl);
        assertTrue(ttl <= TTL_SECONDS, "TTL must not exceed the configured value, was: " + ttl);
    }

    @Test
    void claimIsReleasedAfterTtlExpires() throws InterruptedException {
        IdempotencyService shortLived = new IdempotencyService(redisTemplate, 1);

        assertTrue(shortLived.tryClaim(HASH_1));
        assertFalse(shortLived.tryClaim(HASH_1));

        Thread.sleep(1200);

        assertTrue(shortLived.tryClaim(HASH_1), "claim must be claimable again after the TTL elapses");
    }

    @Test
    void differentPacketsCanBothBeClaimed() {
        assertTrue(idempotencyService.tryClaim(HASH_1), "first packet must be claimable");
        assertTrue(idempotencyService.tryClaim(HASH_2), "different packet must be claimable independently");

        assertTrue(redisTemplate.hasKey("packet_hash:" + HASH_1));
        assertTrue(redisTemplate.hasKey("packet_hash:" + HASH_2));
    }

    @Test
    void concurrentClaimsProduceExactlyOneWinner() throws InterruptedException {
        int attempts = 10;
        AtomicInteger winners = new AtomicInteger();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(attempts);

        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        for (int i = 0; i < attempts; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (idempotencyService.tryClaim(HASH_1)) {
                        winners.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();

        assertEquals(1, winners.get(),
                "exactly one concurrent submission must win the claim, won by " + winners.get());
    }
}
