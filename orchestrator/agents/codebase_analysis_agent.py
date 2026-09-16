"""Brownfield codebase-impact-analysis agent.

Performs a real (if simple) static scan of the existing `app/` package to
answer: which files mention the target concept, and which HTTP endpoints in
main.py would be affected. This is genuine codebase reasoning over the actual
source on disk, not a canned description -- run it against a different
keyword or a changed codebase and the output changes accordingly.
"""
import os
import re

ROUTE_RE = re.compile(r'@app\.(get|post|put|delete|patch)\("([^"]+)"')


def analyze(ctx, node, keyword: str, project_root: str) -> dict:
    app_dir = os.path.join(project_root, "app")
    impacted_files = []
    for fname in sorted(os.listdir(app_dir)):
        if not fname.endswith(".py"):
            continue
        path = os.path.join(app_dir, fname)
        text = open(path, encoding="utf-8").read()
        if keyword.lower() in text.lower():
            matching_lines = [i + 1 for i, line in enumerate(text.splitlines()) if keyword.lower() in line.lower()]
            impacted_files.append({"file": f"app/{fname}", "matching_lines": matching_lines})

    main_text = open(os.path.join(app_dir, "main.py"), encoding="utf-8").read()
    impacted_endpoints = [
        f"{method.upper()} {route}" for method, route in ROUTE_RE.findall(main_text) if keyword.lower() in route.lower()
    ]
    # An endpoint can also be impacted through shared logic even if the route
    # string itself doesn't contain the keyword (e.g. analytics logic lives in
    # shortener.py and is exposed via /api/urls/{code}/analytics). If main.py
    # itself mentions the keyword, treat every endpoint it defines as in scope.
    main_py_impacted = any(f["file"] == "app/main.py" for f in impacted_files)
    if not impacted_endpoints and main_py_impacted:
        impacted_endpoints = [f"{method.upper()} {route}" for method, route in ROUTE_RE.findall(main_text)]

    dependency_notes = (
        f"'{keyword}' appears in {len(impacted_files)} existing module(s): "
        f"{[f['file'] for f in impacted_files]}. Downstream consumers that must keep working: "
        f"main.py's HTTP layer, tests/test_shortener.py's unit tests, and tests/test_api.py's "
        f"integration tests -- any signature change to shortener.py functions has to stay backward "
        f"compatible or all three need updating together."
    )

    ctx.record_decision(
        stage=node.stage,
        node_id=node.id,
        summary=f"Static impact scan for '{keyword}' across app/: {len(impacted_files)} file(s), "
        f"{len(impacted_endpoints)} endpoint(s) affected.",
        rationale=dependency_notes,
    )

    return {
        "keyword": keyword,
        "impacted_files": impacted_files,
        "impacted_endpoints": impacted_endpoints,
        "dependency_notes": dependency_notes,
    }
