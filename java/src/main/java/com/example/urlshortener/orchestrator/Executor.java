package com.example.urlshortener.orchestrator;

import java.util.*;
import java.util.concurrent.*;

/**
 * The orchestration engine: walks the dependency graph wave by wave,
 * enforcing entry/exit gates, approval checkpoints, bounded retries,
 * fallback, and rollback/safe-stop -- and supports re-planning by
 * re-invoking {@link #run()} after the caller marks part of the graph
 * stale. Direct, behavior-preserving port of orchestrator/executor.py.
 */
public class Executor {
    private final Graph graph;
    private final SharedContext ctx;
    private final EventLog eventLog;
    private final Approver approver;
    private final boolean rollbackOnFailure;
    private final int maxWorkers;

    public Executor(Graph graph, SharedContext ctx, EventLog eventLog, Approver approver, boolean rollbackOnFailure, int maxWorkers) {
        graph.validate();
        this.graph = graph;
        this.ctx = ctx;
        this.eventLog = eventLog;
        this.approver = approver;
        this.rollbackOnFailure = rollbackOnFailure;
        this.maxWorkers = maxWorkers;
    }

    public Executor(Graph graph, SharedContext ctx, EventLog eventLog, Approver approver, boolean rollbackOnFailure) {
        this(graph, ctx, eventLog, approver, rollbackOnFailure, 4);
    }

    public RunResult run() {
        boolean isReplan = graph.nodes.values().stream().anyMatch(n -> n.status == NodeStatus.STALE);
        eventLog.emit(isReplan ? "replan" : "run_started");
        if (isReplan) {
            List<String> stale = graph.nodes.values().stream().filter(n -> n.status == NodeStatus.STALE).map(n -> n.id).toList();
            eventLog.emit("run_resumed_after_replan", null, Map.of("stale_nodes", stale));
        }

        String outcome = "completed";
        while (true) {
            List<Node> wave = graph.readyNodes();
            if (wave.isEmpty()) break;

            List<Node> parallelNodes = wave.stream().filter(n -> n.parallelOk).toList();
            List<Node> serialNodes = wave.stream().filter(n -> !n.parallelOk).toList();

            eventLog.emit("wave_started", null, Map.of(
                    "parallel", parallelNodes.stream().map(n -> n.id).toList(),
                    "serial", serialNodes.stream().map(n -> n.id).toList()
            ));

            if (!parallelNodes.isEmpty()) {
                ExecutorService pool = Executors.newFixedThreadPool(Math.min(maxWorkers, Math.max(1, parallelNodes.size())));
                try {
                    List<Future<?>> futures = new ArrayList<>();
                    for (Node n : parallelNodes) futures.add(pool.submit(() -> executeNode(n)));
                    for (Future<?> f : futures) f.get();
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                } finally {
                    pool.shutdown();
                }
            }
            for (Node n : serialNodes) executeNode(n);

            List<Node> failedThisWave = wave.stream().filter(n -> n.status == NodeStatus.FAILED).toList();
            if (!failedThisWave.isEmpty()) {
                if (rollbackOnFailure) {
                    rollback();
                    outcome = "rolled_back";
                } else {
                    safeStop();
                    outcome = "safe_stopped";
                }
                break;
            }
        }

        eventLog.emit("run_finished", null, Map.of("outcome", outcome));
        RunMetrics metrics = MetricsCalculator.compute(eventLog.events, graph);
        return new RunResult(ctx, graph, metrics, eventLog, outcome);
    }

    private void executeNode(Node node) {
        node.status = NodeStatus.RUNNING;
        eventLog.emit("node_started", node.id, Map.of("stage", node.stage, "name", node.name));

        for (NamedPolicy policy : node.entryPolicies) {
            PolicyResult pr = policy.check(ctx, node, null);
            if (!pr.passed()) {
                ctx.recordPolicyViolation(node.id, policy.name(), pr.message());
                eventLog.emit("policy_violation", node.id, Map.of("policy", policy.name(), "gate", "entry", "message", pr.message()));
                node.status = NodeStatus.FAILED;
                node.lastError = "entry policy failed: " + pr.message();
                eventLog.emit("node_failed", node.id, Map.of("stage", node.stage, "error", node.lastError));
                return;
            }
        }

        if (node.requiresApproval) {
            ApprovalDecision decision = approver.decide(ctx, node, null);
            ctx.recordApproval(node.id, decision.approved(), decision.approver(), decision.rationale());
            eventLog.emit("approval_decision", node.id, Map.of(
                    "approved", decision.approved(), "approver", decision.approver(), "rationale", decision.rationale()));
            if (!decision.approved()) {
                node.status = NodeStatus.FAILED;
                node.lastError = "approval withheld by " + decision.approver() + ": " + decision.rationale();
                eventLog.emit("node_failed", node.id, Map.of("stage", node.stage, "error", node.lastError));
                return;
            }
        }

        Map<String, Object> result = null;
        int maxAttempts = Math.max(1, node.retry.maxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            node.attempts = attempt;
            try {
                result = node.run.run(ctx, node);
            } catch (Exception ex) {
                node.lastError = String.valueOf(ex.getMessage());
                if (attempt < maxAttempts) {
                    eventLog.emit("node_retry", node.id, Map.of("attempt", attempt, "max_attempts", maxAttempts, "reason", node.lastError));
                    sleep(node.retry.backoffMillis() * attempt);
                    continue;
                }
                break;
            }

            node.lastResult = result; // visible to compensate even if the exit gate rejects this attempt

            NamedPolicy failedPolicy = null;
            PolicyResult failedResult = null;
            for (NamedPolicy policy : node.exitPolicies) {
                PolicyResult pr = policy.check(ctx, node, result);
                if (!pr.passed()) {
                    failedPolicy = policy;
                    failedResult = pr;
                    break;
                }
            }

            if (failedPolicy == null) {
                ctx.setOutput(node.id, result);
                node.status = NodeStatus.SUCCEEDED;
                eventLog.emit("node_succeeded", node.id, Map.of("stage", node.stage));
                return;
            }

            ctx.recordPolicyViolation(node.id, failedPolicy.name(), failedResult.message());
            eventLog.emit("policy_violation", node.id, Map.of("policy", failedPolicy.name(), "gate", "exit", "message", failedResult.message()));
            node.lastError = "exit policy '" + failedPolicy.name() + "' failed: " + failedResult.message();
            if (attempt < maxAttempts) {
                eventLog.emit("node_retry", node.id, Map.of("attempt", attempt, "max_attempts", maxAttempts, "reason", failedResult.message()));
                sleep(node.retry.backoffMillis() * attempt);
                continue;
            }
            break;
        }

        if (node.fallback != null) {
            try {
                eventLog.emit("node_fallback_invoked", node.id, Map.of());
                result = node.fallback.run(ctx, node);
                node.lastResult = result;
                ctx.setOutput(node.id, result);
                node.status = NodeStatus.SUCCEEDED;
                eventLog.emit("node_succeeded", node.id, Map.of("stage", node.stage, "via_fallback", true));
                return;
            } catch (Exception ex) {
                node.lastError = "fallback also failed: " + ex.getMessage();
            }
        }

        node.status = NodeStatus.FAILED;
        eventLog.emit("node_failed", node.id, Map.of("stage", node.stage, "error", String.valueOf(node.lastError)));
    }

    private void rollback() {
        List<String> order = new ArrayList<>(graph.topologicalOrder());
        Collections.reverse(order);
        eventLog.emit("rollback_started", null, Map.of("order", order));
        for (String nid : order) {
            Node node = graph.nodes.get(nid);
            boolean eligible = (node.status == NodeStatus.SUCCEEDED || node.status == NodeStatus.FAILED)
                    && node.compensate != null && node.lastResult != null;
            if (eligible) {
                try {
                    node.compensate.compensate(ctx, node);
                    boolean wasSucceeded = node.status == NodeStatus.SUCCEEDED;
                    if (wasSucceeded) node.status = NodeStatus.ROLLED_BACK;
                    eventLog.emit("node_rolled_back", node.id, Map.of("was_succeeded", wasSucceeded));
                } catch (Exception ex) {
                    eventLog.emit("node_rollback_failed", node.id, Map.of("error", String.valueOf(ex.getMessage())));
                }
            }
        }
        for (Node node : graph.nodes.values()) {
            if (node.status == NodeStatus.PENDING || node.status == NodeStatus.STALE) {
                node.status = NodeStatus.SKIPPED;
            }
        }
        eventLog.emit("rollback_finished");
    }

    private void safeStop() {
        List<String> skipped = new ArrayList<>();
        for (Node node : graph.nodes.values()) {
            if (node.status == NodeStatus.PENDING || node.status == NodeStatus.STALE) {
                node.status = NodeStatus.SKIPPED;
                skipped.add(node.id);
            }
        }
        eventLog.emit("safe_stop", null, Map.of(
                "skipped_nodes", skipped,
                "note", "halted without rollback; all previously SUCCEEDED nodes remain in place as last-known-good state"
        ));
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
