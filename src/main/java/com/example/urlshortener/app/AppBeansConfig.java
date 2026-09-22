package com.example.urlshortener.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class AppBeansConfig {

    @Bean
    public RateLimiter rateLimiter(
            @Value("${urlshort.rate-limit-requests}") int maxRequests,
            @Value("${urlshort.rate-limit-window-seconds}") int windowSeconds
    ) {
        return new RateLimiter(maxRequests, Duration.ofSeconds(windowSeconds));
    }
}
