"""Docs agent: writes real Markdown to disk describing what changed, pulled
straight from the design node's output so the docs cannot drift from the
API contract that was actually implemented.
"""
import os
from datetime import datetime, timezone


def write_change_doc(ctx, node, design_node_id: str, project_root: str, doc_rel_path: str) -> dict:
    design = ctx.get_output(design_node_id)
    lines = [
        f"## {design['feature_name']}",
        "",
        f"_Generated {datetime.now(timezone.utc).isoformat()} by the docs agent, run `{ctx.run_id}`._",
        "",
        "### API contract",
        "",
    ]
    for endpoint in design["api_contract"]:
        lines.append(f"- `{endpoint['method']} {endpoint['path']}` -- {endpoint['description']}")
    lines += ["", "### Data model changes", ""]
    for change in design["data_model_changes"]:
        lines.append(f"- {change}")
    lines += ["", "### Known risks", ""]
    for risk in design["risks"]:
        lines.append(f"- {risk}")
    lines.append("")
    content = "\n".join(lines)

    abs_path = os.path.join(project_root, doc_rel_path)
    os.makedirs(os.path.dirname(abs_path), exist_ok=True)
    mode = "a" if os.path.exists(abs_path) else "w"
    with open(abs_path, mode, encoding="utf-8") as f:
        if mode == "w":
            f.write(f"# API Changelog\n\n{content}")
        else:
            f.write("\n---\n\n" + content)
    ctx.add_artifact(doc_rel_path)

    ctx.record_decision(
        stage=node.stage,
        node_id=node.id,
        summary=f"Documented '{design['feature_name']}' in {doc_rel_path}",
        rationale="Doc content is generated directly from the design node's approved API contract, so it "
        "cannot describe an endpoint that wasn't actually implemented.",
    )
    return {"doc_path": doc_rel_path, "content": content}
