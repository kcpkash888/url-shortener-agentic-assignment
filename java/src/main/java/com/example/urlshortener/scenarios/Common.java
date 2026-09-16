package com.example.urlshortener.scenarios;

import com.example.urlshortener.orchestrator.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared plumbing for the three demo scenarios: run-id/output-dir setup and
 * a human-readable Markdown report writer built from a RunResult. Assumes
 * the JVM's working directory is the Maven project root (true when launched
 * via `mvn exec:java`, the documented way to run these). */
public final class Common {
    private Common() {}

    public static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();
    public static final Path RUNS_DIR = PROJECT_ROOT.resolve("runs");

    public static String newRunId(String scenario) {
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").format(
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        return scenario + "-" + ts + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    public static Path runDir(String runId) {
        Path d = RUNS_DIR.resolve(runId);
        try {
            Files.createDirectories(d);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return d;
    }

    public static void writeReport(RunResult result, Path path) {
        SharedContext ctx = result.ctx();
        Graph graph = result.graph();
        RunMetrics metrics = result.metrics();
        StringBuilder sb = new StringBuilder();
        sb.append("# Orchestration run report: ").append(ctx.scenario).append("\n\n");
        sb.append("- run id: `").append(ctx.runId).append("`\n");
        sb.append("- outcome: **").append(result.outcome()).append("**\n");
        sb.append("- original request: ").append(quoted(ctx.rawRequest)).append("\n\n");
        sb.append("## Node status\n\n");
        sb.append("| node | stage | status | attempts | error |\n|---|---|---|---|---|\n");
        for (String nid : graph.topologicalOrder()) {
            Node n = graph.nodes.get(nid);
            String err = n.lastError == null ? "" : n.lastError.replace("|", "/");
            if (err.length() > 120) err = err.substring(0, 120);
            sb.append("| ").append(n.id).append(" | ").append(n.stage).append(" | ").append(n.status)
                    .append(" | ").append(n.attempts).append(" | ").append(err).append(" |\n");
        }

        sb.append("\n## Reliability metrics\n\n```json\n");
        try {
            ObjectMapper mapper = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
            sb.append(mapper.writeValueAsString(metrics.toMap()));
        } catch (Exception e) {
            sb.append("{}");
        }
        sb.append("\n```\n\n");

        sb.append("## Decision lineage\n\n");
        for (Decision d : ctx.decisions) {
            sb.append("- **[").append(d.stage()).append("/").append(d.nodeId()).append("]** ").append(d.summary()).append("\n");
            sb.append("  - rationale: ").append(d.rationale()).append("\n");
            for (String alt : d.alternativesConsidered()) sb.append("  - rejected alternative: ").append(alt).append("\n");
        }

        sb.append("\n## Approvals\n\n");
        for (Map<String, Object> a : ctx.approvals) {
            sb.append("- `").append(a.get("node_id")).append("`: ")
                    .append(Boolean.TRUE.equals(a.get("approved")) ? "APPROVED" : "WITHHELD")
                    .append(" by ").append(a.get("approver")).append(" -- ").append(a.get("rationale")).append("\n");
        }

        sb.append("\n## Policy violations\n\n");
        if (ctx.policyViolations.isEmpty()) {
            sb.append("- none\n");
        } else {
            for (Map<String, Object> v : ctx.policyViolations) {
                sb.append("- `").append(v.get("node_id")).append("` / ").append(v.get("policy")).append(": ").append(v.get("message")).append("\n");
            }
        }

        sb.append("\n## Artifacts touched\n\n");
        for (String a : ctx.artifacts) sb.append("- ").append(a).append("\n");

        try {
            Files.writeString(path, sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String quoted(String s) {
        return "'" + s + "'";
    }

    public static void printSummary(RunResult result) {
        Map<String, Object> m = result.metrics().toMap();
        System.out.println("\n=== " + result.ctx().scenario + " :: outcome=" + result.outcome() + " ===");
        for (String nid : result.graph().topologicalOrder()) {
            Node n = result.graph().nodes.get(nid);
            System.out.printf("  [%-10s] %-28s stage=%-15s attempts=%d%n", n.status, n.id, n.stage, n.attempts);
        }
        System.out.println("  success_rate=" + m.get("success_rate") + " retries=" + m.get("retry_count")
                + " rollbacks=" + m.get("rollback_count") + " safe_stops=" + m.get("safe_stops")
                + " replans=" + m.get("replans") + " e2e_latency_ms=" + m.get("end_to_end_latency_ms"));
    }
}
