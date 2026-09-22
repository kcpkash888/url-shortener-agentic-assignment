package com.example.urlshortener.orchestrator;

public record RetryPolicy(int maxAttempts, long backoffMillis) {
    public static RetryPolicy none() {
        return new RetryPolicy(1, 0);
    }

    public static RetryPolicy of(int maxAttempts, long backoffMillis) {
        return new RetryPolicy(maxAttempts, backoffMillis);
    }
}
