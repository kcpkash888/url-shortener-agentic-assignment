package com.example.urlshortener.app;

import com.example.urlshortener.app.exceptions.AliasConflictException;
import com.example.urlshortener.app.exceptions.LinkExpiredException;
import com.example.urlshortener.app.exceptions.LinkInactiveException;
import com.example.urlshortener.app.exceptions.LinkNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Core URL-shortening domain logic: code generation, persistence, resolution,
 * click tracking and analytics. Kept independent of the web layer so it can
 * be unit-tested directly and reused by the orchestrator's
 * implementation/testing agents.
 */
@Service
public class ShortenerService {

    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final int codeLength;
    public final TtlCache redirectCache;

    public ShortenerService(
            JdbcTemplate jdbc,
            @Value("${urlshort.code-length}") int codeLength,
            @Value("${urlshort.cache-max-size}") int cacheMaxSize,
            @Value("${urlshort.cache-ttl-seconds}") int cacheTtlSeconds
    ) {
        this.jdbc = jdbc;
        this.codeLength = codeLength;
        this.redirectCache = new TtlCache(cacheMaxSize, Duration.ofSeconds(cacheTtlSeconds));
    }

    private static String nowIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
    }

    private static String generateCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private boolean codeExists(String code) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM links WHERE code = ?", Integer.class, code);
        return count != null && count > 0;
    }

    private static final java.util.Set<String> BLOCKED_DOMAINS =
            java.util.Set.of("malicious-example.test", "phishing-example.test");
    private static final java.util.Set<String> RESERVED_ALIASES =
            java.util.Set.of("api", "health", "admin", "www");

    public Link createLink(String url, String customAlias, Integer expiresInDays) {
        String domain = java.net.URI.create(url).getHost();
        if (domain != null && BLOCKED_DOMAINS.contains(domain.toLowerCase())) {
            throw new com.example.urlshortener.app.exceptions.UnsafeTargetException(
                    "target domain '" + domain + "' is not allowed");
        }
        if (customAlias != null && RESERVED_ALIASES.contains(customAlias.toLowerCase())) {
            throw new com.example.urlshortener.app.exceptions.ReservedAliasException(
                    "alias '" + customAlias + "' is reserved for internal use");
        }
        String code;
        boolean isCustom;
        if (customAlias != null && !customAlias.isBlank()) {
            if (codeExists(customAlias)) {
                throw new AliasConflictException("alias '" + customAlias + "' is already in use");
            }
            code = customAlias;
            isCustom = true;
        } else {
            code = generateCode(codeLength);
            int attempts = 0;
            while (codeExists(code)) {
                attempts++;
                if (attempts > 5) {
                    throw new IllegalStateException("failed to generate a unique code after 5 attempts");
                }
                code = generateCode(codeLength);
            }
            isCustom = false;
        }

        String createdAt = nowIso();
        String expiresAt = null;
        if (expiresInDays != null && expiresInDays > 0) {
            expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(expiresInDays).toString();
        }

        jdbc.update(
                "INSERT INTO links (code, target_url, created_at, expires_at, is_custom_alias, click_count, is_active) "
                        + "VALUES (?, ?, ?, ?, ?, 0, 1)",
                code, url, createdAt, expiresAt, isCustom ? 1 : 0
        );

        return getLink(code, true);
    }

    public Link getLink(String code, boolean allowInactive) {
        List<Link> rows = jdbc.query(
                "SELECT * FROM links WHERE code = ?",
                (rs, rowNum) -> new Link(
                        rs.getString("code"),
                        rs.getString("target_url"),
                        rs.getString("created_at"),
                        rs.getString("expires_at"),
                        rs.getInt("is_custom_alias") != 0,
                        rs.getLong("click_count"),
                        rs.getInt("is_active") != 0
                ),
                code
        );
        if (rows.isEmpty()) {
            throw new LinkNotFoundException("no link found for code '" + code + "'");
        }
        Link link = rows.get(0);
        if (!allowInactive && !link.isActive()) {
            throw new LinkInactiveException("link '" + code + "' has been deactivated");
        }
        return link;
    }

    public String resolveLink(String code, String referrer, String userAgent) {
        String targetUrl = redirectCache.get(code);
        if (targetUrl == null) {
            Link link = getLink(code, false);
            if (link.expiresAt() != null && OffsetDateTime.parse(link.expiresAt()).isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
                throw new LinkExpiredException("link '" + code + "' expired at " + link.expiresAt());
            }
            targetUrl = link.targetUrl();
            redirectCache.set(code, targetUrl);
        }

        jdbc.update("UPDATE links SET click_count = click_count + 1 WHERE code = ?", code);
        jdbc.update(
                "INSERT INTO clicks (code, clicked_at, referrer, user_agent) VALUES (?, ?, ?, ?)",
                code, nowIso(), referrer, userAgent
        );
        return targetUrl;
    }

    public void deactivateLink(String code) {
        getLink(code, true); // raises LinkNotFoundException if missing
        jdbc.update("UPDATE links SET is_active = 0 WHERE code = ?", code);
        redirectCache.invalidate(code);
    }

    public AnalyticsResult getAnalytics(String code, int recentLimit) {
        Link link = getLink(code, true);
        List<ClickRecord> recent = jdbc.query(
                "SELECT clicked_at, referrer, user_agent FROM clicks WHERE code = ? ORDER BY clicked_at DESC LIMIT ?",
                (rs, rowNum) -> new ClickRecord(rs.getString("clicked_at"), rs.getString("referrer"), rs.getString("user_agent")),
                code, recentLimit
        );
        List<ReferrerCount> topReferrers = jdbc.query(
                "SELECT COALESCE(referrer, 'direct') AS referrer, COUNT(*) as cnt FROM clicks WHERE code = ? "
                        + "GROUP BY referrer ORDER BY cnt DESC LIMIT 5",
                (rs, rowNum) -> new ReferrerCount(rs.getString("referrer"), rs.getLong("cnt")),
                code
        );
        int uniqueReferrerCount = topReferrers.size();
        return new AnalyticsResult(code, link.clickCount(), link.createdAt(), link.expiresAt(), link.isActive(), recent, topReferrers, uniqueReferrerCount);
    }

    public record ReferrerCount(String referrer, long count) {}

    public record AnalyticsResult(
            String code, long totalClicks, String createdAt, String expiresAt, boolean isActive,
            List<ClickRecord> recentClicks, List<ReferrerCount> topReferrers, int uniqueReferrerCount
    ) {}

    public List<Link> listLinks(int limit, int offset) {
        return jdbc.query(
                "SELECT * FROM links ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new Link(
                        rs.getString("code"),
                        rs.getString("target_url"),
                        rs.getString("created_at"),
                        rs.getString("expires_at"),
                        rs.getInt("is_custom_alias") != 0,
                        rs.getLong("click_count"),
                        rs.getInt("is_active") != 0
                ),
                limit, offset
        );
    }
}
