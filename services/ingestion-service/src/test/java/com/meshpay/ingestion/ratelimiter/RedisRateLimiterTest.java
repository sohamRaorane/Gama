package com.meshpay.ingestion.ratelimiter;

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

@Testcontainers
class RedisRateLimiterTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.10.1")
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private RedisRateLimiter rateLimiter;

    private static final long CAPACITY = 10;
    private static final long REFILL_RATE = 5;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        rateLimiter = new RedisRateLimiter(template, CAPACITY, REFILL_RATE);
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void scenario5_independentBridgesDoNotAffectEachOther() {
        boolean rejectedA = false;
        for (int i = 0; i < CAPACITY + 10; i++) {
            if (!rateLimiter.attemptConsume("bridge-A")) {
                rejectedA = true;
                break;
            }
        }
        assertTrue(rejectedA, "bridge-A should eventually be rate limited");

        assertTrue(rateLimiter.attemptConsume("bridge-B"),
                "bridge-B should not be affected by bridge-A rate limit");
    }

    @Test
    void scenario6_tokensRefillOverTime() throws InterruptedException {
        boolean rejected = false;
        for (int i = 0; i < CAPACITY + 10; i++) {
            if (!rateLimiter.attemptConsume("bridge-refill")) {
                rejected = true;
                break;
            }
        }
        assertTrue(rejected, "Bucket should be exhausted after rapid consumption");

        Thread.sleep(600);

        assertTrue(rateLimiter.attemptConsume("bridge-refill"),
                "Token should have refilled after waiting");
    }

    @Test
    void scenario7_concurrentRequestsDoNotExceedCapacity() throws InterruptedException {
        String bridgeId = "bridge-concurrent";
        int threads = 20;
        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (rateLimiter.attemptConsume(bridgeId)) {
                        allowed.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
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

        assertTrue(allowed.get() <= CAPACITY,
                "Allowed " + allowed.get() + " requests, expected at most " + CAPACITY);
        assertEquals(threads, allowed.get() + rejected.get(),
                "Every request should be either allowed or rejected");
    }

    @Test
    void resetClearsBucket() {
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(rateLimiter.attemptConsume("bridge-reset"));
        }
        assertFalse(rateLimiter.attemptConsume("bridge-reset"));

        rateLimiter.reset("bridge-reset");

        assertTrue(rateLimiter.attemptConsume("bridge-reset"),
                "Bucket should be full again after reset");
    }
}