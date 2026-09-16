"""Shared plumbing for the three demo scenarios: run-id/output-dir setup and
a human-readable Markdown report writer built from a RunResult.
"""
import json
import os
import sys
import uuid
from datetime import datetime, timezone

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, PROJECT_ROOT)

RUNS_DIR = os.path.join(PROJECT_ROOT, "runs")


def new_run_id(scenario: str) -> str:
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S")
    return f"{scenario}-{ts}-{uuid.uuid4().hex[:6]}"


def run_dir(run_id: str) -> str:
    d = os.path.join(RUNS_DIR, run_id)
    os.makedirs(d, exist_ok=True)
    return d


def write_report(result, path: str) -> None:
    ctx, graph, metrics = result.ctx, result.graph, result.metrics
    lines = [
        f"# Orchestration run report: {ctx.scenario}",
        "",
        f"- run id: `{ctx.run_id}`",
        f"- outcome: **{result.outcome}**",
        f"- original request: {ctx.raw_request!r}",
        "",
        "## Node status",
        "",
        "| node | stage | status | attempts | error |",
        "|---|---|---|---|---|",
    ]
    for nid in graph.topological_order():
        n = graph.nodes[nid]
        err = (n.last_error or "").replace("|", "/")[:120]
        lines.append(f"| {n.id} | {n.stage} | {n.status} | {n.attempts} | {err} |")

    lines += ["", "## Reliability metrics", "", "```json", json.dumps(metrics.to_dict(), indent=2), "```", ""]

    lines += ["## Decision lineage", ""]
    for d in ctx.decisions:
        lines.append(f"- **[{d.stage}/{d.node_id}]** {d.summary}")
        lines.append(f"  - rationale: {d.rationale}")
        for alt in d.alternatives_considered:
            lines.append(f"  - rejected alternative: {alt}")

    lines += ["", "## Approvals", ""]
    for a in ctx.approvals:
        lines.append(f"- `{a['node_id']}`: {'APPROVED' if a['approved'] else 'WITHHELD'} by {a['approver']} -- {a['rationale']}")

    lines += ["", "## Policy violations", ""]
    if ctx.policy_violations:
        for v in ctx.policy_violations:
            lines.append(f"- `{v['node_id']}` / {v['policy']}: {v['message']}")
    else:
        lines.append("- none")

    lines += ["", "## Artifacts touched", ""]
    for a in ctx.artifacts:
        lines.append(f"- {a}")

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def print_summary(result) -> None:
    m = result.metrics.to_dict()
    print(f"\n=== {result.ctx.scenario} :: outcome={result.outcome} ===")
    for nid in result.graph.topological_order():
        n = result.graph.nodes[nid]
        print(f"  [{n.status:10s}] {n.id:28s} stage={n.stage:15s} attempts={n.attempts}")
    print(
        f"  success_rate={m['success_rate']} retries={m['retry_count']} rollbacks={m['rollback_count']} "
        f"safe_stops={m['safe_stops']} replans={m['replans']} e2e_latency_ms={m['end_to_end_latency_ms']}"
    )
