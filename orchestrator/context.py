"""Shared, stateful context threaded through a workflow run.

This is what makes the orchestration "stateful" rather than a stream of
independent tool calls: every node reads prior nodes' outputs from here, and
every non-trivial decision -- including *why* a particular interpretation or
design choice was picked over alternatives -- is appended to `decisions` as a
permanent, ordered record (the "decision lineage"). The audit report and the
re-planning logic both read this object, not the raw node functions.
"""
import threading
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass
class Decision:
    stage: str
    node_id: str
    summary: str
    rationale: str
    alternatives_considered: list[str] = field(default_factory=list)
    timestamp: str = field(default_factory=_now)

    def to_dict(self) -> dict:
        return {
            "stage": self.stage,
            "node_id": self.node_id,
            "summary": self.summary,
            "rationale": self.rationale,
            "alternatives_considered": self.alternatives_considered,
            "timestamp": self.timestamp,
        }


class SharedContext:
    def __init__(self, run_id: str, scenario: str, raw_request: str):
        self.run_id = run_id
        self.scenario = scenario
        self.raw_request = raw_request
        self.data: dict[str, Any] = {}
        self.decisions: list[Decision] = []
        self.artifacts: list[str] = []
        self.policy_violations: list[dict] = []
        self.approvals: list[dict] = []
        self._lock = threading.Lock()

    def set_output(self, node_id: str, payload: Any) -> None:
        with self._lock:
            self.data[node_id] = payload

    def get_output(self, node_id: str) -> Any:
        if node_id not in self.data:
            raise KeyError(f"no output recorded yet for node '{node_id}'")
        return self.data[node_id]

    def has_output(self, node_id: str) -> bool:
        return node_id in self.data

    def record_decision(
        self, stage: str, node_id: str, summary: str, rationale: str, alternatives_considered: list[str] | None = None
    ) -> None:
        with self._lock:
            self.decisions.append(
                Decision(stage, node_id, summary, rationale, alternatives_considered or [])
            )

    def add_artifact(self, path: str) -> None:
        with self._lock:
            if path not in self.artifacts:
                self.artifacts.append(path)

    def record_policy_violation(self, node_id: str, policy_name: str, message: str) -> None:
        with self._lock:
            self.policy_violations.append(
                {"node_id": node_id, "policy": policy_name, "message": message, "timestamp": _now()}
            )

    def record_approval(self, node_id: str, approved: bool, approver: str, rationale: str) -> None:
        with self._lock:
            self.approvals.append(
                {
                    "node_id": node_id,
                    "approved": approved,
                    "approver": approver,
                    "rationale": rationale,
                    "timestamp": _now(),
                }
            )

    def lineage_as_dicts(self) -> list[dict]:
        return [d.to_dict() for d in self.decisions]
