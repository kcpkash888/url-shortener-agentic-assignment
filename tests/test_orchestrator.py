"""Unit tests for the orchestration engine itself (orchestrator/), using
small synthetic graphs -- independent of the URL shortener app. These exist
because the orchestrator is the assignment's "critical differentiator" and
deserves direct coverage, not just indirect exercise via the three scenario
scripts.
"""
import os
import tempfile

import pytest

from orchestrator.approvals import AlwaysReject, AutoApprover
from orchestrator.context import SharedContext
from orchestrator.executor import Executor
from orchestrator.graph import CycleError, Graph, Node, PolicyResult, RetryPolicy
from orchestrator.observability import EventLog


def make_ctx(scenario="test") -> SharedContext:
    return SharedContext(run_id="test-run", scenario=scenario, raw_request="a test request")


def make_event_log(tmp_path) -> EventLog:
    return EventLog(run_id="test-run", out_dir=str(tmp_path))


# -- Graph -------------------------------------------------------------------

def test_graph_detects_cycle():
    g = Graph()
    g.add(Node(id="a", name="a", stage="x", run=lambda c, n: {}, depends_on=["b"]))
    g.add(Node(id="b", name="b", stage="x", run=lambda c, n: {}, depends_on=["a"]))
    with pytest.raises(CycleError):
        g.validate()


def test_graph_rejects_unknown_dependency():
    g = Graph()
    g.add(Node(id="a", name="a", stage="x", run=lambda c, n: {}, depends_on=["missing"]))
    with pytest.raises(ValueError):
        g.validate()


def test_ready_nodes_only_returns_nodes_whose_deps_succeeded():
    g = Graph()
    g.add(Node(id="a", name="a", stage="x", run=lambda c, n: {}))
    g.add(Node(id="b", name="b", stage="x", run=lambda c, n: {}, depends_on=["a"]))
    assert [n.id for n in g.ready_nodes()] == ["a"]
    g.nodes["a"].status = "SUCCEEDED"
    assert [n.id for n in g.ready_nodes()] == ["b"]


def test_mark_stale_propagates_only_to_descendants():
    g = Graph()
    g.add(Node(id="a", name="a", stage="x", run=lambda c, n: {}))
    g.add(Node(id="b", name="b", stage="x", run=lambda c, n: {}, depends_on=["a"]))
    g.add(Node(id="c", name="c", stage="x", run=lambda c, n: {}))  # unrelated branch
    for nid in ("a", "b", "c"):
        g.nodes[nid].status = "SUCCEEDED"

    affected = g.mark_stale("a")

    assert set(affected) == {"a", "b"}
    assert g.nodes["a"].status == "STALE"
    assert g.nodes["b"].status == "STALE"
    assert g.nodes["c"].status == "SUCCEEDED"  # untouched: not a descendant of 'a'


# -- Executor: happy path, retries, fallback ---------------------------------

def test_executor_runs_linear_graph_to_completion(tmp_path):
    g = Graph()
    g.add(Node(id="a", name="a", stage="x", run=lambda c, n: {"v": 1}))
    g.add(Node(id="b", name="b", stage="x", run=lambda c, n: {"v": c.get_output("a")["v"] + 1}, depends_on=["a"]))
    ctx = make_ctx()
    result = Executor(g, ctx, make_event_log(tmp_path), approver=AutoApprover()).run()
    assert result.outcome == "completed"
    assert g.nodes["a"].status == "SUCCEEDED"
    assert g.nodes["b"].status == "SUCCEEDED"
    assert ctx.get_output("b")["v"] == 2


def test_executor_retries_transient_exception_then_succeeds(tmp_path):
    attempts = {"n": 0}

    def flaky(ctx, node):
        attempts["n"] += 1
        if attempts["n"] == 1:
            raise RuntimeError("transient")
        return {"ok": True}

    g = Graph()
    g.add(Node(id="a", name="a", stage="x", run=flaky, retry=RetryPolicy(max_attempts=2, backoff_seconds=0)))
    ctx = make_ctx()
    result = Executor(g, ctx, make_event_log(tmp_path), approver=AutoApprover()).run()
    assert result.outcome == "completed"
    assert g.nodes["a"].attempts == 2
    assert result.metrics.retry_count == 1


def test_executor_exhausts_retries_then_uses_fallback(tmp_path):
    g = Graph()
    g.add(Node(
        id="a", name="a", stage="x",
        run=lambda c, n: (_ for _ in ()).throw(RuntimeError("always fails")),
        fallback=lambda c, n: {"via": "fallback"},
        retry=RetryPolicy(max_attempts=1),
    ))
    ctx = make_ctx()
    result = Executor(g, ctx, make_event_log(tmp_path), approver=AutoApprover()).run()
    assert result.outcome == "completed"
    assert g.nodes["a"].status == "SUCCEEDED"
    assert ctx.get_output("a") == {"via": "fallback"}


# -- Executor: policy gates ----------------------------------------------------

def test_entry_policy_failure_blocks_node_without_running_it(tmp_path):
    ran = {"called": False}

    def mark_ran(c, n):
        ran["called"] = True
        return {}

    g = Graph()
    g.add(Node(
        id="a", name="a", stage="x", run=mark_ran,
        entry_policies=[lambda c, n, r: PolicyResult(False, "blocked by policy")],
    ))
    ctx = make_ctx()
    result = Executor(g, ctx, make_event_log(tmp_path), approver=AutoApprover(), rollback_on_failure=False).run()
    assert result.outcome == "safe_stopped"
    assert g.nodes["a"].status == "FAILED"
    assert ran["called"] is False


def test_exit_policy_failure_fails_node_after_it_ran(tmp_path):
    g = Graph()
    g.add(Node(
        id="a", name="a", stage="x", run=lambda c, n: {"passed": False},
        exit_policies=[lambda c, n, r: PolicyResult(r["passed"], "must pass")],
        retry=RetryPolicy(max_attempts=1),
    ))
    ctx = make_ctx()
    result = Executor(g, ctx, make_event_log(tmp_path), approver=AutoApprover(), rollback_on_failure=False).run()
    assert g.nodes["a"].status == "FAILED"
    assert len(ctx.policy_violations) == 1


def test_approval_withheld_blocks_node(tmp_path):
    g = Graph()
    g.add(Node(id="a", name="a", stage="release", run=lambda c, n: {}, requires_approval=True))
    ctx = make_ctx()
    result = Executor(
        g, ctx, make_event_log(tmp_path), approver=AlwaysReject(reason="manual hold"), rollback_on_failure=False
    ).run()
    assert g.nodes["a"].status == "FAILED"
    assert "manual hold" in g.nodes["a"].last_error
    assert ctx.approvals[0]["approved"] is False


# -- Executor: rollback vs safe-stop -----------------------------------------

def test_rollback_compensates_succeeded_nodes_and_the_failing_node_itself(tmp_path):
    compensated = []

    def compensate_factory(node_id):
        def _compensate(ctx, node):
            compensated.append(node_id)
        return _compensate

    g = Graph()
    g.add(Node(id="a", name="a", stage="x", run=lambda c, n: {"x": 1}, compensate=compensate_factory("a")))
    g.add(Node(
        id="b", name="b", stage="x", depends_on=["a"],
        run=lambda c, n: {"x": 2},  # runs successfully...
        exit_policies=[lambda c, n, r: PolicyResult(False, "reject anyway")],  # ...but its own gate rejects it
        compensate=compensate_factory("b"),
        retry=RetryPolicy(max_attempts=1),
    ))
    g.add(Node(id="c", name="c", stage="x", depends_on=["b"], run=lambda c, n: {}))  # never reached

    ctx = make_ctx()
    result = Executor(g, ctx, make_event_log(tmp_path), approver=AutoApprover(), rollback_on_failure=True).run()

    assert result.outcome == "rolled_back"
    assert g.nodes["a"].status == "ROLLED_BACK"
    assert g.nodes["b"].status == "FAILED"  # stays FAILED, but was still compensated
    assert g.nodes["c"].status == "SKIPPED"
    assert compensated == ["b", "a"]  # reverse topological order


def test_safe_stop_skips_remaining_and_does_not_compensate(tmp_path):
    compensated = []
    g = Graph()
    g.add(Node(id="a", name="a", stage="x", run=lambda c, n: {}, compensate=lambda c, n: compensated.append("a")))
    g.add(Node(
        id="b", name="b", stage="x", depends_on=["a"],
        run=lambda c, n: (_ for _ in ()).throw(RuntimeError("boom")),
        retry=RetryPolicy(max_attempts=1),
    ))
    g.add(Node(id="c", name="c", stage="x", depends_on=["b"], run=lambda c, n: {}))

    ctx = make_ctx()
    result = Executor(g, ctx, make_event_log(tmp_path), approver=AutoApprover(), rollback_on_failure=False).run()

    assert result.outcome == "safe_stopped"
    assert g.nodes["a"].status == "SUCCEEDED"  # left in place, last-known-good
    assert g.nodes["c"].status == "SKIPPED"
    assert compensated == []  # safe-stop never compensates


# -- Re-planning ---------------------------------------------------------------

def test_replan_only_reexecutes_the_stale_subgraph(tmp_path):
    calls = {"a": 0, "b": 0}

    def run_a(c, n):
        calls["a"] += 1
        return {"v": 1}

    def run_b(c, n):
        calls["b"] += 1
        return {"v": c.get_output("a")["v"] + 1}

    g = Graph()
    g.add(Node(id="a", name="a", stage="x", run=run_a))
    g.add(Node(id="b", name="b", stage="x", run=run_b, depends_on=["a"]))
    ctx = make_ctx()
    event_log = make_event_log(tmp_path)
    Executor(g, ctx, event_log, approver=AutoApprover()).run()
    assert calls == {"a": 1, "b": 1}

    g.mark_stale("b")
    result = Executor(g, ctx, event_log, approver=AutoApprover()).run()

    assert calls == {"a": 1, "b": 2}  # 'a' was not re-run; only the stale 'b' was
    assert result.outcome == "completed"


# -- Metrics --------------------------------------------------------------------

def test_metrics_reflect_success_rate_and_mttr_across_failure_and_recovery(tmp_path):
    attempt = {"n": 0}

    def run_once_fails(c, n):
        attempt["n"] += 1
        if attempt["n"] == 1:
            raise RuntimeError("boom")
        return {"ok": True}

    g = Graph()
    g.add(Node(id="a", name="a", stage="x", run=run_once_fails, retry=RetryPolicy(max_attempts=1)))
    ctx = make_ctx()
    event_log = make_event_log(tmp_path)
    result1 = Executor(g, ctx, event_log, approver=AutoApprover(), rollback_on_failure=False).run()
    assert result1.outcome == "safe_stopped"
    assert result1.metrics.success_rate == 0.0

    g.mark_stale("a")
    result2 = Executor(g, ctx, event_log, approver=AutoApprover()).run()
    assert result2.outcome == "completed"
    assert result2.metrics.success_rate == 1.0
    assert result2.metrics.mttr_ms_by_node.get("a") is not None
