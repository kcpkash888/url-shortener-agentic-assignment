"""Append-only, structured event log for a workflow run -- the audit trail.

Every state transition the executor makes (node started, retried, blocked by
policy, approved/rejected, succeeded, failed, rolled back, skipped by
safe-stop, marked stale for re-planning) is written here as one JSON object
per line, in order, and flushed to disk immediately. This is what makes the
run auditable after the fact: `runs/<run_id>/events.jsonl` is a complete,
replayable trace independent of the in-memory context.
"""
import json
import os
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass
class EventLog:
    run_id: str
    out_dir: str
    events: list[dict] = field(default_factory=list)
    _fh = None
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def __post_init__(self):
        os.makedirs(self.out_dir, exist_ok=True)
        self._path = os.path.join(self.out_dir, "events.jsonl")
        self._fh = open(self._path, "w", encoding="utf-8")

    def emit(self, event_type: str, node_id: str | None = None, **details) -> dict:
        event = {
            "timestamp": _now(),
            "monotonic": time.monotonic(),
            "run_id": self.run_id,
            "event_type": event_type,
            "node_id": node_id,
            **details,
        }
        with self._lock:
            self.events.append(event)
            self._fh.write(json.dumps(event, default=str) + "\n")
            self._fh.flush()
        return event

    def close(self) -> None:
        if self._fh:
            self._fh.close()
