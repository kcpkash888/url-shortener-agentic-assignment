package com.example.urlshortener.app;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fixed-window per-client rate limiter (in-memory). Adequate for a
 * single-instance prototype; a multi-instance deployment would move this
 * state to Redis.
 */
public class RateLimiter {

    private record Bucket(int count, Instant windowStart) {}

    private volatile int maxRequests;
    private final Duration window;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public RateLimiter(int maxRequests, Duration window) {
        this.maxRequests = maxRequests;
        this.window = window;
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {}

    public Decision allow(String clientKey) {
        lock.lock();
        try {
            Instant now = Instant.now();
            Bucket bucket = buckets.get(clientKey);
            if (bucket == null || Duration.between(bucket.windowStart(), now).compareTo(window) >= 0) {
                bucket = new Bucket(0, now);
            }
            if (bucket.count() >= maxRequests) {
                long retryAfter = window.minus(Duration.between(bucket.windowStart(), now)).toSeconds() + 1;
                return new Decision(false, Math.max(retryAfter, 1));
            }
            buckets.put(clientKey, new Bucket(bucket.count() + 1, bucket.windowStart()));
            return new Decision(true, 0);
        } finally {
            lock.unlock();
        }
    }

    public void reset() {
        buckets.clear();
    }

    public void setMaxRequests(int maxRequests) {
        this.maxRequests = maxRequests;
    }
}
