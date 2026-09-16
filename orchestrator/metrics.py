"""Reliability metrics derived purely from the event log.

Computing metrics from the audit trail (rather than tracking separate
counters during execution) guarantees they are always consistent with what
actually happened, and lets the same function be re-run against historical
runs stored under runs/.
"""
from dataclasses import dataclass, field


@dataclass
class RunMetrics:
    total_nodes: int = 0
    succeeded_nodes: int = 0
    failed_nodes: int = 0
    skipped_nodes: int = 0
    rolled_back_nodes: int = 0
    retry_count: int = 0
    rollback_count: int = 0
    safe_stops: int = 0
    replans: int = 0
    approvals_granted: int = 0
    approvals_withheld: int = 0
    policy_violations: int = 0
    end_to_end_latency_ms: float = 0.0
    stage_latency_ms: dict = field(default_factory=dict)
    mttr_ms_by_node: dict = field(default_factory=dict)

    @property
    def success_rate(self) -> float:
        if self.total_nodes == 0:
            return 0.0
        return round(self.succeeded_nodes / self.total_nodes, 4)

    @property
    def average_mttr_ms(self) -> float | None:
        values = [v for v in self.mttr_ms_by_node.values() if v is not None]
        if not values:
            return None
        return round(sum(values) / len(values), 2)

    def to_dict(self) -> dict:
        return {
            "total_nodes": self.total_nodes,
            "succeeded_nodes": self.succeeded_nodes,
            "failed_nodes": self.failed_nodes,
            "skipped_nodes": self.skipped_nodes,
            "rolled_back_nodes": self.rolled_back_nodes,
            "success_rate": self.success_rate,
            "retry_count": self.retry_count,
            "rollback_count": self.rollback_count,
            "safe_stops": self.safe_stops,
            "replans": self.replans,
            "approvals_granted": self.approvals_granted,
            "approvals_withheld": self.approvals_withheld,
            "policy_violations": self.policy_violations,
            "end_to_end_latency_ms": round(self.end_to_end_latency_ms, 2),
            "stage_latency_ms": {k: round(v, 2) for k, v in self.stage_latency_ms.items()},
            "average_mttr_ms": self.average_mttr_ms,
            "mttr_ms_by_node": self.mttr_ms_by_node,
        }


def compute_metrics(events: list[dict], graph) -> RunMetrics:
    m = RunMetrics()
    m.total_nodes = len(graph.nodes)
    for node in graph.nodes.values():
        if node.status == "SUCCEEDED":
            m.succeeded_nodes += 1
        elif node.status == "FAILED":
            m.failed_nodes += 1
        elif node.status == "SKIPPED":
            m.skipped_nodes += 1
        elif node.status == "ROLLED_BACK":
            m.rolled_back_nodes += 1

    first_failure_mono: dict[str, float] = {}
    for e in events:
        et = e["event_type"]
        if et == "node_retry":
            m.retry_count += 1
        elif et == "node_rolled_back":
            m.rollback_count += 1
        elif et == "safe_stop":
            m.safe_stops += 1
        elif et == "replan":
            m.replans += 1
        elif et == "approval_decision":
            if e.get("approved"):
                m.approvals_granted += 1
            else:
                m.approvals_withheld += 1
        elif et == "policy_violation":
            m.policy_violations += 1
        elif et == "node_failed":
            nid = e.get("node_id")
            if nid and nid not in first_failure_mono:
                first_failure_mono[nid] = e["monotonic"]
        elif et == "node_succeeded":
            nid = e.get("node_id")
            if nid in first_failure_mono and nid not in m.mttr_ms_by_node:
                m.mttr_ms_by_node[nid] = round((e["monotonic"] - first_failure_mono[nid]) * 1000, 2)

    run_start = next((e["monotonic"] for e in events if e["event_type"] == "run_started"), None)
    run_end = next((e["monotonic"] for e in reversed(events) if e["event_type"] == "run_finished"), None)
    if run_start is not None and run_end is not None:
        m.end_to_end_latency_ms = (run_end - run_start) * 1000

    stage_start: dict[str, float] = {}
    for e in events:
        if e["event_type"] == "node_started":
            stage_start[e["node_id"]] = e["monotonic"]
        elif e["event_type"] in ("node_succeeded", "node_failed") and e["node_id"] in stage_start:
            stage = e.get("stage", "unknown")
            elapsed_ms = (e["monotonic"] - stage_start[e["node_id"]]) * 1000
            m.stage_latency_ms[stage] = m.stage_latency_ms.get(stage, 0.0) + elapsed_ms

    return m
