package com.example.urlshortener.scenarios;

import com.example.urlshortener.orchestrator.*;
import com.example.urlshortener.orchestrator.agents.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AMBIGUOUS scenario: a raw, underspecified stakeholder ask with no
 * acceptance criteria. Direct port of scenarios/ambiguous_run.py -- see
 * that file's docstring for the full narrative (ambiguity detection,
 * scored candidate interpretations, then real input-validation hardening).
 *
 * Run with: mvn exec:java -Dexec.mainClass=com.example.urlshortener.scenarios.AmbiguousRun
 */
public class AmbiguousRun {

    static final String RAW_REQUEST = "Can we make the short links a bit safer? A couple of users raised concerns.";

    static final String UNSAFE_TARGET_EXCEPTION = """
            package com.example.urlshortener.app.exceptions;

            public class UnsafeTargetException extends RuntimeException {
                public UnsafeTargetException(String message) {
                    super(message);
                }
            }
            """;

    static final String RESERVED_ALIAS_EXCEPTION = """
            package com.example.urlshortener.app.exceptions;

            public class ReservedAliasException extends RuntimeException {
                public ReservedAliasException(String message) {
                    super(message);
                }
            }
            """;

    static final String SERVICE_ANCHOR = """
                public Link createLink(String url, String customAlias, Integer expiresInDays) {
                    String code;
                    boolean isCustom;
            """;

    static final String SERVICE_REPLACEMENT = """
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
            """;

    static String buildServiceContent(Path projectRoot) {
        Path path = projectRoot.resolve("src/main/java/com/example/urlshortener/app/ShortenerService.java");
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (content.contains("BLOCKED_DOMAINS")) return content;
        return content.replace(SERVICE_ANCHOR, SERVICE_REPLACEMENT);
    }

    static final String HANDLER_ANCHOR = """
                @ExceptionHandler(IllegalArgumentException.class)
                public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
                    return ResponseEntity.status(422).body(Map.of("detail", e.getMessage()));
                }
            }
            """;

    static final String HANDLER_REPLACEMENT = """
                @ExceptionHandler(IllegalArgumentException.class)
                public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
                    return ResponseEntity.status(422).body(Map.of("detail", e.getMessage()));
                }

                @ExceptionHandler({
                        com.example.urlshortener.app.exceptions.UnsafeTargetException.class,
                        com.example.urlshortener.app.exceptions.ReservedAliasException.class
                })
                public ResponseEntity<Map<String, String>> handleUnsafe(RuntimeException e) {
                    return ResponseEntity.status(422).body(Map.of("detail", e.getMessage()));
                }
            }
            """;

    static String buildHandlerContent(Path projectRoot) {
        Path path = projectRoot.resolve("src/main/java/com/example/urlshortener/app/GlobalExceptionHandler.java");
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (content.contains("UnsafeTargetException")) return content;
        return content.replace(HANDLER_ANCHOR, HANDLER_REPLACEMENT);
    }

    static final String BULK_CATCH_ANCHOR = """
                        } catch (com.example.urlshortener.app.exceptions.AliasConflictException e) {
                            results.add(new BulkResultItem(false, null, e.getMessage(), item.url()));
                        }
            """;

    static final String BULK_CATCH_REPLACEMENT = """
                        } catch (com.example.urlshortener.app.exceptions.AliasConflictException
                                | com.example.urlshortener.app.exceptions.UnsafeTargetException
                                | com.example.urlshortener.app.exceptions.ReservedAliasException e) {
                            results.add(new BulkResultItem(false, null, e.getMessage(), item.url()));
                        }
            """;

    static String buildControllerContent(Path projectRoot) {
        Path path = projectRoot.resolve("src/main/java/com/example/urlshortener/app/UrlController.java");
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!content.contains("/api/urls/bulk") || content.contains("UnsafeTargetException")) return content;
        return content.replace(BULK_CATCH_ANCHOR, BULK_CATCH_REPLACEMENT);
    }

    static String buildSafetyTest() {
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
                import org.springframework.http.ResponseEntity;
                import org.springframework.jdbc.core.JdbcTemplate;
                import org.springframework.test.context.ActiveProfiles;

                import static org.assertj.core.api.Assertions.assertThat;

                @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
                @ActiveProfiles("test")
                class SafetyValidationTest {

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
                    void blockedDomainIsRejected() {
                        ResponseEntity<String> resp = rest.postForEntity(
                                url("/api/urls"),
                                new com.example.urlshortener.app.dto.CreateLinkRequest("https://malicious-example.test/phish", null, null),
                                String.class);
                        assertThat(resp.getStatusCode().value()).isEqualTo(422);
                    }

                    @Test
                    void reservedAliasIsRejected() {
                        ResponseEntity<String> resp = rest.postForEntity(
                                url("/api/urls"),
                                new com.example.urlshortener.app.dto.CreateLinkRequest("https://example.com", "api", null),
                                String.class);
                        assertThat(resp.getStatusCode().value()).isEqualTo(422);
                    }

                    @Test
                    void safeUrlAndAliasStillWork() {
                        ResponseEntity<String> resp = rest.postForEntity(
                                url("/api/urls"),
                                new com.example.urlshortener.app.dto.CreateLinkRequest("https://example.com/fine", "my-brand-2", null),
                                String.class);
                        assertThat(resp.getStatusCode().value()).isEqualTo(201);
                    }

                    @Test
                    void bulkReportsUnsafeItemAsPerItemErrorNotHardFailure() throws Exception {
                        var req = new com.example.urlshortener.app.dto.BulkCreateRequest(java.util.List.of(
                                new com.example.urlshortener.app.dto.BulkCreateItem("https://malicious-example.test/x", null, null),
                                new com.example.urlshortener.app.dto.BulkCreateItem("https://example.com/y", null, null)
                        ));
                        ResponseEntity<String> resp = rest.postForEntity(url("/api/urls/bulk"), req, String.class);
                        assertThat(resp.getStatusCode().value()).isEqualTo(201);
                        JsonNode body = mapper.readTree(resp.getBody());
                        assertThat(body.get("results").get(0).get("success").asBoolean()).isFalse();
                        assertThat(body.get("results").get(1).get("success").asBoolean()).isTrue();
                    }
                }
                """;
    }

    static Graph buildGraph(Path projectRoot) {
        Graph g = new Graph();

        g.add(Node.builder("requirements", "requirements", RequirementsAgent::normalize)
                .name("Detect ambiguity and normalize the safety request").build());

        g.add(Node.builder("design", "design", (ctx, node) -> {
                    @SuppressWarnings("unchecked")
                    String featureName = (String) ctx.getOutput("requirements").get("chosen_interpretation");
                    return DesignAgent.propose(
                            ctx, node, featureName,
                            List.of(
                                    new DesignAgent.Endpoint("POST", "/api/urls",
                                            "Rejects blocklisted target domains and reserved short-code aliases with 422."),
                                    new DesignAgent.Endpoint("POST", "/api/urls/bulk",
                                            "Applies the same safety checks per item, consistent with the single-create endpoint.")
                            ),
                            List.of("No schema/table change; validation happens in ShortenerService.createLink."),
                            List.of(
                                    "The domain blocklist is static and requires manual maintenance -- a live "
                                            + "threat-intel feed was considered and explicitly rejected for this change "
                                            + "(see requirements decision lineage) as disproportionate effort for the ask as stated.",
                                    "Reserved-alias list is hardcoded; adding a new reserved word later requires a "
                                            + "code change, not just configuration."
                            ),
                            projectRoot, "modify"
                    );
                })
                .name("Design the input-validation hardening").dependsOn("requirements").build());

        g.add(Node.builder("implementation", "implementation", (ctx, node) -> {
                    Map<String, String> files = new LinkedHashMap<>();
                    files.put("src/main/java/com/example/urlshortener/app/exceptions/UnsafeTargetException.java", UNSAFE_TARGET_EXCEPTION);
                    files.put("src/main/java/com/example/urlshortener/app/exceptions/ReservedAliasException.java", RESERVED_ALIAS_EXCEPTION);
                    files.put("src/main/java/com/example/urlshortener/app/ShortenerService.java", buildServiceContent(projectRoot));
                    files.put("src/main/java/com/example/urlshortener/app/GlobalExceptionHandler.java", buildHandlerContent(projectRoot));
                    files.put("src/main/java/com/example/urlshortener/app/UrlController.java", buildControllerContent(projectRoot));
                    files.put("src/test/java/com/example/urlshortener/app/SafetyValidationTest.java", buildSafetyTest());
                    return ImplementationAgent.writeFiles(ctx, node, files, projectRoot, List.of());
                })
                .name("Implement validation + tests").dependsOn("design")
                .exitPolicy("no_hardcoded_secrets", Policies::noHardcodedSecrets)
                .exitPolicy("dependency_allowlist", Policies::dependencyAllowlist)
                .compensate(ImplementationAgent::revertFiles)
                .build());

        g.add(Node.builder("testing", "testing", (ctx, node) -> TestingAgent.runTests(ctx, node, projectRoot))
                .name("Run mvn test (compile + test)").dependsOn("implementation")
                .exitPolicy("tests_must_pass", Policies::testsMustPass)
                .build());

        g.add(Node.builder("docs", "docs", (ctx, node) -> DocsAgent.writeChangeDoc(
                        ctx, node, "design", projectRoot, "docs/API_CHANGELOG.md"))
                .name("Write API changelog entry").dependsOn("implementation").build());

        g.add(Node.builder("release", "release", (ctx, node) -> ReleaseAgent.prepareRelease(
                        ctx, node, projectRoot, "runs/" + ctx.runId + "/RELEASE_NOTES.md"))
                .name("Prepare release (human-approved -- safety-relevant change)").dependsOn("testing", "docs")
                .requiresApproval(true).parallelOk(false)
                .entryPolicy("release_requires_clean_policy_history", (ctx, node, r) -> Policies.releaseGate(g).check(ctx, node, r))
                .build());

        return g;
    }

    public static void main(String[] args) {
        String runId = Common.newRunId("ambiguous-java");
        Path outDir = Common.runDir(runId);
        SharedContext ctx = new SharedContext(runId, "ambiguous: 'make links safer' [Java]", RAW_REQUEST);
        Graph graph = buildGraph(Common.PROJECT_ROOT);
        EventLog eventLog = new EventLog(runId, outDir);
        Executor executor = new Executor(graph, ctx, eventLog, new AutoApprover(graph), true);

        RunResult result = executor.run();
        eventLog.close();
        Common.writeReport(result, outDir.resolve("REPORT.md"));
        Common.printSummary(result);

        Map<String, Object> reqOutput = ctx.getOutput("requirements");
        System.out.println("\n--- requirements agent output ---");
        System.out.println("  ambiguity_detected: " + reqOutput.get("ambiguity_detected"));
        System.out.println("  chosen_interpretation: " + reqOutput.get("chosen_interpretation"));
        System.out.println("  rejected_interpretations: " + reqOutput.get("rejected_interpretations"));
        System.out.println("\nFull report: runs/" + runId + "/REPORT.md");
    }
}
