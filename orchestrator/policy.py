"""Policy guardrails: callables run at a node's entry and/or exit gate.

Each policy inspects the shared context (and, at exit, the node's own result)
and returns a PolicyResult. A failed entry policy blocks execution entirely
(the node never runs); a failed exit policy fails the node's attempt as if
its own logic had raised, so it is subject to the same retry/fallback/
rollback handling as any other failure. This is the mechanism the assignment
calls "policy guardrails for security, compliance, and change control" --
concretely, below.
"""
import re

from orchestrator.graph import PolicyResult

SECRET_PATTERNS = [
    re.compile(r"AKIA[0-9A-Z]{16}"),  # AWS access key id shape
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"(?i)api[_-]?key\s*=\s*['\"][A-Za-z0-9_\-]{16,}['\"]"),
]

DEPENDENCY_ALLOWLIST = {"fastapi", "uvicorn", "pydantic", "httpx", "pytest"}


def _collect_strings(value, out: list[str]) -> None:
    if isinstance(value, str):
        out.append(value)
    elif isinstance(value, dict):
        for v in value.values():
            _collect_strings(v, out)
    elif isinstance(value, (list, tuple, set)):
        for v in value:
            _collect_strings(v, out)


def no_hardcoded_secrets(ctx, node, result) -> PolicyResult:
    """Exit gate: recursively scan everything the node produced -- including
    nested dicts like implementation_agent's `file_contents` -- for
    secret-shaped strings."""
    text_blobs: list[str] = []
    _collect_strings(result, text_blobs)
    for blob in text_blobs:
        for pattern in SECRET_PATTERNS:
            if pattern.search(blob):
                return PolicyResult(False, f"potential hardcoded secret matched pattern {pattern.pattern!r}")
    return PolicyResult(True)


def dependency_allowlist(ctx, node, result) -> PolicyResult:
    """Exit gate: new dependencies introduced by implementation must be pre-approved."""
    new_deps = (result or {}).get("new_dependencies", [])
    disallowed = [d for d in new_deps if d not in DEPENDENCY_ALLOWLIST]
    if disallowed:
        return PolicyResult(False, f"dependencies not on the approved allowlist: {disallowed}")
    return PolicyResult(True)


def tests_must_pass(ctx, node, result) -> PolicyResult:
    """Exit gate: a testing-stage node must report all tests green before downstream
    (docs/release) work is allowed to treat the change as valid."""
    if result is None:
        return PolicyResult(False, "no test result produced")
    if not result.get("passed", False):
        return PolicyResult(False, f"test suite failed: {result.get('summary', 'no summary')}")
    return PolicyResult(True)


def unresolved_policy_violations(ctx, graph) -> list[dict]:
    """A violation is considered resolved once the node that triggered it
    currently shows SUCCEEDED status -- i.e. it was fixed and re-run
    successfully via re-planning -- rather than staying a permanent black
    mark for the rest of the run. The full history stays in
    ctx.policy_violations for the audit trail regardless; this is just what's
    still *live*. Shared by the release entry-gate policy and by
    AutoApprover, which must agree on what counts as clean.
    """
    return [
        v for v in ctx.policy_violations
        if graph.nodes.get(v["node_id"]) is None or graph.nodes[v["node_id"]].status != "SUCCEEDED"
    ]


def make_release_gate(graph):
    """Entry gate factory for the release node: refuse to proceed if any
    currently-unresolved policy violation exists (see `unresolved_policy_violations`)."""

    def _gate(ctx, node, result) -> PolicyResult:
        unresolved = unresolved_policy_violations(ctx, graph)
        if unresolved:
            return PolicyResult(
                False,
                f"{len(unresolved)} unresolved policy violation(s): "
                f"{[(v['node_id'], v['policy']) for v in unresolved]}",
            )
        return PolicyResult(True)

    return _gate
