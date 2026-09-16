"""Design agent: turns a normalized requirement into a concrete API/data
contract, and validates it against the real codebase before approving it --
specifically, it refuses a design that would collide with a route already
defined in app/main.py, which is checked by reading that file, not asserted.
"""
import os
import re

ROUTE_RE = re.compile(r'@app\.(?:get|post|put|delete|patch)\("([^"]+)"')


def propose(
    ctx,
    node,
    feature_name: str,
    api_contract: list[dict],
    data_model_changes: list[str],
    risks: list[str],
    project_root: str,
    mode: str = "add",
) -> dict:
    """mode='add': every proposed route must be new (greenfield). mode='modify':
    every proposed route must already exist (brownfield enhancement of an
    existing endpoint) -- guards against a "brownfield" change silently
    turning into an accidental new-route addition, or vice versa."""
    main_path = os.path.join(project_root, "app", "main.py")
    with open(main_path, encoding="utf-8") as f:
        existing_routes = set(ROUTE_RE.findall(f.read()))

    if mode == "add":
        collisions = [c["path"] for c in api_contract if c["path"] in existing_routes]
        if collisions:
            raise ValueError(f"design proposes route(s) that already exist and would be silently shadowed: {collisions}")
    elif mode == "modify":
        missing = [c["path"] for c in api_contract if c["path"] not in existing_routes]
        if missing:
            raise ValueError(f"design claims to modify route(s) that don't exist yet: {missing}")
    else:
        raise ValueError(f"unknown design mode '{mode}'")

    ctx.record_decision(
        stage=node.stage,
        node_id=node.id,
        summary=f"Approved design for '{feature_name}': {[c['method'] + ' ' + c['path'] for c in api_contract]}",
        rationale=(
            f"Checked proposed routes against app/main.py's current route table -- no collisions. "
            f"Data model changes: {data_model_changes}. Known risks accepted for this scope: {risks}."
        ),
    )
    return {
        "feature_name": feature_name,
        "api_contract": api_contract,
        "data_model_changes": data_model_changes,
        "risks": risks,
    }
