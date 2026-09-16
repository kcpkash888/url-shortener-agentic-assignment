"""The orchestration engine: walks the dependency graph wave by wave,
enforcing entry/exit gates, approval checkpoints, bounded retries, fallback,
and rollback/safe-stop -- and supports re-planning by re-invoking `run()`
after the caller marks part of the graph stale.

A "wave" is the set of nodes whose dependencies are all satisfied at a given
moment. Nodes in a wave with `parallel_ok=True` execute concurrently in a
thread pool; the next wave cannot start until the whole current wave (serial
and parallel members) has resolved, which is the "synchronization" the
assignment asks for -- e.g. testing and docs both depend on implementation
and run side by side, but release waits for both.
"""
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass

from orchestrator.approvals import Approver
from orchestrator.context import SharedContext
from orchestrator.graph import FAILED, PENDING, ROLLED_BACK, RUNNING, SKIPPED, STALE, SUCCEEDED, Graph, Node
from orchestrator.metrics import RunMetrics, compute_metrics
from orchestrator.observability import EventLog


@dataclass
class RunResult:
    ctx: SharedContext
    graph: Graph
    metrics: RunMetrics
    event_log: EventLog
    outcome: str  # "completed" | "rolled_back" | "safe_stopped"


class Executor:
    def __init__(
        self,
        graph: Graph,
        ctx: SharedContext,
        event_log: EventLog,
        approver: Approver,
        rollback_on_failure: bool = True,
        max_workers: int = 4,
    ):
        graph.validate()
        self.graph = graph
        self.ctx = ctx
        self.event_log = event_log
        self.approver = approver
        self.rollback_on_failure = rollback_on_failure
        self.max_workers = max_workers

    def run(self) -> RunResult:
        is_replan = any(n.status == STALE for n in self.graph.nodes.values())
        self.event_log.emit("replan" if is_replan else "run_started")
        if is_replan:
            self.event_log.emit(
                "run_resumed_after_replan",
                stale_nodes=[n.id for n in self.graph.nodes.values() if n.status == STALE],
            )

        outcome = "completed"
        while True:
            wave = self.graph.ready_nodes()
            if not wave:
                break

            parallel_nodes = [n for n in wave if n.parallel_ok]
            serial_nodes = [n for n in wave if not n.parallel_ok]

            self.event_log.emit(
                "wave_started",
                parallel=[n.id for n in parallel_nodes],
                serial=[n.id for n in serial_nodes],
            )

            if parallel_nodes:
                with ThreadPoolExecutor(max_workers=self.max_workers) as pool:
                    list(pool.map(self._execute_node, parallel_nodes))
            for node in serial_nodes:
                self._execute_node(node)

            failed_this_wave = [n for n in wave if n.status == FAILED]
            if failed_this_wave:
                if self.rollback_on_failure:
                    self._rollback()
                    outcome = "rolled_back"
                else:
                    self._safe_stop()
                    outcome = "safe_stopped"
                break

        self.event_log.emit("run_finished", outcome=outcome)
        metrics = compute_metrics(self.event_log.events, self.graph)
        return RunResult(self.ctx, self.graph, metrics, self.event_log, outcome)

    # -- node execution -----------------------------------------------------

    def _execute_node(self, node: Node) -> None:
        node.status = RUNNING
        self.event_log.emit("node_started", node_id=node.id, stage=node.stage, name=node.name)

        for policy in node.entry_policies:
            pr = policy(self.ctx, node, None)
            if not pr.passed:
                self.ctx.record_policy_violation(node.id, getattr(policy, "__name__", "entry_policy"), pr.message)
                self.event_log.emit(
                    "policy_violation", node_id=node.id, policy=getattr(policy, "__name__", "entry_policy"),
                    gate="entry", message=pr.message,
                )
                node.status = FAILED
                node.last_error = f"entry policy failed: {pr.message}"
                self.event_log.emit("node_failed", node_id=node.id, stage=node.stage, error=node.last_error)
                return

        if node.requires_approval:
            decision = self.approver.decide(self.ctx, node, None)
            self.ctx.record_approval(node.id, decision.approved, decision.approver, decision.rationale)
            self.event_log.emit(
                "approval_decision", node_id=node.id, approved=decision.approved,
                approver=decision.approver, rationale=decision.rationale,
            )
            if not decision.approved:
                node.status = FAILED
                node.last_error = f"approval withheld by {decision.approver}: {decision.rationale}"
                self.event_log.emit("node_failed", node_id=node.id, stage=node.stage, error=node.last_error)
                return

        result = None
        max_attempts = max(1, node.retry.max_attempts)
        for attempt in range(1, max_attempts + 1):
            node.attempts = attempt
            try:
                result = node.run(self.ctx, node)
            except Exception as exc:  # noqa: BLE001 -- executor must not crash on agent errors
                node.last_error = str(exc)
                if attempt < max_attempts:
                    self.event_log.emit(
                        "node_retry", node_id=node.id, attempt=attempt, max_attempts=max_attempts,
                        reason=str(exc),
                    )
                    time.sleep(node.retry.backoff_seconds * attempt)
                    continue
                break

            node.last_result = result  # visible to compensate even if the exit gate rejects this attempt

            exit_failure = None
            for policy in node.exit_policies:
                pr = policy(self.ctx, node, result)
                if not pr.passed:
                    exit_failure = (getattr(policy, "__name__", "exit_policy"), pr.message)
                    break

            if exit_failure is None:
                self.ctx.set_output(node.id, result)
                node.status = SUCCEEDED
                self.event_log.emit("node_succeeded", node_id=node.id, stage=node.stage)
                return

            policy_name, message = exit_failure
            self.ctx.record_policy_violation(node.id, policy_name, message)
            self.event_log.emit(
                "policy_violation", node_id=node.id, policy=policy_name, gate="exit", message=message
            )
            node.last_error = f"exit policy '{policy_name}' failed: {message}"
            if attempt < max_attempts:
                self.event_log.emit(
                    "node_retry", node_id=node.id, attempt=attempt, max_attempts=max_attempts, reason=message
                )
                time.sleep(node.retry.backoff_seconds * attempt)
                continue
            break

        if node.fallback is not None:
            try:
                self.event_log.emit("node_fallback_invoked", node_id=node.id)
                result = node.fallback(self.ctx, node)
                node.last_result = result
                self.ctx.set_output(node.id, result)
                node.status = SUCCEEDED
                self.event_log.emit("node_succeeded", node_id=node.id, stage=node.stage, via_fallback=True)
                return
            except Exception as exc:  # noqa: BLE001
                node.last_error = f"fallback also failed: {exc}"

        node.status = FAILED
        self.event_log.emit("node_failed", node_id=node.id, stage=node.stage, error=node.last_error)

    # -- failure handling -----------------------------------------------------

    def _rollback(self) -> None:
        order = list(reversed(self.graph.topological_order()))
        self.event_log.emit("rollback_started", order=order)
        for nid in order:
            node = self.graph.nodes[nid]
            # A node that failed its own exit gate can still have written side
            # effects before the gate rejected it (last_result is set as soon as
            # node.run() returns, before the exit policy is checked) -- it must
            # be compensated too, not just nodes that fully succeeded.
            if node.status in (SUCCEEDED, FAILED) and node.compensate is not None and node.last_result is not None:
                try:
                    node.compensate(self.ctx, node)
                    was_succeeded = node.status == SUCCEEDED
                    if was_succeeded:
                        node.status = ROLLED_BACK
                    self.event_log.emit("node_rolled_back", node_id=node.id, was_succeeded=was_succeeded)
                except Exception as exc:  # noqa: BLE001
                    self.event_log.emit("node_rollback_failed", node_id=node.id, error=str(exc))
        for node in self.graph.nodes.values():
            if node.status in (PENDING, STALE):
                node.status = SKIPPED
        self.event_log.emit("rollback_finished")

    def _safe_stop(self) -> None:
        skipped = []
        for node in self.graph.nodes.values():
            if node.status in (PENDING, STALE):
                node.status = SKIPPED
                skipped.append(node.id)
        self.event_log.emit(
            "safe_stop",
            skipped_nodes=skipped,
            note="halted without rollback; all previously SUCCEEDED nodes remain in place as last-known-good state",
        )
