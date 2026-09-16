package com.example.urlshortener.app.dto;

import com.example.urlshortener.app.Link;

public record LinkResponse(
        String code,
        String shortUrl,
        String targetUrl,
        String createdAt,
        String expiresAt,
        boolean isCustomAlias,
        long clickCount,
        boolean isActive
) {
    public static LinkResponse from(Link link, String baseHost) {
        return new LinkResponse(
                link.code(),
                baseHost + "/" + link.code(),
                link.targetUrl(),
                link.createdAt(),
                link.expiresAt(),
                link.isCustomAlias(),
                link.clickCount(),
                link.isActive()
        );
    }
}
