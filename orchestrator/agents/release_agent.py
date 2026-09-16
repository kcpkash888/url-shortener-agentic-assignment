"""Release-readiness agent: the last node in every scenario graph. By the
time it runs, the entry-gate policy `release_requires_clean_policy_history`
has already refused to let it start if any policy violation is outstanding,
and its own `requires_approval=True` flag means it only executes at all
after the approval checkpoint has granted access -- so reaching this
function's body is itself evidence the run is clean. It compiles that
evidence into a release note.
"""
import os
from datetime import datetime, timezone


def prepare_release(ctx, node, project_root: str, release_notes_rel_path: str) -> dict:
    lines = [
        f"# Release note -- {ctx.scenario} (run {ctx.run_id})",
        "",
        f"Generated {datetime.now(timezone.utc).isoformat()}",
        "",
        f"**Original request:** {ctx.raw_request}",
        "",
        "## Decision lineage",
        "",
    ]
    for d in ctx.decisions:
        lines.append(f"- **[{d.stage}/{d.node_id}]** {d.summary}")
        lines.append(f"  - rationale: {d.rationale}")
        for alt in d.alternatives_considered:
            lines.append(f"  - rejected alternative: {alt}")
    lines += ["", "## Artifacts touched", ""]
    for a in ctx.artifacts:
        lines.append(f"- {a}")
    lines += ["", "## Approvals", ""]
    for a in ctx.approvals:
        lines.append(f"- node `{a['node_id']}`: {'APPROVED' if a['approved'] else 'WITHHELD'} by {a['approver']} -- {a['rationale']}")
    lines += ["", "## Policy violations recorded during this run", ""]
    if ctx.policy_violations:
        for v in ctx.policy_violations:
            lines.append(f"- `{v['node_id']}` / {v['policy']}: {v['message']}")
    else:
        lines.append("- none")
    content = "\n".join(lines) + "\n"

    abs_path = os.path.join(project_root, release_notes_rel_path)
    os.makedirs(os.path.dirname(abs_path), exist_ok=True)
    with open(abs_path, "w", encoding="utf-8") as f:
        f.write(content)
    ctx.add_artifact(release_notes_rel_path)

    ctx.record_decision(
        stage=node.stage,
        node_id=node.id,
        summary="Marked release-ready and wrote release note.",
        rationale="Reached this node only because the entry-gate policy found zero unresolved policy "
        "violations and the approval checkpoint had already granted access.",
    )
    return {"release_ready": True, "release_note_path": release_notes_rel_path}
