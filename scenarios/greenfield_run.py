"""GREENFIELD scenario: add a brand-new capability -- bulk short-link
creation -- to the URL shortener via the orchestrator, end to end:
requirements -> design -> implementation -> (testing || docs, in parallel)
-> human-approved release.

The request is already concrete (it names the HTTP method, path, and
behavior), so the requirements agent should NOT flag it as ambiguous --
this scenario is the contrast case for `ambiguous_run.py`.

Run with:  python -m scenarios.greenfield_run
"""
import os

from scenarios.common import PROJECT_ROOT, new_run_id, print_summary, run_dir, write_report

from orchestrator import policy
from orchestrator.agents import design_agent, docs_agent, implementation_agent, release_agent, requirements_agent, testing_agent
from orchestrator.approvals import AutoApprover
from orchestrator.context import SharedContext
from orchestrator.executor import Executor
from orchestrator.graph import Graph, Node, RetryPolicy
from orchestrator.observability import EventLog

RAW_REQUEST = (
    "Add a bulk short-link creation endpoint: POST /api/urls/bulk must accept a list of up to 50 "
    "URLs and return one short link (or a per-item error) for each, reusing the existing validation "
    "and code-generation logic in app/shortener.py."
)


def _bulk_schemas_addition() -> str:
    return '''

class BulkCreateItem(BaseModel):
    url: str
    custom_alias: str | None = None
    expires_in_days: int | None = Field(None, ge=1, le=3650)


class BulkCreateRequest(BaseModel):
    urls: list[BulkCreateItem]


class BulkResultItem(BaseModel):
    success: bool
    link: LinkResponse | None = None
    error: str | None = None
    url: str | None = None


class BulkCreateResponse(BaseModel):
    results: list[BulkResultItem]
'''


def _build_schemas_content(project_root: str) -> str:
    path = os.path.join(project_root, "app", "schemas.py")
    with open(path, encoding="utf-8") as f:
        content = f.read()
    if "BulkCreateRequest" in content:
        return content  # already applied (idempotent re-run / re-plan)
    return content + _bulk_schemas_addition()


def _build_main_content(project_root: str) -> str:
    path = os.path.join(project_root, "app", "main.py")
    with open(path, encoding="utf-8") as f:
        content = f.read()
    if "/api/urls/bulk" in content:
        return content

    content = content.replace(
        "from app.schemas import AnalyticsResponse, CreateLinkRequest, LinkResponse",
        "from app.schemas import (\n"
        "    AnalyticsResponse,\n"
        "    BulkCreateRequest,\n"
        "    BulkCreateResponse,\n"
        "    BulkResultItem,\n"
        "    CreateLinkRequest,\n"
        "    LinkResponse,\n"
        ")",
    )

    bulk_endpoint = '''

@app.post("/api/urls/bulk", response_model=BulkCreateResponse, status_code=201)
def create_urls_bulk(req: BulkCreateRequest) -> BulkCreateResponse:
    if len(req.urls) > 50:
        raise HTTPException(status_code=422, detail="a bulk request may contain at most 50 urls")
    results: list[BulkResultItem] = []
    for item in req.urls:
        try:
            link = shortener.create_link(item.url, item.custom_alias, item.expires_in_days)
            results.append(BulkResultItem(success=True, link=_to_response(link)))
        except shortener.AliasConflictError as e:
            results.append(BulkResultItem(success=False, error=str(e), url=item.url))
    return BulkCreateResponse(results=results)
'''
    content += bulk_endpoint
    return content


def _build_test_content() -> str:
    return '''"""Tests for the bulk short-link creation endpoint, added by the greenfield
orchestration scenario (scenarios/greenfield_run.py)."""


def test_bulk_create_returns_link_per_item(client):
    resp = client.post(
        "/api/urls/bulk",
        json={"urls": [{"url": "https://example.com/a"}, {"url": "https://example.com/b"}]},
    )
    assert resp.status_code == 201
    body = resp.json()
    assert len(body["results"]) == 2
    assert all(r["success"] for r in body["results"])
    assert all(r["link"]["target_url"].startswith("https://example.com") for r in body["results"])


def test_bulk_create_reports_partial_failure_on_alias_conflict(client):
    client.post("/api/urls", json={"url": "https://example.com", "custom_alias": "dup"})
    resp = client.post(
        "/api/urls/bulk",
        json={
            "urls": [
                {"url": "https://example.com/x", "custom_alias": "dup"},
                {"url": "https://example.com/y"},
            ]
        },
    )
    body = resp.json()
    assert body["results"][0]["success"] is False
    assert "error" in body["results"][0] and body["results"][0]["error"]
    assert body["results"][1]["success"] is True


def test_bulk_create_rejects_over_50_items(client):
    resp = client.post("/api/urls/bulk", json={"urls": [{"url": "https://example.com"}] * 51})
    assert resp.status_code == 422
'''


def build_graph(project_root: str) -> Graph:
    g = Graph()

    g.add(Node(
        id="requirements",
        name="Normalize the bulk-create request",
        stage="requirements",
        depends_on=[],
        run=lambda ctx, node: requirements_agent.normalize(ctx, node),
    ))

    g.add(Node(
        id="design",
        name="Design the bulk-create API contract",
        stage="design",
        depends_on=["requirements"],
        run=lambda ctx, node: design_agent.propose(
            ctx, node,
            feature_name="Bulk short-link creation",
            api_contract=[{
                "method": "POST", "path": "/api/urls/bulk",
                "description": "Create up to 50 short links in a single request; per-item success/error.",
            }],
            data_model_changes=["No table/schema migration required; reuses the existing `links` table."],
            risks=[
                "A large batch is processed synchronously in-request; capped at 50 items to bound "
                "worst-case request latency.",
                "Partial failure (e.g. one alias conflict in a batch) does not roll back the other "
                "items in the batch -- each item is independent by design.",
            ],
            project_root=project_root,
        ),
    ))

    g.add(Node(
        id="implementation",
        name="Implement endpoint, schemas, and tests",
        stage="implementation",
        depends_on=["design"],
        run=lambda ctx, node: implementation_agent.write_files(
            ctx, node,
            files={
                "app/schemas.py": _build_schemas_content(project_root),
                "app/main.py": _build_main_content(project_root),
                "tests/test_bulk.py": _build_test_content(),
            },
            project_root=project_root,
            new_dependencies=[],
        ),
        exit_policies=[policy.no_hardcoded_secrets, policy.dependency_allowlist],
        compensate=implementation_agent.revert_files,
        retry=RetryPolicy(max_attempts=1),
    ))

    g.add(Node(
        id="testing",
        name="Run the full pytest suite",
        stage="testing",
        depends_on=["implementation"],
        run=lambda ctx, node: testing_agent.run_tests(ctx, node, test_path="tests/", project_root=project_root),
        exit_policies=[policy.tests_must_pass],
        retry=RetryPolicy(max_attempts=2, backoff_seconds=0.2),
    ))

    g.add(Node(
        id="docs",
        name="Write API changelog entry",
        stage="docs",
        depends_on=["implementation"],
        run=lambda ctx, node: docs_agent.write_change_doc(
            ctx, node, design_node_id="design", project_root=project_root, doc_rel_path="docs/API_CHANGELOG.md"
        ),
    ))

    g.add(Node(
        id="release",
        name="Prepare release (human-approved)",
        stage="release",
        depends_on=["testing", "docs"],
        requires_approval=True,
        entry_policies=[policy.make_release_gate(g)],
        run=lambda ctx, node: release_agent.prepare_release(
            ctx, node, project_root=project_root, release_notes_rel_path=f"runs/{ctx.run_id}/RELEASE_NOTES.md"
        ),
        parallel_ok=False,
    ))

    return g


def main() -> None:
    run_id = new_run_id("greenfield")
    out_dir = run_dir(run_id)
    ctx = SharedContext(run_id=run_id, scenario="greenfield: bulk short-link creation", raw_request=RAW_REQUEST)
    graph = build_graph(PROJECT_ROOT)
    event_log = EventLog(run_id=run_id, out_dir=out_dir)
    executor = Executor(graph, ctx, event_log, approver=AutoApprover(graph=graph), rollback_on_failure=True)

    result = executor.run()
    event_log.close()
    write_report(result, os.path.join(out_dir, "REPORT.md"))
    print_summary(result)
    print(f"\nFull report: runs/{run_id}/REPORT.md")
    print(f"Event log:   runs/{run_id}/events.jsonl")


if __name__ == "__main__":
    main()
