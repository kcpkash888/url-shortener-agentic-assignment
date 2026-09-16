package com.example.urlshortener.app;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Redirects are the hot path and are deliberately excluded from the
 * write-API rate limit; abusive redirect traffic is a separate concern
 * (CDN/WAF layer).
 */
@Component
public class RateLimitFilter extends HttpFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiter rateLimiter;

    public RateLimitFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        long start = System.nanoTime();
        if (request.getRequestURI().startsWith("/api/")) {
            String clientKey = request.getRemoteAddr();
            RateLimiter.Decision decision = rateLimiter.allow(clientKey);
            if (!decision.allowed()) {
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
                response.setContentType("application/json");
                response.getWriter().write("{\"detail\":\"rate limit exceeded\"}");
                return;
            }
        }
        chain.doFilter(request, response);
        double durationMs = (System.nanoTime() - start) / 1_000_000.0;
        log.info("{} {} -> {} ({}ms)", request.getMethod(), request.getRequestURI(), response.getStatus(),
                String.format("%.1f", durationMs));
    }
}
