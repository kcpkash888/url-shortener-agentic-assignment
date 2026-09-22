package com.example.urlshortener.orchestrator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the orchestration engine itself, using small synthetic
 * graphs -- independent of the URL shortener app. Direct port of
 * tests/test_orchestrator.py.
 */
class ExecutorTest {

    private SharedContext ctx() {
        return new SharedContext("test-run", "test", "a test request");
    }

    private EventLog eventLog(Path tmp) {
        return new EventLog("test-run", tmp);
    }

    // -- Graph ----------------------------------------------------------

    @Test
    void graphDetectsCycle() {
        Graph g = new Graph();
        g.add(Node.builder("a", "x", (c, n) -> Map.of()).dependsOn("b").build());
        g.add(Node.builder("b", "x", (c, n) -> Map.of()).dependsOn("a").build());
        assertThatThrownBy(g::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void graphRejectsUnknownDependency() {
        Graph g = new Graph();
        g.add(Node.builder("a", "x", (c, n) -> Map.of()).dependsOn("missing").build());
        assertThatThrownBy(g::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readyNodesOnlyReturnsNodesWhoseDepsSucceeded() {
        Graph g = new Graph();
        g.add(Node.builder("a", "x", (c, n) -> Map.of()).build());
        g.add(Node.builder("b", "x", (c, n) -> Map.of()).dependsOn("a").build());
        assertThat(g.readyNodes().stream().map(n -> n.id).toList()).containsExactly("a");
        g.nodes.get("a").status = NodeStatus.SUCCEEDED;
        assertThat(g.readyNodes().stream().map(n -> n.id).toList()).containsExactly("b");
    }

    @Test
    void markStalePropagatesOnlyToDescendants() {
        Graph g = new Graph();
        g.add(Node.builder("a", "x", (c, n) -> Map.of()).build());
        g.add(Node.builder("b", "x", (c, n) -> Map.of()).dependsOn("a").build());
        g.add(Node.builder("c", "x", (c, n) -> Map.of()).build()); // unrelated branch
        for (String id : List.of("a", "b", "c")) g.nodes.get(id).status = NodeStatus.SUCCEEDED;

        List<String> affected = g.markStale("a");

        assertThat(affected).containsExactlyInAnyOrder("a", "b");
        assertThat(g.nodes.get("a").status).isEqualTo(NodeStatus.STALE);
        assertThat(g.nodes.get("b").status).isEqualTo(NodeStatus.STALE);
        assertThat(g.nodes.get("c").status).isEqualTo(NodeStatus.SUCCEEDED); // untouched
    }

    // -- Executor: happy path, retries, fallback -------------------------

    @Test
    void executorRunsLinearGraphToCompletion(@TempDir Path tmp) {
        Graph g = new Graph();
        g.add(Node.builder("a", "x", (c, n) -> Map.of("v", 1)).build());
        g.add(Node.builder("b", "x", (c, n) -> Map.of("v", (Integer) c.getOutput("a").get("v") + 1))
                .dependsOn("a").build());
        SharedContext ctx = ctx();
        RunResult result = new Executor(g, ctx, eventLog(tmp), new AutoApprover(g), true).run();
        assertThat(result.outcome()).isEqualTo("completed");
        assertThat(g.nodes.get("a").status).isEqualTo(NodeStatus.SUCCEEDED);
        assertThat(g.nodes.get("b").status).isEqualTo(NodeStatus.SUCCEEDED);
        assertThat(ctx.getOutput("b").get("v")).isEqualTo(2);
    }

    @Test
    void executorRetriesTransientExceptionThenSucceeds(@TempDir Path tmp) {
        AtomicInteger attempts = new AtomicInteger();
        Graph g = new Graph();
        g.add(Node.builder("a", "x", (c, n) -> {
            if (attempts.incrementAndGet() == 1) throw new RuntimeException("transient");
            return Map.of("ok", true);
        }).retry(RetryPolicy.of(2, 0)).build());
        RunResult result = new Executor(g, ctx(), eventLog(tmp), new AutoApprover(g), true).run();
        assertThat(result.outcome()).isEqualTo("completed");
        assertThat(g.nodes.get("a").attempts).isEqualTo(2);
        assertThat(result.metrics().retryCount).isEqualTo(1);
    }

    @Test
    void executorExhaustsRetriesThenUsesFallback(@TempDir Path tmp) {
        Graph g = new Graph();
        g.add(Node.builder("a", "x", (c, n) -> { throw new RuntimeException("always fails"); })
                .fallback((c, n) -> Map.of("via", "fallback"))
                .retry(RetryPolicy.of(1, 0))
                .build());
        SharedContext ctx = ctx();
        RunResult result = new Executor(g, ctx, eventLog(tmp), new AutoApprover(g), true).run();
        assertThat(result.outcome()).isEqualTo("completed");
        assertThat(g.nodes.get("a").status).isEqualTo(NodeStatus.SUCCEEDED);
        assertThat(ctx.getOutput("a")).isEqualTo(Map.of("via", "fallback"));
    }

    // -- Executor: policy gates -------------------------------------------

    @Test
    void entryPolicyFailureBlocksNodeWithoutRunningIt(@TempDir Path tmp) {
        List<Boolean> ran = new ArrayList<>();
        Graph g = new Graph();
        g.add(Node.builder("a", "x", (c, n) -> { ran.add(true); return Map.of(); })
                .entryPolicy("blocked", (c, n, r) -> PolicyResult.fail("blocked by policy"))
                .build());
        RunResult result = new Executor(g, ctx(), eventLog(tmp), new AutoApprover(g), false).run();
        assertThat(result.outcome()).isEqualTo("safe_stopped");
        assertThat(g.nodes.get("a").status).isEqualTo(NodeStatus.FAILED);
        assertThat(ran).isEmpty();
    }

    @Test
    void exitPolicyFailureFailsNodeAfterItRan(@TempDir Path tmp) {
        Graph g = new Graph();
        g.add(Node.builder("a", "x", (c, n) -> Map.of("passed", false))
                .exitPolicy("must_pass", (c, n, r) -> PolicyResult.fail("must pass"))
                .retry(RetryPolicy.of(1, 0))
                .build());
        SharedContext ctx = ctx();
        new Executor(g, ctx, eventLog(tmp), new AutoApprover(g), false).run();
        assertThat(g.nodes.get("a").status).isEqualTo(NodeStatus.FAILED);
        assertThat(ctx.policyViolations).hasSize(1);
    }

    @Test
    void approvalWithheldBlocksNode(@TempDir Path tmp) {
        Graph g = new Graph();
        g.add(Node.builder("a", "release", (c, n) -> Map.of()).requiresApproval(true).build());
        SharedContext ctx = ctx();
        new Executor(g, ctx, eventLog(tmp), new AlwaysReject("manual hold"), false).run();
        assertThat(g.nodes.get("a").status).isEqualTo(NodeStatus.FAILED);
        assertThat(g.nodes.get("a").lastError).contains("manual hold");
        assertThat((Boolean) ctx.approvals.get(0).get("approved")).isFalse();
    }

    // -- Executor: rollback vs safe-stop -----------------------------------

    @Test
    void rollbackCompensatesSucceededNodesAndTheFailingNodeItself(@TempDir Path tmp) {
        List<String> compensated = new ArrayList<>();
        Graph g = new Graph();
        g.add(Node.builder("a", "x", (c, n) -> Map.of("x", 1)).compensate((c, n) -> compensated.add("a")).build());
        g.add(Node.builder("b", "x", (c, n) -> Map.of("x", 2)) // runs successfully...
                .dependsOn("a")
                .exitPolicy("reject_anyway", (c, n, r) -> PolicyResult.fail("reject anyway")) // ...but its own gate rejects it
                .compensate((c, n) -> compensated.add("b"))
                .retry(RetryPolicy.of(1, 0))
                .build());
        g.add(Node.builder("c", "x", (c, n) -> Map.of()).dependsOn("b").build()); // never reached

        RunResult result = new Executor(g, ctx(), eventLog(tmp), new AutoApprover(g), true).run();

        assertThat(result.outcome()).isEqualTo("rolled_back");
        assertThat(g.nodes.get("a").status).isEqualTo(NodeStatus.ROLLED_BACK);
        assertThat(g.nodes.get("b").status).isEqualTo(NodeStatus.FAILED); // stays FAILED, but was still compensated
        assertThat(g.nodes.get("c").status).isEqualTo(NodeStatus.SKIPPED);
        assertThat(compensated).containsExactly("b", "a"); // reverse topological order
    }

    @Test
    void safeStopSkipsRemainingAndDoesNotCompensate(@TempDir Path tmp) {
        List<String> compensated = new ArrayList<>();
        Graph g = new Graph();
        g.add(Node.builder("a", "x", (c, n) -> Map.of()).compensate((c, n) -> compensated.add("a")).build());
        g.add(Node.builder("b", "x", (c, n) -> { throw new RuntimeException("boom"); })
                .dependsOn("a").retry(RetryPolicy.of(1, 0)).build());
        g.add(Node.builder("c", "x", (c, n) -> Map.of()).dependsOn("b").build());

        RunResult result = new Executor(g, ctx(), eventLog(tmp), new AutoApprover(g), false).run();

        assertThat(result.outcome()).isEqualTo("safe_stopped");
        assertThat(g.nodes.get("a").status).isEqualTo(NodeStatus.SUCCEEDED); // last-known-good
        assertThat(g.nodes.get("c").status).isEqualTo(NodeStatus.SKIPPED);
        assertThat(compensated).isEmpty();
    }

    // -- Re-planning ---------------------------------------------------------

    @Test
    void replanOnlyReexecutesTheStaleSubgraph(@TempDir Path tmp) {
        Map<String, Integer> calls = new java.util.concurrent.ConcurrentHashMap<>(Map.of("a", 0, "b", 0));
        Graph g = new Graph();
        g.add(Node.builder("a", "x", (c, n) -> { calls.merge("a", 1, Integer::sum); return Map.of("v", 1); }).build());
        g.add(Node.builder("b", "x", (c, n) -> {
            calls.merge("b", 1, Integer::sum);
            return Map.of("v", (Integer) c.getOutput("a").get("v") + 1);
        }).dependsOn("a").build());
        SharedContext ctx = ctx();
        EventLog log = eventLog(tmp);
        new Executor(g, ctx, log, new AutoApprover(g), true).run();
        assertThat(calls).isEqualTo(Map.of("a", 1, "b", 1));

        g.markStale("b");
        RunResult result = new Executor(g, ctx, log, new AutoApprover(g), true).run();

        assertThat(calls).isEqualTo(Map.of("a", 1, "b", 2)); // 'a' was not re-run
        assertThat(result.outcome()).isEqualTo("completed");
    }

    // -- Metrics --------------------------------------------------------------

    @Test
    void metricsReflectSuccessRateAndMttrAcrossFailureAndRecovery(@TempDir Path tmp) {
        AtomicInteger attempt = new AtomicInteger();
        Graph g = new Graph();
        g.add(Node.builder("a", "x", (c, n) -> {
            if (attempt.incrementAndGet() == 1) throw new RuntimeException("boom");
            return Map.of("ok", true);
        }).retry(RetryPolicy.of(1, 0)).build());
        SharedContext ctx = ctx();
        EventLog log = eventLog(tmp);
        RunResult r1 = new Executor(g, ctx, log, new AutoApprover(g), false).run();
        assertThat(r1.outcome()).isEqualTo("safe_stopped");
        assertThat(r1.metrics().successRate()).isEqualTo(0.0);

        g.markStale("a");
        RunResult r2 = new Executor(g, ctx, log, new AutoApprover(g), true).run();
        assertThat(r2.outcome()).isEqualTo("completed");
        assertThat(r2.metrics().successRate()).isEqualTo(1.0);
        assertThat(r2.metrics().mttrMsByNode).containsKey("a");
    }
}
