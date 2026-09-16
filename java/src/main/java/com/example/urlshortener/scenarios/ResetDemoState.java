package com.example.urlshortener.scenarios;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

/**
 * Restores app/ to its pristine pre-scenario state so the three demo
 * scenarios can be replayed from a clean baseline. Direct port of
 * scenarios/reset_demo_state.py -- see that file's docstring for why this
 * exists (the scenarios' guardrails deliberately refuse to re-apply an
 * already-applied change, which is correct behavior, not a bug).
 *
 * Run with: mvn exec:java -Dexec.mainClass=com.example.urlshortener.scenarios.ResetDemoState
 */
public class ResetDemoState {

    static final String PRISTINE_URL_CONTROLLER = """
            package com.example.urlshortener.app;

            import com.example.urlshortener.app.dto.AnalyticsResponse;
            import com.example.urlshortener.app.dto.CreateLinkRequest;
            import com.example.urlshortener.app.dto.LinkResponse;
            import jakarta.servlet.http.HttpServletRequest;
            import jakarta.validation.Valid;
            import org.springframework.beans.factory.annotation.Value;
            import org.springframework.http.HttpHeaders;
            import org.springframework.http.HttpStatus;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.*;

            import java.util.List;
            import java.util.Map;

            @RestController
            public class UrlController {

                private final ShortenerService shortener;
                private final String baseHost;

                public UrlController(ShortenerService shortener, @Value("${urlshort.base-host}") String baseHost) {
                    this.shortener = shortener;
                    this.baseHost = baseHost;
                }

                @GetMapping("/health")
                public Map<String, Object> health() {
                    return Map.of("status", "ok", "cache", shortener.redirectCache.stats());
                }

                @PostMapping("/api/urls")
                public ResponseEntity<LinkResponse> createUrl(@Valid @RequestBody CreateLinkRequest req) {
                    Link link = shortener.createLink(req.url(), req.customAlias(), req.expiresInDays());
                    return ResponseEntity.status(HttpStatus.CREATED).body(LinkResponse.from(link, baseHost));
                }

                @GetMapping("/api/urls/{code}")
                public LinkResponse getUrl(@PathVariable String code) {
                    return LinkResponse.from(shortener.getLink(code, true), baseHost);
                }

                @GetMapping("/api/urls")
                public List<LinkResponse> listUrls(
                        @RequestParam(defaultValue = "50") int limit,
                        @RequestParam(defaultValue = "0") int offset
                ) {
                    int clampedLimit = Math.max(1, Math.min(limit, 200));
                    return shortener.listLinks(clampedLimit, offset).stream()
                            .map(l -> LinkResponse.from(l, baseHost))
                            .toList();
                }

                @DeleteMapping("/api/urls/{code}")
                public ResponseEntity<Void> deleteUrl(@PathVariable String code) {
                    shortener.deactivateLink(code);
                    return ResponseEntity.noContent().build();
                }

                @GetMapping("/api/urls/{code}/analytics")
                public AnalyticsResponse getAnalytics(@PathVariable String code) {
                    return AnalyticsResponse.from(shortener.getAnalytics(code, 20));
                }

                @GetMapping("/{code}")
                public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
                    String target = shortener.resolveLink(code, request.getHeader("referer"), request.getHeader("user-agent"));
                    return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, target).build();
                }
            }
            """;

    static final String PRISTINE_GLOBAL_EXCEPTION_HANDLER = """
            package com.example.urlshortener.app;

            import com.example.urlshortener.app.exceptions.AliasConflictException;
            import com.example.urlshortener.app.exceptions.LinkExpiredException;
            import com.example.urlshortener.app.exceptions.LinkInactiveException;
            import com.example.urlshortener.app.exceptions.LinkNotFoundException;
            import org.springframework.http.HttpStatus;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.MethodArgumentNotValidException;
            import org.springframework.web.bind.annotation.ExceptionHandler;
            import org.springframework.web.bind.annotation.RestControllerAdvice;

            import java.util.LinkedHashMap;
            import java.util.Map;

            @RestControllerAdvice
            public class GlobalExceptionHandler {

                @ExceptionHandler(LinkNotFoundException.class)
                public ResponseEntity<Map<String, String>> handleNotFound(LinkNotFoundException e) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", e.getMessage()));
                }

                @ExceptionHandler({LinkExpiredException.class, LinkInactiveException.class})
                public ResponseEntity<Map<String, String>> handleGone(RuntimeException e) {
                    return ResponseEntity.status(HttpStatus.GONE).body(Map.of("detail", e.getMessage()));
                }

                @ExceptionHandler(AliasConflictException.class)
                public ResponseEntity<Map<String, String>> handleConflict(AliasConflictException e) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", e.getMessage()));
                }

                // Mapped to 422 (rather than Spring's default 400) to stay API-compatible
                // with the Python/FastAPI port, which uses Pydantic's conventional 422
                // for request validation failures.
                @ExceptionHandler(MethodArgumentNotValidException.class)
                public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("detail", e.getBindingResult().getFieldErrors().stream()
                            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                            .toList());
                    return ResponseEntity.status(422).body(body);
                }
            }
            """;

    static final String PRISTINE_ANALYTICS_RESPONSE = """
            package com.example.urlshortener.app.dto;

            import com.example.urlshortener.app.ShortenerService;

            import java.util.List;

            public record AnalyticsResponse(
                    String code,
                    long totalClicks,
                    String createdAt,
                    String expiresAt,
                    boolean isActive,
                    List<ClickEventDto> recentClicks
            ) {
                public static AnalyticsResponse from(ShortenerService.AnalyticsResult r) {
                    return new AnalyticsResponse(
                            r.code(), r.totalClicks(), r.createdAt(), r.expiresAt(), r.isActive(),
                            r.recentClicks().stream().map(ClickEventDto::from).toList()
                    );
                }
            }
            """;

    static final String PRISTINE_SHORTENER_SERVICE = """
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

                public Link createLink(String url, String customAlias, Integer expiresInDays) {
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
                    return new AnalyticsResult(code, link.clickCount(), link.createdAt(), link.expiresAt(), link.isActive(), recent);
                }

                public record AnalyticsResult(
                        String code, long totalClicks, String createdAt, String expiresAt, boolean isActive, List<ClickRecord> recentClicks
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
            """;

    static final List<String> GENERATED_APP_FILES = List.of(
            "src/main/java/com/example/urlshortener/app/dto/BulkCreateItem.java",
            "src/main/java/com/example/urlshortener/app/dto/BulkCreateRequest.java",
            "src/main/java/com/example/urlshortener/app/dto/BulkResultItem.java",
            "src/main/java/com/example/urlshortener/app/dto/BulkCreateResponse.java",
            "src/main/java/com/example/urlshortener/app/AuditLog.java",
            "src/main/java/com/example/urlshortener/app/exceptions/UnsafeTargetException.java",
            "src/main/java/com/example/urlshortener/app/exceptions/ReservedAliasException.java"
    );

    static final List<String> GENERATED_TEST_FILES = List.of(
            "src/test/java/com/example/urlshortener/app/BulkControllerTest.java",
            "src/test/java/com/example/urlshortener/app/AnalyticsEnhancementTest.java",
            "src/test/java/com/example/urlshortener/app/SafetyValidationTest.java"
    );

    public static void main(String[] args) throws IOException {
        Path root = Common.PROJECT_ROOT;

        write(root, "src/main/java/com/example/urlshortener/app/UrlController.java", PRISTINE_URL_CONTROLLER);
        write(root, "src/main/java/com/example/urlshortener/app/GlobalExceptionHandler.java", PRISTINE_GLOBAL_EXCEPTION_HANDLER);
        write(root, "src/main/java/com/example/urlshortener/app/dto/AnalyticsResponse.java", PRISTINE_ANALYTICS_RESPONSE);
        write(root, "src/main/java/com/example/urlshortener/app/ShortenerService.java", PRISTINE_SHORTENER_SERVICE);
        System.out.println("restored UrlController.java, GlobalExceptionHandler.java, AnalyticsResponse.java, ShortenerService.java to pristine state");

        for (String rel : GENERATED_APP_FILES) deleteIfExists(root, rel);
        for (String rel : GENERATED_TEST_FILES) deleteIfExists(root, rel);

        Path changelog = root.resolve("docs/API_CHANGELOG.md");
        if (Files.exists(changelog)) {
            Files.delete(changelog);
            System.out.println("removed docs/API_CHANGELOG.md");
        }

        Path runsDir = root.resolve("runs");
        if (Files.exists(runsDir)) {
            try (Stream<Path> entries = Files.list(runsDir)) {
                for (Path p : entries.toList()) {
                    if (Files.isDirectory(p)) deleteRecursive(p);
                }
            }
        }
        System.out.println("cleared runs/");

        Path db = root.resolve("urlshortener.db");
        if (Files.exists(db)) {
            Files.delete(db);
            System.out.println("removed urlshortener.db");
        }

        System.out.println("\ndemo state reset -- safe to run GreenfieldRun, BrownfieldRun, AmbiguousRun from a clean baseline");
    }

    private static void write(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static void deleteIfExists(Path root, String rel) {
        Path p = root.resolve(rel);
        if (Files.exists(p)) {
            try {
                Files.delete(p);
                System.out.println("removed " + rel);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static void deleteRecursive(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
