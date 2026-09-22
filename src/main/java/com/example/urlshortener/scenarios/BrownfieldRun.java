package com.example.urlshortener.scenarios;

import com.example.urlshortener.orchestrator.*;
import com.example.urlshortener.orchestrator.agents.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BROWNFIELD scenario, two parts against the existing codebase. Direct port
 * of scenarios/brownfield_run.py -- see that file's docstring for the full
 * narrative (codebase impact analysis, bounded retry, mid-flow re-plan,
 * then a planted secret caught by the guardrail -> rollback -> fix -> re-plan).
 *
 * Run with: mvn exec:java -Dexec.mainClass=com.example.urlshortener.scenarios.BrownfieldRun
 */
public class BrownfieldRun {

    // ---------------------------------------------------------------
    // PART 1: analytics enhancement
    // ---------------------------------------------------------------

    static final String PART1_REQUEST =
            "Enhance GET /api/urls/{code}/analytics so it must also return a top_referrers breakdown "
                    + "(referrer -> click count, top 5) computed from existing click records.";

    static final String SERVICE_BASELINE = """
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
            """;

    static final String SERVICE_PHASE_A = """
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
                    return new AnalyticsResult(code, link.clickCount(), link.createdAt(), link.expiresAt(), link.isActive(), recent, topReferrers);
                }

                public record ReferrerCount(String referrer, long count) {}

                public record AnalyticsResult(
                        String code, long totalClicks, String createdAt, String expiresAt, boolean isActive,
                        List<ClickRecord> recentClicks, List<ReferrerCount> topReferrers
                ) {}
            """;

    static final String SERVICE_PHASE_AB = """
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
            """;

    static String buildServiceContent(Path projectRoot, boolean includeUniqueCount) {
        Path path = projectRoot.resolve("src/main/java/com/example/urlshortener/app/ShortenerService.java");
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (includeUniqueCount && content.contains("uniqueReferrerCount")) return content;
        if (!includeUniqueCount && content.contains("topReferrers")) return content;

        if (content.contains("topReferrers")) {
            return content.replace(SERVICE_PHASE_A, SERVICE_PHASE_AB);
        }
        return content.replace(SERVICE_BASELINE, includeUniqueCount ? SERVICE_PHASE_AB : SERVICE_PHASE_A);
    }

    static String buildResponseContent(boolean includeUniqueCount) {
        if (!includeUniqueCount) {
            return """
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
                            List<Map<String, Object>> topReferrers
                    ) {
                        public static AnalyticsResponse from(ShortenerService.AnalyticsResult r) {
                            return new AnalyticsResponse(
                                    r.code(), r.totalClicks(), r.createdAt(), r.expiresAt(), r.isActive(),
                                    r.recentClicks().stream().map(ClickEventDto::from).toList(),
                                    r.topReferrers().stream()
                                            .map(tr -> Map.<String, Object>of("referrer", tr.referrer(), "count", tr.count()))
                                            .toList()
                            );
                        }
                    }
                    """;
        }
        return """
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
                """;
    }

    static String buildAnalyticsTest(boolean includeUniqueCount) {
        String extra = includeUniqueCount
                ? "\n        assertThat(body.get(\"unique_referrer_count\").asInt()).isGreaterThan(0);"
                : "";
        return """
                package com.example.urlshortener.app;

                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.ObjectMapper;
                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.boot.test.web.client.TestRestTemplate;
                import org.springframework.boot.test.web.server.LocalServerPort;
                import org.springframework.http.RequestEntity;
                import org.springframework.http.ResponseEntity;
                import org.springframework.jdbc.core.JdbcTemplate;
                import org.springframework.test.context.ActiveProfiles;

                import java.net.URI;

                import static org.assertj.core.api.Assertions.assertThat;

                @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
                @ActiveProfiles("test")
                class AnalyticsEnhancementTest {

                    @LocalServerPort
                    private int port;

                    @Autowired
                    private TestRestTemplate rest;

                    @Autowired
                    private JdbcTemplate jdbc;

                    @Autowired
                    private ShortenerService shortener;

                    private final ObjectMapper mapper = new ObjectMapper();

                    @BeforeEach
                    void cleanDb() {
                        jdbc.execute("DELETE FROM clicks");
                        jdbc.execute("DELETE FROM links");
                        shortener.redirectCache.clear();
                    }

                    private String url(String path) {
                        return "http://localhost:" + port + path;
                    }

                    @Test
                    void analyticsReportsTopReferrers() throws Exception {
                        ResponseEntity<String> created = rest.postForEntity(
                                url("/api/urls"), new com.example.urlshortener.app.dto.CreateLinkRequest("https://example.com", null, null), String.class);
                        String code = mapper.readTree(created.getBody()).get("code").asText();

                        rest.exchange(org.springframework.http.RequestEntity.get(URI.create(url("/" + code)))
                                .header("referer", "https://google.com").build(), Void.class);
                        rest.exchange(org.springframework.http.RequestEntity.get(URI.create(url("/" + code)))
                                .header("referer", "https://google.com").build(), Void.class);
                        rest.exchange(org.springframework.http.RequestEntity.get(URI.create(url("/" + code)))
                                .header("referer", "https://bing.com").build(), Void.class);

                        ResponseEntity<String> resp = rest.getForEntity(url("/api/urls/" + code + "/analytics"), String.class);
                        JsonNode body = mapper.readTree(resp.getBody());
                        assertThat(body.has("top_referrers")).isTrue();
                        assertThat(body.get("top_referrers")).hasSize(2);%s
                    }
                }
                """.formatted(extra);
    }

    private static final AtomicInteger flakyAttempts = new AtomicInteger();

    static Map<String, Object> flakyRunTests(SharedContext ctx, Node node, Path projectRoot) throws Exception {
        if (flakyAttempts.incrementAndGet() == 1) {
            throw new RuntimeException(
                    "simulated transient CI-runner hiccup (disk contention) -- not a real defect; demonstrates "
                            + "the bounded retry policy recovering automatically");
        }
        return TestingAgent.runTests(ctx, node, projectRoot);
    }

    static Graph buildPart1Graph(Path projectRoot) {
        Graph g = new Graph();

        g.add(Node.builder("requirements", "requirements", RequirementsAgent::normalize)
                .name("Normalize the analytics-enhancement request").build());

        g.add(Node.builder("codebase_analysis", "design",
                        (ctx, node) -> CodebaseAnalysisAgent.analyze(ctx, node, "analytics", projectRoot))
                .name("Analyze impact on existing analytics code").dependsOn("requirements").build());

        g.add(Node.builder("design", "design", (ctx, node) -> DesignAgent.propose(
                        ctx, node, "Analytics top_referrers breakdown",
                        List.of(new DesignAgent.Endpoint("GET", "/api/urls/{code}/analytics",
                                "Now also returns top_referrers: top 5 referrers by click count.")),
                        List.of("AnalyticsResponse gains `topReferrers: List<Map<String,Object>>`."),
                        List.of("Referrer aggregation adds one more query per analytics call; acceptable at current scale."),
                        projectRoot, "modify"
                ))
                .name("Design the analytics response extension").dependsOn("codebase_analysis").build());

        g.add(Node.builder("implementation", "implementation", (ctx, node) -> {
                    Map<String, String> files = new LinkedHashMap<>();
                    files.put("src/main/java/com/example/urlshortener/app/ShortenerService.java", buildServiceContent(projectRoot, false));
                    files.put("src/main/java/com/example/urlshortener/app/dto/AnalyticsResponse.java", buildResponseContent(false));
                    files.put("src/test/java/com/example/urlshortener/app/AnalyticsEnhancementTest.java", buildAnalyticsTest(false));
                    return ImplementationAgent.writeFiles(ctx, node, files, projectRoot, List.of());
                })
                .name("Implement topReferrers + tests").dependsOn("design")
                .exitPolicy("no_hardcoded_secrets", Policies::noHardcodedSecrets)
                .exitPolicy("dependency_allowlist", Policies::dependencyAllowlist)
                .compensate(ImplementationAgent::revertFiles)
                .build());

        g.add(Node.builder("testing", "testing", (ctx, node) -> flakyRunTests(ctx, node, projectRoot))
                .name("Run mvn test (flaky first attempt, by design)").dependsOn("implementation")
                .exitPolicy("tests_must_pass", Policies::testsMustPass)
                .retry(RetryPolicy.of(2, 500))
                .build());

        g.add(Node.builder("docs", "docs", (ctx, node) -> DocsAgent.writeChangeDoc(
                        ctx, node, "design", projectRoot, "docs/API_CHANGELOG.md"))
                .name("Write API changelog entry").dependsOn("implementation").build());

        g.add(Node.builder("release", "release", (ctx, node) -> ReleaseAgent.prepareRelease(
                        ctx, node, projectRoot, "runs/" + ctx.runId + "/RELEASE_NOTES_part1.md"))
                .name("Prepare release (human-approved)").dependsOn("testing", "docs")
                .requiresApproval(true).parallelOk(false)
                .entryPolicy("release_requires_clean_policy_history", (ctx, node, r) -> Policies.releaseGate(g).check(ctx, node, r))
                .build());

        return g;
    }

    static void runPart1() {
        String runId = Common.newRunId("brownfield-analytics-java");
        Path outDir = Common.runDir(runId);
        SharedContext ctx = new SharedContext(runId, "brownfield: analytics top_referrers (+ re-plan) [Java]", PART1_REQUEST);
        Graph graph = buildPart1Graph(Common.PROJECT_ROOT);
        EventLog eventLog = new EventLog(runId, outDir);
        Executor executor = new Executor(graph, ctx, eventLog, new AutoApprover(graph), true);

        System.out.println("--- brownfield part 1, initial run ---");
        RunResult result = executor.run();
        Common.printSummary(result);

        System.out.println("\n--- simulated requirement change: also report unique_referrer_count ---");
        ctx.recordDecision(
                "requirements", "requirements",
                "Stakeholder added a follow-up acceptance criterion after reviewing the first cut.",
                "Also report unique_referrer_count alongside top_referrers."
        );
        Node designNode = graph.nodes.get("design");
        graph.nodes.put("design", Node.builder("design", "design", (ctx2, node) -> DesignAgent.propose(
                        ctx2, node, "Analytics top_referrers breakdown (+ unique_referrer_count)",
                        List.of(new DesignAgent.Endpoint("GET", "/api/urls/{code}/analytics",
                                "Now also returns top_referrers and unique_referrer_count.")),
                        List.of(
                                "AnalyticsResponse gains `topReferrers: List<Map<String,Object>>`.",
                                "AnalyticsResponse gains `uniqueReferrerCount: int`."
                        ),
                        List.of("Referrer aggregation adds one more query per analytics call; acceptable at current scale."),
                        Common.PROJECT_ROOT, "modify"
                ))
                .name(designNode.name).dependsOn("codebase_analysis").build());

        Node implNode = graph.nodes.get("implementation");
        graph.nodes.put("implementation", Node.builder("implementation", "implementation", (ctx2, node) -> {
                    Map<String, String> files = new LinkedHashMap<>();
                    files.put("src/main/java/com/example/urlshortener/app/ShortenerService.java", buildServiceContent(Common.PROJECT_ROOT, true));
                    files.put("src/main/java/com/example/urlshortener/app/dto/AnalyticsResponse.java", buildResponseContent(true));
                    files.put("src/test/java/com/example/urlshortener/app/AnalyticsEnhancementTest.java", buildAnalyticsTest(true));
                    return ImplementationAgent.writeFiles(ctx2, node, files, Common.PROJECT_ROOT, List.of());
                })
                .name(implNode.name).dependsOn("design")
                .exitPolicy("no_hardcoded_secrets", Policies::noHardcodedSecrets)
                .exitPolicy("dependency_allowlist", Policies::dependencyAllowlist)
                .compensate(ImplementationAgent::revertFiles)
                .build());

        graph.markStale("design");

        Executor executor2 = new Executor(graph, ctx, eventLog, new AutoApprover(graph), true);
        RunResult result2 = executor2.run();
        eventLog.close();
        Common.writeReport(result2, outDir.resolve("REPORT.md"));
        Common.printSummary(result2);
        System.out.println("\nFull report: runs/" + runId + "/REPORT.md");
    }

    // ---------------------------------------------------------------
    // PART 2: security guardrail catches a defect -> rollback -> fix -> re-plan
    // ---------------------------------------------------------------

    static final String PART2_REQUEST = "Add a structured audit-log class (AuditLog.java) that records admin actions on links.";

    static final String BAD_AUDIT_LOG = """
            package com.example.urlshortener.app;

            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;

            /** Structured audit logging for admin actions on links. */
            public final class AuditLog {
                private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

                // TODO: wire this up to the real notification webhook
                private static final String apiKey = "AKIAABCDEFGHIJKLMNOP";

                private AuditLog() {}

                public static void record(String action, String code, String actor) {
                    log.info("AUDIT action={} code={} actor={}", action, code, actor);
                }
            }
            """;

    static final String FIXED_AUDIT_LOG = """
            package com.example.urlshortener.app;

            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;

            /** Structured audit logging for admin actions on links. */
            public final class AuditLog {
                private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

                // Webhook credentials are injected via environment, never hardcoded.
                private static final String WEBHOOK_TOKEN_ENV_VAR = "URLSHORT_AUDIT_WEBHOOK_TOKEN";

                private AuditLog() {}

                public static void record(String action, String code, String actor) {
                    log.info("AUDIT action={} code={} actor={}", action, code, actor);
                }
            }
            """;

    static Graph buildPart2Graph(Path projectRoot) {
        Graph g = new Graph();
        g.add(Node.builder("requirements", "requirements", RequirementsAgent::normalize)
                .name("Normalize the audit-log request").build());
        g.add(Node.builder("implementation", "implementation", (ctx, node) -> ImplementationAgent.writeFiles(
                        ctx, node, Map.of("src/main/java/com/example/urlshortener/app/AuditLog.java", BAD_AUDIT_LOG),
                        projectRoot, List.of()))
                .name("Implement AuditLog.java").dependsOn("requirements")
                .exitPolicy("no_hardcoded_secrets", Policies::noHardcodedSecrets)
                .exitPolicy("dependency_allowlist", Policies::dependencyAllowlist)
                .compensate(ImplementationAgent::revertFiles)
                .build());
        g.add(Node.builder("testing", "testing", (ctx, node) -> TestingAgent.runTests(ctx, node, projectRoot))
                .name("Run mvn test").dependsOn("implementation")
                .exitPolicy("tests_must_pass", Policies::testsMustPass)
                .build());
        g.add(Node.builder("release", "release", (ctx, node) -> ReleaseAgent.prepareRelease(
                        ctx, node, projectRoot, "runs/" + ctx.runId + "/RELEASE_NOTES_part2.md"))
                .name("Prepare release (human-approved)").dependsOn("testing")
                .requiresApproval(true).parallelOk(false)
                .entryPolicy("release_requires_clean_policy_history", (ctx, node, r) -> Policies.releaseGate(g).check(ctx, node, r))
                .build());
        return g;
    }

    static void runPart2() {
        String runId = Common.newRunId("brownfield-security-incident-java");
        Path outDir = Common.runDir(runId);
        SharedContext ctx = new SharedContext(runId, "brownfield: audit log (security guardrail + rollback + recovery) [Java]", PART2_REQUEST);
        Graph graph = buildPart2Graph(Common.PROJECT_ROOT);
        EventLog eventLog = new EventLog(runId, outDir);
        Executor executor = new Executor(graph, ctx, eventLog, new AutoApprover(graph), true);

        System.out.println("\n--- brownfield part 2: introducing a hardcoded-secret defect ---");
        RunResult result = executor.run();
        Common.printSummary(result);
        if (!"rolled_back".equals(result.outcome())) {
            throw new IllegalStateException("expected the secret-scan guardrail to trigger a rollback");
        }
        Path auditPath = Common.PROJECT_ROOT.resolve("src/main/java/com/example/urlshortener/app/AuditLog.java");
        if (Files.exists(auditPath)) {
            try {
                String reverted = Files.readString(auditPath);
                if (reverted.contains("AKIA")) throw new IllegalStateException("rollback should have reverted the injected secret");
                System.out.println("  confirmed: AuditLog.java no longer contains the injected secret after rollback");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            System.out.println("  confirmed: AuditLog.java did not exist before this run and rollback removed it");
        }

        System.out.println("\n--- applying the fix and re-planning from the failed node ---");
        ctx.recordDecision(
                "implementation", "implementation",
                "Root cause identified: hardcoded placeholder credential in AuditLog.java.",
                "Replaced with an environment-variable reference; re-running from the implementation node."
        );
        graph.nodes.put("implementation", Node.builder("implementation", "implementation", (ctx2, node) -> ImplementationAgent.writeFiles(
                        ctx2, node, Map.of("src/main/java/com/example/urlshortener/app/AuditLog.java", FIXED_AUDIT_LOG),
                        Common.PROJECT_ROOT, List.of()))
                .name(graph.nodes.get("implementation").name).dependsOn("requirements")
                .exitPolicy("no_hardcoded_secrets", Policies::noHardcodedSecrets)
                .exitPolicy("dependency_allowlist", Policies::dependencyAllowlist)
                .compensate(ImplementationAgent::revertFiles)
                .build());
        graph.markStale("implementation");

        Executor executor2 = new Executor(graph, ctx, eventLog, new AutoApprover(graph), true);
        RunResult result2 = executor2.run();
        eventLog.close();
        Common.writeReport(result2, outDir.resolve("REPORT.md"));
        Common.printSummary(result2);
        System.out.println("\nFull report: runs/" + runId + "/REPORT.md");
    }

    public static void main(String[] args) {
        runPart1();
        runPart2();
    }
}
