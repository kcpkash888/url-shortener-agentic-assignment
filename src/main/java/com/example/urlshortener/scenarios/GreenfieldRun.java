package com.example.urlshortener.scenarios;

import com.example.urlshortener.orchestrator.*;
import com.example.urlshortener.orchestrator.agents.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * GREENFIELD scenario: add a brand-new capability -- bulk short-link
 * creation -- to the URL shortener via the orchestrator, end to end.
 * Direct port of scenarios/greenfield_run.py, adapted for a compiled
 * language: the implementation agent writes real .java source, and the
 * testing agent's `mvn test` both compiles and tests it as one gate.
 *
 * Run with: mvn exec:java -Dexec.mainClass=com.example.urlshortener.scenarios.GreenfieldRun
 */
public class GreenfieldRun {

    static final String RAW_REQUEST =
            "Add a bulk short-link creation endpoint: POST /api/urls/bulk must accept a list of up to 50 "
                    + "URLs and return one short link (or a per-item error) for each, reusing the existing "
                    + "validation and code-generation logic in ShortenerService.createLink.";

    static String buildBulkCreateItem() {
        return """
                package com.example.urlshortener.app.dto;

                import jakarta.validation.constraints.Max;
                import jakarta.validation.constraints.Min;
                import jakarta.validation.constraints.NotBlank;
                import jakarta.validation.constraints.Pattern;
                import jakarta.validation.constraints.Size;

                public record BulkCreateItem(
                        @NotBlank
                        @Pattern(regexp = "^https?://.+", message = "url must start with http:// or https://")
                        @Size(max = 2048)
                        String url,

                        @Pattern(regexp = "^[A-Za-z0-9_-]{1,32}$")
                        String customAlias,

                        @Min(1) @Max(3650)
                        Integer expiresInDays
                ) {}
                """;
    }

    static String buildBulkCreateRequest() {
        return """
                package com.example.urlshortener.app.dto;

                import jakarta.validation.Valid;
                import jakarta.validation.constraints.NotEmpty;

                import java.util.List;

                public record BulkCreateRequest(
                        @NotEmpty
                        @Valid
                        List<BulkCreateItem> urls
                ) {}
                """;
    }

    static String buildBulkResultItem() {
        return """
                package com.example.urlshortener.app.dto;

                public record BulkResultItem(boolean success, LinkResponse link, String error, String url) {}
                """;
    }

    static String buildBulkCreateResponse() {
        return """
                package com.example.urlshortener.app.dto;

                import java.util.List;

                public record BulkCreateResponse(List<BulkResultItem> results) {}
                """;
    }

    static String buildControllerContent(Path projectRoot) {
        Path path = projectRoot.resolve("src/main/java/com/example/urlshortener/app/UrlController.java");
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (content.contains("/api/urls/bulk")) return content;

        content = content.replace(
                "import com.example.urlshortener.app.dto.AnalyticsResponse;\n"
                        + "import com.example.urlshortener.app.dto.CreateLinkRequest;\n"
                        + "import com.example.urlshortener.app.dto.LinkResponse;\n",
                "import com.example.urlshortener.app.dto.AnalyticsResponse;\n"
                        + "import com.example.urlshortener.app.dto.BulkCreateRequest;\n"
                        + "import com.example.urlshortener.app.dto.BulkCreateResponse;\n"
                        + "import com.example.urlshortener.app.dto.BulkResultItem;\n"
                        + "import com.example.urlshortener.app.dto.CreateLinkRequest;\n"
                        + "import com.example.urlshortener.app.dto.LinkResponse;\n"
        );

        String anchor = """
                    @GetMapping("/{code}")
                    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
                        String target = shortener.resolveLink(code, request.getHeader("referer"), request.getHeader("user-agent"));
                        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, target).build();
                    }
                }
                """;

        String replacement = """
                    @GetMapping("/{code}")
                    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
                        String target = shortener.resolveLink(code, request.getHeader("referer"), request.getHeader("user-agent"));
                        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, target).build();
                    }

                    @PostMapping("/api/urls/bulk")
                    public ResponseEntity<BulkCreateResponse> createUrlsBulk(@jakarta.validation.Valid @RequestBody BulkCreateRequest req) {
                        if (req.urls().size() > 50) {
                            throw new IllegalArgumentException("a bulk request may contain at most 50 urls");
                        }
                        java.util.List<BulkResultItem> results = new java.util.ArrayList<>();
                        for (var item : req.urls()) {
                            try {
                                var link = shortener.createLink(item.url(), item.customAlias(), item.expiresInDays());
                                results.add(new BulkResultItem(true, LinkResponse.from(link, baseHost), null, null));
                            } catch (com.example.urlshortener.app.exceptions.AliasConflictException e) {
                                results.add(new BulkResultItem(false, null, e.getMessage(), item.url()));
                            }
                        }
                        return ResponseEntity.status(HttpStatus.CREATED).body(new BulkCreateResponse(results));
                    }
                }
                """;

        return content.replace(anchor, replacement);
    }

    static String buildExceptionHandlerContent(Path projectRoot) {
        Path path = projectRoot.resolve("src/main/java/com/example/urlshortener/app/GlobalExceptionHandler.java");
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (content.contains("IllegalArgumentException")) return content;

        String anchor = """
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

        String replacement = """
                    @ExceptionHandler(MethodArgumentNotValidException.class)
                    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("detail", e.getBindingResult().getFieldErrors().stream()
                                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                                .toList());
                        return ResponseEntity.status(422).body(body);
                    }

                    @ExceptionHandler(IllegalArgumentException.class)
                    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
                        return ResponseEntity.status(422).body(Map.of("detail", e.getMessage()));
                    }
                }
                """;

        return content.replace(anchor, replacement);
    }

    static String buildBulkTest() {
        return """
                package com.example.urlshortener.app;

                import com.example.urlshortener.app.dto.BulkCreateItem;
                import com.example.urlshortener.app.dto.BulkCreateRequest;
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

                import java.util.List;

                import static org.assertj.core.api.Assertions.assertThat;

                @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
                @ActiveProfiles("test")
                class BulkControllerTest {

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
                    void bulkCreateReturnsLinkPerItem() throws Exception {
                        var req = new BulkCreateRequest(List.of(
                                new BulkCreateItem("https://example.com/a", null, null),
                                new BulkCreateItem("https://example.com/b", null, null)
                        ));
                        ResponseEntity<String> resp = rest.postForEntity(url("/api/urls/bulk"), req, String.class);
                        assertThat(resp.getStatusCode().value()).isEqualTo(201);
                        JsonNode body = mapper.readTree(resp.getBody());
                        assertThat(body.get("results")).hasSize(2);
                        for (JsonNode item : body.get("results")) {
                            assertThat(item.get("success").asBoolean()).isTrue();
                        }
                    }

                    @Test
                    void bulkCreateReportsPartialFailureOnAliasConflict() throws Exception {
                        rest.postForEntity(url("/api/urls"), new com.example.urlshortener.app.dto.CreateLinkRequest("https://example.com", "dup", null), String.class);
                        var req = new BulkCreateRequest(List.of(
                                new BulkCreateItem("https://example.com/x", "dup", null),
                                new BulkCreateItem("https://example.com/y", null, null)
                        ));
                        ResponseEntity<String> resp = rest.postForEntity(url("/api/urls/bulk"), req, String.class);
                        JsonNode body = mapper.readTree(resp.getBody());
                        assertThat(body.get("results").get(0).get("success").asBoolean()).isFalse();
                        assertThat(body.get("results").get(1).get("success").asBoolean()).isTrue();
                    }

                    @Test
                    void bulkCreateRejectsOver50Items() {
                        List<BulkCreateItem> items = new java.util.ArrayList<>();
                        for (int i = 0; i < 51; i++) items.add(new BulkCreateItem("https://example.com", null, null));
                        ResponseEntity<String> resp = rest.postForEntity(url("/api/urls/bulk"), new BulkCreateRequest(items), String.class);
                        assertThat(resp.getStatusCode().value()).isEqualTo(422);
                    }
                }
                """;
    }

    static Graph buildGraph(Path projectRoot) {
        Graph g = new Graph();

        g.add(Node.builder("requirements", "requirements", RequirementsAgent::normalize)
                .name("Normalize the bulk-create request").build());

        g.add(Node.builder("design", "design", (ctx, node) -> DesignAgent.propose(
                        ctx, node, "Bulk short-link creation",
                        List.of(new DesignAgent.Endpoint("POST", "/api/urls/bulk",
                                "Create up to 50 short links in a single request; per-item success/error.")),
                        List.of("No table/schema migration required; reuses the existing `links` table."),
                        List.of(
                                "A large batch is processed synchronously in-request; capped at 50 items to bound "
                                        + "worst-case request latency.",
                                "Partial failure (e.g. one alias conflict in a batch) does not roll back the other "
                                        + "items in the batch -- each item is independent by design."
                        ),
                        projectRoot, "add"
                ))
                .name("Design the bulk-create API contract").dependsOn("requirements").build());

        g.add(Node.builder("implementation", "implementation", (ctx, node) -> {
                    Map<String, String> files = new LinkedHashMap<>();
                    files.put("src/main/java/com/example/urlshortener/app/dto/BulkCreateItem.java", buildBulkCreateItem());
                    files.put("src/main/java/com/example/urlshortener/app/dto/BulkCreateRequest.java", buildBulkCreateRequest());
                    files.put("src/main/java/com/example/urlshortener/app/dto/BulkResultItem.java", buildBulkResultItem());
                    files.put("src/main/java/com/example/urlshortener/app/dto/BulkCreateResponse.java", buildBulkCreateResponse());
                    files.put("src/main/java/com/example/urlshortener/app/UrlController.java", buildControllerContent(projectRoot));
                    files.put("src/main/java/com/example/urlshortener/app/GlobalExceptionHandler.java", buildExceptionHandlerContent(projectRoot));
                    files.put("src/test/java/com/example/urlshortener/app/BulkControllerTest.java", buildBulkTest());
                    return ImplementationAgent.writeFiles(ctx, node, files, projectRoot, List.of());
                })
                .name("Implement endpoint, DTOs, and tests").dependsOn("design")
                .exitPolicy("no_hardcoded_secrets", Policies::noHardcodedSecrets)
                .exitPolicy("dependency_allowlist", Policies::dependencyAllowlist)
                .compensate(ImplementationAgent::revertFiles)
                .build());

        g.add(Node.builder("testing", "testing", (ctx, node) -> TestingAgent.runTests(ctx, node, projectRoot))
                .name("Run mvn test (compile + test)").dependsOn("implementation")
                .exitPolicy("tests_must_pass", Policies::testsMustPass)
                .retry(RetryPolicy.of(2, 500))
                .build());

        g.add(Node.builder("docs", "docs", (ctx, node) -> DocsAgent.writeChangeDoc(
                        ctx, node, "design", projectRoot, "docs/API_CHANGELOG.md"))
                .name("Write API changelog entry").dependsOn("implementation").build());

        g.add(Node.builder("release", "release", (ctx, node) -> ReleaseAgent.prepareRelease(
                        ctx, node, projectRoot, "runs/" + ctx.runId + "/RELEASE_NOTES.md"))
                .name("Prepare release (human-approved)").dependsOn("testing", "docs")
                .requiresApproval(true).parallelOk(false)
                .entryPolicy("release_requires_clean_policy_history", (ctx, node, r) -> Policies.releaseGate(g).check(ctx, node, r))
                .build());

        return g;
    }

    public static void main(String[] args) {
        String runId = Common.newRunId("greenfield-java");
        Path outDir = Common.runDir(runId);
        SharedContext ctx = new SharedContext(runId, "greenfield: bulk short-link creation (Java)", RAW_REQUEST);
        Graph graph = buildGraph(Common.PROJECT_ROOT);
        EventLog eventLog = new EventLog(runId, outDir);
        Executor executor = new Executor(graph, ctx, eventLog, new AutoApprover(graph), true);

        RunResult result = executor.run();
        eventLog.close();
        Common.writeReport(result, outDir.resolve("REPORT.md"));
        Common.printSummary(result);
        System.out.println("\nFull report: runs/" + runId + "/REPORT.md");
        System.out.println("Event log:   runs/" + runId + "/events.jsonl");
    }
}
