"""AMBIGUOUS scenario: a raw, underspecified stakeholder ask with no
acceptance criteria. The requirements agent must (1) detect the ambiguity,
(2) generate multiple concrete candidate interpretations, (3) score and
select one with a documented rationale, and (4) record the rejected
alternatives -- all before the design/implementation stages ever run. This
is the contrast case for `greenfield_run.py`, whose request is already
concrete.

The chosen interpretation is implemented as real input-validation hardening
on POST /api/urls (and, for consistency, its bulk sibling): reject URLs that
target a blocklisted domain, and reserve system-critical short codes so they
can't be squatted.

Run with:  python -m scenarios.ambiguous_run
"""
import os

from scenarios.common import PROJECT_ROOT, new_run_id, print_summary, run_dir, write_report

from orchestrator import policy
from orchestrator.agents import design_agent, docs_agent, implementation_agent, release_agent, requirements_agent, testing_agent
from orchestrator.approvals import AutoApprover
from orchestrator.context import SharedContext
from orchestrator.executor import Executor
from orchestrator.graph import Graph, Node
from orchestrator.observability import EventLog

RAW_REQUEST = "Can we make the short links a bit safer? A couple of users raised concerns."


def _build_shortener_content(project_root: str) -> str:
    path = os.path.join(project_root, "app", "shortener.py")
    with open(path, encoding="utf-8") as f:
        content = f.read()
    if "UnsafeTargetError" in content:
        return content  # already applied (idempotent re-run)

    content = content.replace(
        "from datetime import datetime, timedelta, timezone\n\nfrom app import config, db",
        "from datetime import datetime, timedelta, timezone\nfrom urllib.parse import urlparse\n\nfrom app import config, db",
    )

    content = content.replace(
        "class AliasConflictError(Exception):\n    pass\n",
        "class AliasConflictError(Exception):\n    pass\n\n\n"
        "class UnsafeTargetError(Exception):\n    pass\n\n\n"
        "class ReservedAliasError(Exception):\n    pass\n\n\n"
        'BLOCKED_DOMAINS = {"malicious-example.test", "phishing-example.test"}\n'
        'RESERVED_ALIASES = {"api", "health", "admin", "www"}\n',
    )

    content = content.replace(
        "def create_link(url: str, custom_alias: str | None = None, expires_in_days: int | None = None) -> dict:\n"
        "    if custom_alias:",
        "def create_link(url: str, custom_alias: str | None = None, expires_in_days: int | None = None) -> dict:\n"
        "    domain = urlparse(url).netloc.lower()\n"
        "    if domain in BLOCKED_DOMAINS:\n"
        "        raise UnsafeTargetError(f\"target domain '{domain}' is not allowed\")\n"
        "    if custom_alias and custom_alias.lower() in RESERVED_ALIASES:\n"
        "        raise ReservedAliasError(f\"alias '{custom_alias}' is reserved for internal use\")\n"
        "    if custom_alias:",
    )
    return content


def _build_main_content(project_root: str) -> str:
    path = os.path.join(project_root, "app", "main.py")
    with open(path, encoding="utf-8") as f:
        content = f.read()
    if "UnsafeTargetError" not in content:
        content = content.replace(
            "    try:\n"
            "        link = shortener.create_link(req.url, req.custom_alias, req.expires_in_days)\n"
            "    except shortener.AliasConflictError as e:\n"
            "        raise HTTPException(status_code=409, detail=str(e))\n"
            "    return _to_response(link)",
            "    try:\n"
            "        link = shortener.create_link(req.url, req.custom_alias, req.expires_in_days)\n"
            "    except shortener.AliasConflictError as e:\n"
            "        raise HTTPException(status_code=409, detail=str(e))\n"
            "    except (shortener.UnsafeTargetError, shortener.ReservedAliasError) as e:\n"
            "        raise HTTPException(status_code=422, detail=str(e))\n"
            "    return _to_response(link)",
        )
    if "/api/urls/bulk" in content and "except shortener.AliasConflictError as e:\n            results.append" in content:
        content = content.replace(
            "        except shortener.AliasConflictError as e:\n"
            "            results.append(BulkResultItem(success=False, error=str(e), url=item.url))",
            "        except (shortener.AliasConflictError, shortener.UnsafeTargetError, shortener.ReservedAliasError) as e:\n"
            "            results.append(BulkResultItem(success=False, error=str(e), url=item.url))",
        )
    return content


def _build_test_content() -> str:
    return '''"""Tests for the URL-safety hardening added by the ambiguous-requirement
orchestration scenario (scenarios/ambiguous_run.py)."""


def test_blocked_domain_is_rejected(client):
    resp = client.post("/api/urls", json={"url": "https://malicious-example.test/phish"})
    assert resp.status_code == 422


def test_reserved_alias_is_rejected(client):
    resp = client.post("/api/urls", json={"url": "https://example.com", "custom_alias": "api"})
    assert resp.status_code == 422


def test_safe_url_and_alias_still_work(client):
    resp = client.post("/api/urls", json={"url": "https://example.com/fine", "custom_alias": "my-brand-2"})
    assert resp.status_code == 201


def test_bulk_reports_unsafe_item_as_a_per_item_error_not_a_hard_failure(client):
    resp = client.post(
        "/api/urls/bulk",
        json={"urls": [{"url": "https://malicious-example.test/x"}, {"url": "https://example.com/y"}]},
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["results"][0]["success"] is False
    assert body["results"][1]["success"] is True
'''


def build_graph(project_root: str) -> Graph:
    g = Graph()

    g.add(Node(
        id="requirements", name="Detect ambiguity and normalize the safety request", stage="requirements",
        depends_on=[], run=lambda ctx, node: requirements_agent.normalize(ctx, node),
    ))

    g.add(Node(
        id="design", name="Design the input-validation hardening", stage="design",
        depends_on=["requirements"],
        run=lambda ctx, node: design_agent.propose(
            ctx, node,
            feature_name=ctx.get_output("requirements")["chosen_interpretation"],
            api_contract=[
                {"method": "POST", "path": "/api/urls",
                 "description": "Rejects blocklisted target domains and reserved short-code aliases with 422."},
                {"method": "POST", "path": "/api/urls/bulk",
                 "description": "Applies the same safety checks per item, consistent with the single-create endpoint."},
            ],
            data_model_changes=["No schema/table change; validation happens in app/shortener.py:create_link."],
            risks=[
                "The domain blocklist is static and requires manual maintenance -- a live threat-intel feed was "
                "considered and explicitly rejected for this change (see requirements decision lineage) as "
                "disproportionate effort for the ask as stated.",
                "Reserved-alias list is hardcoded; adding a new reserved word later requires a code change, "
                "not just configuration.",
            ],
            project_root=project_root,
            mode="modify",
        ),
    ))

    g.add(Node(
        id="implementation", name="Implement validation + tests", stage="implementation",
        depends_on=["design"],
        run=lambda ctx, node: implementation_agent.write_files(
            ctx, node,
            files={
                "app/shortener.py": _build_shortener_content(project_root),
                "app/main.py": _build_main_content(project_root),
                "tests/test_url_safety.py": _build_test_content(),
            },
            project_root=project_root,
        ),
        exit_policies=[policy.no_hardcoded_secrets, policy.dependency_allowlist],
        compensate=implementation_agent.revert_files,
    ))

    g.add(Node(
        id="testing", name="Run the full pytest suite", stage="testing",
        depends_on=["implementation"],
        run=lambda ctx, node: testing_agent.run_tests(ctx, node, test_path="tests/", project_root=project_root),
        exit_policies=[policy.tests_must_pass],
    ))

    g.add(Node(
        id="docs", name="Write API changelog entry", stage="docs",
        depends_on=["implementation"],
        run=lambda ctx, node: docs_agent.write_change_doc(
            ctx, node, design_node_id="design", project_root=project_root, doc_rel_path="docs/API_CHANGELOG.md"
        ),
    ))

    g.add(Node(
        id="release", name="Prepare release (human-approved -- safety-relevant change)", stage="release",
        depends_on=["testing", "docs"], requires_approval=True, parallel_ok=False,
        entry_policies=[policy.make_release_gate(g)],
        run=lambda ctx, node: release_agent.prepare_release(
            ctx, node, project_root=project_root, release_notes_rel_path=f"runs/{ctx.run_id}/RELEASE_NOTES.md"
        ),
    ))

    return g


def main() -> None:
    run_id = new_run_id("ambiguous")
    out_dir = run_dir(run_id)
    ctx = SharedContext(run_id=run_id, scenario="ambiguous: 'make links safer'", raw_request=RAW_REQUEST)
    graph = build_graph(PROJECT_ROOT)
    event_log = EventLog(run_id=run_id, out_dir=out_dir)
    executor = Executor(graph, ctx, event_log, approver=AutoApprover(graph=graph), rollback_on_failure=True)

    result = executor.run()
    event_log.close()
    write_report(result, os.path.join(out_dir, "REPORT.md"))
    print_summary(result)

    req_output = ctx.get_output("requirements")
    print("\n--- requirements agent output ---")
    print(f"  ambiguity_detected: {req_output['ambiguity_detected']}")
    print(f"  chosen_interpretation: {req_output['chosen_interpretation']}")
    print(f"  rejected_interpretations: {req_output['rejected_interpretations']}")
    print(f"\nFull report: runs/{run_id}/REPORT.md")


if __name__ == "__main__":
    main()
