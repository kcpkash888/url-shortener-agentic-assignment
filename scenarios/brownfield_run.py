"""BROWNFIELD scenario, two parts against the existing codebase:

PART 1 -- enhance the existing analytics endpoint with a `top_referrers`
breakdown. Demonstrates: codebase impact analysis over real files, a bounded
retry recovering from a simulated transient test-runner failure, and then a
simulated requirement change ("also report unique_referrer_count") handled
by marking the design node stale and re-running -- only the affected
subgraph (design/implementation/testing/docs/release) re-executes; the
already-succeeded requirements and codebase-analysis nodes are left alone.

PART 2 -- a deliberately-introduced security defect (a hardcoded-looking
secret in a new audit-log module) is caught by the `no_hardcoded_secrets`
exit-gate policy, which fails the implementation node and triggers an
automatic rollback (the bad file write is reverted). We then apply the fix
and re-plan from the failed node, demonstrating recovery and giving the
metrics module a real MTTR to report.

Run with:  python -m scenarios.brownfield_run
"""
import os

from scenarios.common import PROJECT_ROOT, new_run_id, print_summary, run_dir, write_report

from orchestrator import policy
from orchestrator.agents import codebase_analysis_agent, design_agent, docs_agent, implementation_agent, release_agent, requirements_agent, testing_agent
from orchestrator.approvals import AutoApprover
from orchestrator.context import SharedContext
from orchestrator.executor import Executor
from orchestrator.graph import Graph, Node, RetryPolicy
from orchestrator.observability import EventLog

# ---------------------------------------------------------------------------
# PART 1: analytics enhancement
# ---------------------------------------------------------------------------

PART1_REQUEST = (
    "Enhance GET /api/urls/{code}/analytics so it must also return a top_referrers breakdown "
    "(referrer -> click count, top 5) computed from existing click records."
)


def _build_shortener_content(project_root: str, include_unique_referrer_count: bool) -> str:
    path = os.path.join(project_root, "app", "shortener.py")
    with open(path, encoding="utf-8") as f:
        content = f.read()

    if "top_referrers" not in content:
        old_query = (
            '    rows = conn.execute(\n'
            '        "SELECT clicked_at, referrer, user_agent FROM clicks WHERE code = ? "\n'
            '        "ORDER BY clicked_at DESC LIMIT ?",\n'
            '        (code, recent_limit),\n'
            '    ).fetchall()\n'
        )
        new_query = old_query + (
            '    referrer_rows = conn.execute(\n'
            '        "SELECT COALESCE(referrer, \'direct\') AS referrer, COUNT(*) as count FROM clicks "\n'
            '        "WHERE code = ? GROUP BY referrer ORDER BY count DESC LIMIT 5",\n'
            '        (code,),\n'
            '    ).fetchall()\n'
            '    top_referrers = [{"referrer": r["referrer"], "count": r["count"]} for r in referrer_rows]\n'
        )
        assert old_query in content, "expected query block not found in app/shortener.py"
        content = content.replace(old_query, new_query)
        content = content.replace(
            '        "recent_clicks": [dict(r) for r in rows],\n    }',
            '        "recent_clicks": [dict(r) for r in rows],\n        "top_referrers": top_referrers,\n    }',
        )

    if include_unique_referrer_count and "unique_referrer_count" not in content:
        anchor = '    top_referrers = [{"referrer": r["referrer"], "count": r["count"]} for r in referrer_rows]\n'
        content = content.replace(anchor, anchor + "    unique_referrer_count = len(referrer_rows)\n")
        content = content.replace(
            '        "top_referrers": top_referrers,\n    }',
            '        "top_referrers": top_referrers,\n        "unique_referrer_count": unique_referrer_count,\n    }',
        )
    return content


def _build_schemas_content(project_root: str, include_unique_referrer_count: bool) -> str:
    path = os.path.join(project_root, "app", "schemas.py")
    with open(path, encoding="utf-8") as f:
        content = f.read()
    if "top_referrers" not in content:
        content = content.replace(
            "    recent_clicks: list[ClickEvent]",
            "    recent_clicks: list[ClickEvent]\n    top_referrers: list[dict] = []",
        )
    if include_unique_referrer_count and "unique_referrer_count" not in content:
        content = content.replace(
            "    top_referrers: list[dict] = []",
            "    top_referrers: list[dict] = []\n    unique_referrer_count: int | None = None",
        )
    return content


def _build_analytics_test_content(include_unique_referrer_count: bool) -> str:
    extra_assertion = (
        '\n    assert "unique_referrer_count" in body and isinstance(body["unique_referrer_count"], int)'
        if include_unique_referrer_count
        else ""
    )
    return f'''"""Tests for the analytics `top_referrers` enhancement, added by the
brownfield orchestration scenario (scenarios/brownfield_run.py)."""


def test_analytics_reports_top_referrers(client):
    created = client.post("/api/urls", json={{"url": "https://example.com"}}).json()
    code = created["code"]
    client.get(f"/{{code}}", headers={{"referer": "https://google.com"}}, follow_redirects=False)
    client.get(f"/{{code}}", headers={{"referer": "https://google.com"}}, follow_redirects=False)
    client.get(f"/{{code}}", headers={{"referer": "https://bing.com"}}, follow_redirects=False)

    resp = client.get(f"/api/urls/{{code}}/analytics")
    body = resp.json()
    assert "top_referrers" in body
    referrer_counts = {{r["referrer"]: r["count"] for r in body["top_referrers"]}}
    assert referrer_counts["https://google.com"] == 2
    assert referrer_counts["https://bing.com"] == 1{extra_assertion}
'''


_flaky_state = {"attempts": 0}


def _flaky_run_tests(ctx, node, project_root: str) -> dict:
    """Wraps the real testing agent but fails the *first* invocation across
    this whole process run with a simulated transient environment error, to
    demonstrate the bounded-retry path recovering without any code change."""
    _flaky_state["attempts"] += 1
    if _flaky_state["attempts"] == 1:
        raise RuntimeError(
            "simulated transient CI-runner hiccup (disk contention) -- not a real defect; "
            "demonstrates the bounded retry policy recovering automatically"
        )
    return testing_agent.run_tests(ctx, node, test_path="tests/", project_root=project_root)


def build_part1_graph(project_root: str) -> Graph:
    g = Graph()

    g.add(Node(
        id="requirements", name="Normalize the analytics-enhancement request", stage="requirements",
        depends_on=[], run=lambda ctx, node: requirements_agent.normalize(ctx, node),
    ))

    g.add(Node(
        id="codebase_analysis", name="Analyze impact on existing analytics code", stage="design",
        depends_on=["requirements"],
        run=lambda ctx, node: codebase_analysis_agent.analyze(ctx, node, keyword="analytics", project_root=project_root),
    ))

    g.add(Node(
        id="design", name="Design the analytics response extension", stage="design",
        depends_on=["codebase_analysis"],
        run=lambda ctx, node: design_agent.propose(
            ctx, node,
            feature_name="Analytics top_referrers breakdown",
            api_contract=[{
                "method": "GET", "path": "/api/urls/{code}/analytics",
                "description": "Now also returns top_referrers: top 5 referrers by click count.",
            }],
            data_model_changes=["AnalyticsResponse gains `top_referrers: list[dict]`."],
            risks=["Referrer aggregation adds one more query per analytics call; acceptable at current scale."],
            project_root=project_root,
            mode="modify",
        ),
    ))

    g.add(Node(
        id="implementation", name="Implement top_referrers + tests", stage="implementation",
        depends_on=["design"],
        run=lambda ctx, node: implementation_agent.write_files(
            ctx, node,
            files={
                "app/shortener.py": _build_shortener_content(project_root, include_unique_referrer_count=False),
                "app/schemas.py": _build_schemas_content(project_root, include_unique_referrer_count=False),
                "tests/test_analytics_enhancement.py": _build_analytics_test_content(include_unique_referrer_count=False),
            },
            project_root=project_root,
        ),
        exit_policies=[policy.no_hardcoded_secrets, policy.dependency_allowlist],
        compensate=implementation_agent.revert_files,
    ))

    g.add(Node(
        id="testing", name="Run pytest (flaky first attempt, by design)", stage="testing",
        depends_on=["implementation"],
        run=lambda ctx, node: _flaky_run_tests(ctx, node, project_root),
        exit_policies=[policy.tests_must_pass],
        retry=RetryPolicy(max_attempts=2, backoff_seconds=0.2),
    ))

    g.add(Node(
        id="docs", name="Write API changelog entry", stage="docs",
        depends_on=["implementation"],
        run=lambda ctx, node: docs_agent.write_change_doc(
            ctx, node, design_node_id="design", project_root=project_root, doc_rel_path="docs/API_CHANGELOG.md"
        ),
    ))

    g.add(Node(
        id="release", name="Prepare release (human-approved)", stage="release",
        depends_on=["testing", "docs"], requires_approval=True, parallel_ok=False,
        entry_policies=[policy.make_release_gate(g)],
        run=lambda ctx, node: release_agent.prepare_release(
            ctx, node, project_root=project_root, release_notes_rel_path=f"runs/{ctx.run_id}/RELEASE_NOTES_part1.md"
        ),
    ))

    return g


def run_part1() -> None:
    run_id = new_run_id("brownfield-analytics")
    out_dir = run_dir(run_id)
    ctx = SharedContext(run_id=run_id, scenario="brownfield: analytics top_referrers (+ re-plan)", raw_request=PART1_REQUEST)
    graph = build_part1_graph(PROJECT_ROOT)
    event_log = EventLog(run_id=run_id, out_dir=out_dir)
    executor = Executor(graph, ctx, event_log, approver=AutoApprover(graph=graph), rollback_on_failure=True)

    print("--- brownfield part 1, initial run ---")
    result = executor.run()
    print_summary(result)

    # Simulate a stakeholder adding a requirement after seeing the first cut:
    # also report how many distinct referrers drove traffic. This changes the
    # design node's output, so we mark it (and everything downstream) stale
    # and let the executor re-plan -- requirements/codebase_analysis, whose
    # outputs did not change, are left SUCCEEDED and are not re-run.
    print("\n--- simulated requirement change: also report unique_referrer_count ---")
    ctx.record_decision(
        stage="requirements", node_id="requirements",
        summary="Stakeholder added a follow-up acceptance criterion after reviewing the first cut.",
        rationale="Also report unique_referrer_count alongside top_referrers.",
    )
    graph.nodes["design"].run = lambda ctx, node: design_agent.propose(
        ctx, node,
        feature_name="Analytics top_referrers breakdown (+ unique_referrer_count)",
        api_contract=[{
            "method": "GET", "path": "/api/urls/{code}/analytics",
            "description": "Now also returns top_referrers and unique_referrer_count.",
        }],
        data_model_changes=[
            "AnalyticsResponse gains `top_referrers: list[dict]`.",
            "AnalyticsResponse gains `unique_referrer_count: int`.",
        ],
        risks=["Referrer aggregation adds one more query per analytics call; acceptable at current scale."],
        project_root=PROJECT_ROOT, mode="modify",
    )
    graph.nodes["implementation"].run = lambda ctx, node: implementation_agent.write_files(
        ctx, node,
        files={
            "app/shortener.py": _build_shortener_content(PROJECT_ROOT, include_unique_referrer_count=True),
            "app/schemas.py": _build_schemas_content(PROJECT_ROOT, include_unique_referrer_count=True),
            "tests/test_analytics_enhancement.py": _build_analytics_test_content(include_unique_referrer_count=True),
        },
        project_root=PROJECT_ROOT,
    )
    graph.mark_stale("design")

    executor2 = Executor(graph, ctx, event_log, approver=AutoApprover(graph=graph), rollback_on_failure=True)
    result = executor2.run()
    event_log.close()
    write_report(result, os.path.join(out_dir, "REPORT.md"))
    print_summary(result)
    print(f"\nFull report: runs/{run_id}/REPORT.md")


# ---------------------------------------------------------------------------
# PART 2: security guardrail catches a defect -> automatic rollback -> fix -> re-plan
# ---------------------------------------------------------------------------

PART2_REQUEST = "Add a structured audit-log module (app/audit_log.py) that records admin actions on links."

_BAD_AUDIT_LOG = '''"""Structured audit logging for admin actions on links."""
import logging

logger = logging.getLogger("urlshortener.audit")

# TODO: wire this up to the real notification webhook
api_key = "AKIAABCDEFGHIJKLMNOP"


def record(action: str, code: str, actor: str) -> None:
    logger.info("AUDIT action=%s code=%s actor=%s", action, code, actor)
'''

_FIXED_AUDIT_LOG = '''"""Structured audit logging for admin actions on links."""
import logging
import os

logger = logging.getLogger("urlshortener.audit")

# Webhook credentials are injected via environment, never hardcoded.
WEBHOOK_TOKEN_ENV_VAR = "URLSHORT_AUDIT_WEBHOOK_TOKEN"


def record(action: str, code: str, actor: str) -> None:
    logger.info("AUDIT action=%s code=%s actor=%s", action, code, actor)
'''


def build_part2_graph(project_root: str) -> Graph:
    g = Graph()
    g.add(Node(
        id="requirements", name="Normalize the audit-log request", stage="requirements", depends_on=[],
        run=lambda ctx, node: requirements_agent.normalize(ctx, node),
    ))
    g.add(Node(
        id="implementation", name="Implement audit_log.py", stage="implementation", depends_on=["requirements"],
        run=lambda ctx, node: implementation_agent.write_files(
            ctx, node, files={"app/audit_log.py": _BAD_AUDIT_LOG}, project_root=project_root,
        ),
        exit_policies=[policy.no_hardcoded_secrets, policy.dependency_allowlist],
        compensate=implementation_agent.revert_files,
    ))
    g.add(Node(
        id="testing", name="Run pytest", stage="testing", depends_on=["implementation"],
        run=lambda ctx, node: testing_agent.run_tests(ctx, node, test_path="tests/", project_root=project_root),
        exit_policies=[policy.tests_must_pass],
    ))
    g.add(Node(
        id="release", name="Prepare release (human-approved)", stage="release", depends_on=["testing"],
        requires_approval=True, parallel_ok=False,
        entry_policies=[policy.make_release_gate(g)],
        run=lambda ctx, node: release_agent.prepare_release(
            ctx, node, project_root=project_root, release_notes_rel_path=f"runs/{ctx.run_id}/RELEASE_NOTES_part2.md"
        ),
    ))
    return g


def run_part2() -> None:
    run_id = new_run_id("brownfield-security-incident")
    out_dir = run_dir(run_id)
    ctx = SharedContext(run_id=run_id, scenario="brownfield: audit log (security guardrail + rollback + recovery)", raw_request=PART2_REQUEST)
    graph = build_part2_graph(PROJECT_ROOT)
    event_log = EventLog(run_id=run_id, out_dir=out_dir)
    executor = Executor(graph, ctx, event_log, approver=AutoApprover(graph=graph), rollback_on_failure=True)

    print("\n--- brownfield part 2: introducing a hardcoded-secret defect ---")
    result = executor.run()
    print_summary(result)
    assert result.outcome == "rolled_back", "expected the secret-scan guardrail to trigger a rollback"
    audit_path = os.path.join(PROJECT_ROOT, "app", "audit_log.py")
    if os.path.exists(audit_path):
        with open(audit_path, encoding="utf-8") as f:
            reverted_content = f.read()
        assert "AKIA" not in reverted_content, "rollback should have reverted the injected secret"
        print("  confirmed: app/audit_log.py no longer contains the injected secret after rollback")
    else:
        print("  confirmed: app/audit_log.py did not exist before this run and rollback removed it")

    print("\n--- applying the fix and re-planning from the failed node ---")
    ctx.record_decision(
        stage="implementation", node_id="implementation",
        summary="Root cause identified: hardcoded placeholder credential in audit_log.py.",
        rationale="Replaced with an environment-variable reference; re-running from the implementation node.",
    )
    graph.nodes["implementation"].run = lambda ctx, node: implementation_agent.write_files(
        ctx, node, files={"app/audit_log.py": _FIXED_AUDIT_LOG}, project_root=PROJECT_ROOT,
    )
    graph.mark_stale("implementation")
    executor2 = Executor(graph, ctx, event_log, approver=AutoApprover(graph=graph), rollback_on_failure=True)
    result = executor2.run()
    event_log.close()
    write_report(result, os.path.join(out_dir, "REPORT.md"))
    print_summary(result)
    print(f"\nFull report: runs/{run_id}/REPORT.md")


def main() -> None:
    run_part1()
    run_part2()


if __name__ == "__main__":
    main()
