package com.example.urlshortener.orchestrator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Computes reliability metrics purely from the event log, so they can never
 * drift from what actually happened and can be recomputed for any historical
 * run. Direct port of orchestrator/metrics.py's compute_metrics. */
public final class MetricsCalculator {
    private MetricsCalculator() {}

    public static RunMetrics compute(List<Map<String, Object>> events, Graph graph) {
        RunMetrics m = new RunMetrics();
        m.totalNodes = graph.nodes.size();
        for (Node n : graph.nodes.values()) {
            switch (n.status) {
                case SUCCEEDED -> m.succeededNodes++;
                case FAILED -> m.failedNodes++;
                case SKIPPED -> m.skippedNodes++;
                case ROLLED_BACK -> m.rolledBackNodes++;
                default -> {}
            }
        }

        Map<String, Long> firstFailureNanos = new HashMap<>();
        for (Map<String, Object> e : events) {
            String type = (String) e.get("event_type");
            switch (type) {
                case "node_retry" -> m.retryCount++;
                case "node_rolled_back" -> m.rollbackCount++;
                case "safe_stop" -> m.safeStops++;
                case "replan" -> m.replans++;
                case "approval_decision" -> {
                    Boolean approved = (Boolean) e.get("approved");
                    if (Boolean.TRUE.equals(approved)) m.approvalsGranted++; else m.approvalsWithheld++;
                }
                case "policy_violation" -> m.policyViolations++;
                case "node_failed" -> {
                    String nid = (String) e.get("node_id");
                    if (nid != null) firstFailureNanos.putIfAbsent(nid, (Long) e.get("nanos"));
                }
                case "node_succeeded" -> {
                    String nid = (String) e.get("node_id");
                    if (nid != null && firstFailureNanos.containsKey(nid) && !m.mttrMsByNode.containsKey(nid)) {
                        long failedAt = firstFailureNanos.get(nid);
                        long succeededAt = (Long) e.get("nanos");
                        m.mttrMsByNode.put(nid, Math.round((succeededAt - failedAt) / 1_000_000.0 * 100.0) / 100.0);
                    }
                }
                default -> {}
            }
        }

        Long runStart = events.stream().filter(e -> "run_started".equals(e.get("event_type")))
                .map(e -> (Long) e.get("nanos")).findFirst().orElse(null);
        Long runEnd = null;
        for (int i = events.size() - 1; i >= 0; i--) {
            if ("run_finished".equals(events.get(i).get("event_type"))) {
                runEnd = (Long) events.get(i).get("nanos");
                break;
            }
        }
        if (runStart != null && runEnd != null) {
            m.endToEndLatencyMs = (runEnd - runStart) / 1_000_000.0;
        }

        Map<String, Long> stageStart = new HashMap<>();
        for (Map<String, Object> e : events) {
            String type = (String) e.get("event_type");
            String nid = (String) e.get("node_id");
            if ("node_started".equals(type) && nid != null) {
                stageStart.put(nid, (Long) e.get("nanos"));
            } else if (("node_succeeded".equals(type) || "node_failed".equals(type)) && nid != null && stageStart.containsKey(nid)) {
                String stage = (String) e.getOrDefault("stage", "unknown");
                double elapsedMs = ((Long) e.get("nanos") - stageStart.get(nid)) / 1_000_000.0;
                m.stageLatencyMs.merge(stage, elapsedMs, Double::sum);
            }
        }
        m.stageLatencyMs.replaceAll((k, v) -> Math.round(v * 100.0) / 100.0);

        return m;
    }
}
