package com.example.urlshortener.orchestrator;

import java.util.LinkedHashMap;
import java.util.Map;

public class RunMetrics {
    public int totalNodes;
    public int succeededNodes;
    public int failedNodes;
    public int skippedNodes;
    public int rolledBackNodes;
    public int retryCount;
    public int rollbackCount;
    public int safeStops;
    public int replans;
    public int approvalsGranted;
    public int approvalsWithheld;
    public int policyViolations;
    public double endToEndLatencyMs;
    public final Map<String, Double> stageLatencyMs = new LinkedHashMap<>();
    public final Map<String, Double> mttrMsByNode = new LinkedHashMap<>();

    public double successRate() {
        return totalNodes == 0 ? 0.0 : (double) succeededNodes / totalNodes;
    }

    public Double averageMttrMs() {
        if (mttrMsByNode.isEmpty()) return null;
        return mttrMsByNode.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total_nodes", totalNodes);
        m.put("succeeded_nodes", succeededNodes);
        m.put("failed_nodes", failedNodes);
        m.put("skipped_nodes", skippedNodes);
        m.put("rolled_back_nodes", rolledBackNodes);
        m.put("success_rate", Math.round(successRate() * 10000.0) / 10000.0);
        m.put("retry_count", retryCount);
        m.put("rollback_count", rollbackCount);
        m.put("safe_stops", safeStops);
        m.put("replans", replans);
        m.put("approvals_granted", approvalsGranted);
        m.put("approvals_withheld", approvalsWithheld);
        m.put("policy_violations", policyViolations);
        m.put("end_to_end_latency_ms", Math.round(endToEndLatencyMs * 100.0) / 100.0);
        m.put("stage_latency_ms", stageLatencyMs);
        m.put("average_mttr_ms", averageMttrMs());
        m.put("mttr_ms_by_node", mttrMsByNode);
        return m;
    }
}
