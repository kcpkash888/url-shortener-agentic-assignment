package com.example.urlshortener.app;

public record Link(
        String code,
        String targetUrl,
        String createdAt,
        String expiresAt,
        boolean isCustomAlias,
        long clickCount,
        boolean isActive
) {}
