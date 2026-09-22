package com.example.urlshortener.app;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void allowsUpToLimitThenBlocks() {
        RateLimiter limiter = new RateLimiter(3, Duration.ofSeconds(60));
        assertThat(limiter.allow("client-a").allowed()).isTrue();
        assertThat(limiter.allow("client-a").allowed()).isTrue();
        assertThat(limiter.allow("client-a").allowed()).isTrue();
        RateLimiter.Decision fourth = limiter.allow("client-a");
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.retryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    void differentClientsHaveIndependentBuckets() {
        RateLimiter limiter = new RateLimiter(1, Duration.ofSeconds(60));
        assertThat(limiter.allow("a").allowed()).isTrue();
        assertThat(limiter.allow("b").allowed()).isTrue();
        assertThat(limiter.allow("a").allowed()).isFalse();
    }

    @Test
    void resetClearsAllBuckets() {
        RateLimiter limiter = new RateLimiter(1, Duration.ofSeconds(60));
        limiter.allow("a");
        limiter.reset();
        assertThat(limiter.allow("a").allowed()).isTrue();
    }
}
