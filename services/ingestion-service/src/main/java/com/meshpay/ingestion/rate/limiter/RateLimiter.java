package com.meshpay.ingestion.rate.limiter;

/**
 * Interface for rate limiting operations.
 * The Redis implementation (RedisRateLimiter) fulfills this contract.
 * Redis key format: rate_limit:{bridgeId}
 */
public interface RateLimiter {

    /**
     * Attempt to consume one token from the bucket for the given bridgeId.
     *
     * @param bridgeId the authenticated bridge identity (from JWT sub claim)
     * @return true if the request is allowed (token available), false if rate limited
     */
    boolean attemptConsume(String bridgeId);

    /**
     * Reset the rate limit bucket for testing purposes.
     *
     * @param bridgeId the bridge identity to reset
     */
    void reset(String bridgeId);
}