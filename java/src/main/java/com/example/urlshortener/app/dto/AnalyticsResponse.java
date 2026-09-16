package com.example.urlshortener.app.dto;

import com.example.urlshortener.app.ShortenerService;

import java.util.List;
import java.util.Map;

public record AnalyticsResponse(
        String code,
        long totalClicks,
        String createdAt,
        String expiresAt,
        boolean isActive,
        List<ClickEventDto> recentClicks,
        List<Map<String, Object>> topReferrers,
        int uniqueReferrerCount
) {
    public static AnalyticsResponse from(ShortenerService.AnalyticsResult r) {
        return new AnalyticsResponse(
                r.code(), r.totalClicks(), r.createdAt(), r.expiresAt(), r.isActive(),
                r.recentClicks().stream().map(ClickEventDto::from).toList(),
                r.topReferrers().stream()
                        .map(tr -> Map.<String, Object>of("referrer", tr.referrer(), "count", tr.count()))
                        .toList(),
                r.uniqueReferrerCount()
        );
    }
}
