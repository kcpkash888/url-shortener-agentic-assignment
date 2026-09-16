"""Implementation agent: performs real, tracked file writes on disk.

Every file it touches is backed up in-memory first (original content, or
None for a brand-new file) so that `revert_files` -- wired as this node's
`compensate` function -- can put the working tree back exactly as it found
it if a later stage fails and the run rolls back.
"""
import os


def write_files(ctx, node, files: dict[str, str], project_root: str, new_dependencies: list[str] | None = None) -> dict:
    written = []
    backups: dict[str, str | None] = {}
    for rel_path, content in files.items():
        abs_path = os.path.join(project_root, rel_path)
        if os.path.exists(abs_path):
            with open(abs_path, encoding="utf-8") as f:
                backups[rel_path] = f.read()
        else:
            backups[rel_path] = None
        os.makedirs(os.path.dirname(abs_path), exist_ok=True)
        with open(abs_path, "w", encoding="utf-8") as f:
            f.write(content)
        ctx.add_artifact(rel_path)
        written.append(rel_path)

    ctx.record_decision(
        stage=node.stage,
        node_id=node.id,
        summary=f"Wrote/modified {len(written)} file(s): {written}",
        rationale="Implementation follows the approved design's API contract and data model changes verbatim.",
    )
    return {
        "written_files": written,
        "backups": backups,
        "new_dependencies": new_dependencies or [],
        "project_root": project_root,
        "file_contents": files,  # the new content itself, so exit-gate policies (e.g. secret scanning) can inspect it
    }


def revert_files(ctx, node) -> None:
    result = node.last_result
    project_root = result["project_root"]
    for rel_path, original in result["backups"].items():
        abs_path = os.path.join(project_root, rel_path)
        if original is None:
            if os.path.exists(abs_path):
                os.remove(abs_path)
        else:
            with open(abs_path, "w", encoding="utf-8") as f:
                f.write(original)
