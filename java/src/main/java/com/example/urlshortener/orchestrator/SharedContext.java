package com.example.urlshortener.orchestrator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared, stateful context threaded through a workflow run -- what makes the
 * orchestration "stateful" rather than a stream of independent calls. Every
 * node reads prior nodes' outputs from here, and every non-trivial decision
 * is appended to {@code decisions} as a permanent, ordered record (the
 * "decision lineage"). Thread-safe: parallel nodes in the same wave write to
 * this concurrently.
 */
public class SharedContext {
    public final String runId;
    public final String scenario;
    public final String rawRequest;

    private final Map<String, Map<String, Object>> data = new ConcurrentHashMap<>();
    public final List<Decision> decisions = new CopyOnWriteArrayList<>();
    public final List<String> artifacts = new CopyOnWriteArrayList<>();
    public final List<Map<String, Object>> policyViolations = new CopyOnWriteArrayList<>();
    public final List<Map<String, Object>> approvals = new CopyOnWriteArrayList<>();

    public SharedContext(String runId, String scenario, String rawRequest) {
        this.runId = runId;
        this.scenario = scenario;
        this.rawRequest = rawRequest;
    }

    public void setOutput(String nodeId, Map<String, Object> payload) {
        data.put(nodeId, payload);
    }

    public Map<String, Object> getOutput(String nodeId) {
        Map<String, Object> v = data.get(nodeId);
        if (v == null) throw new IllegalStateException("no output recorded yet for node '" + nodeId + "'");
        return v;
    }

    public boolean hasOutput(String nodeId) {
        return data.containsKey(nodeId);
    }

    public void recordDecision(String stage, String nodeId, String summary, String rationale) {
        recordDecision(stage, nodeId, summary, rationale, List.of());
    }

    public void recordDecision(String stage, String nodeId, String summary, String rationale, List<String> alternatives) {
        decisions.add(Decision.of(stage, nodeId, summary, rationale, alternatives));
    }

    public void addArtifact(String path) {
        if (!artifacts.contains(path)) artifacts.add(path);
    }

    public void recordPolicyViolation(String nodeId, String policyName, String message) {
        policyViolations.add(Map.of(
                "node_id", nodeId, "policy", policyName, "message", message,
                "timestamp", java.time.Instant.now().toString()
        ));
    }

    public void recordApproval(String nodeId, boolean approved, String approver, String rationale) {
        approvals.add(Map.of(
                "node_id", nodeId, "approved", approved, "approver", approver, "rationale", rationale,
                "timestamp", java.time.Instant.now().toString()
        ));
    }
}
