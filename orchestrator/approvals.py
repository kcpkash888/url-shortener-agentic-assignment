"""Human approval checkpoints.

`Approver` is the seam between the orchestration engine and an actual human.
In this prototype `AutoApprover` stands in for a person so the three demo
scenarios can run unattended, but it is a real decision function -- it can
and does withhold approval -- and every call is logged with its rationale to
the audit trail. Swapping in a real human simply means implementing this
same `decide()` interface against a Slack/webhook/CLI-prompt backend; nothing
else in the executor changes.
"""
from dataclasses import dataclass
from typing import Protocol

from orchestrator.context import SharedContext
from orchestrator.graph import Graph, Node
from orchestrator.policy import unresolved_policy_violations


@dataclass
class ApprovalDecision:
    approved: bool
    approver: str
    rationale: str


class Approver(Protocol):
    def decide(self, ctx: SharedContext, node: Node, staged_result: dict | None) -> ApprovalDecision: ...


class AutoApprover:
    """Demo/CI stand-in for a human approval checkpoint.

    Approves only when the run has no currently-unresolved policy violations
    (see `orchestrator.policy.unresolved_policy_violations` -- a violation
    whose node has since been fixed and re-succeeded via re-planning does not
    block forever) -- i.e. it applies the same minimum bar a careful human
    reviewer would, and is auditable and replaceable rather than a rubber
    stamp.
    """

    def __init__(self, graph: Graph | None = None, identity: str = "auto-approver@demo-policy"):
        self.graph = graph
        self.identity = identity

    def decide(self, ctx: SharedContext, node: Node, staged_result: dict | None) -> ApprovalDecision:
        unresolved = unresolved_policy_violations(ctx, self.graph) if self.graph is not None else ctx.policy_violations
        if unresolved:
            return ApprovalDecision(
                approved=False,
                approver=self.identity,
                rationale=(
                    f"withheld: {len(unresolved)} unresolved policy violation(s) in this run "
                    f"(in production this would route to a named human reviewer for a manual call)"
                ),
            )
        return ApprovalDecision(
            approved=True,
            approver=self.identity,
            rationale=(
                "auto-approved under demo policy: all upstream stages succeeded and no policy "
                "violations are outstanding; in production this checkpoint would block on a human "
                "reviewer via the same decide() interface"
            ),
        )


class AlwaysReject:
    """Used in the brownfield scenario to deterministically demonstrate the
    rollback/safe-stop path without relying on randomness."""

    def __init__(self, identity: str = "auto-approver@demo-policy", reason: str = "manual hold for demo"):
        self.identity = identity
        self.reason = reason

    def decide(self, ctx: SharedContext, node: Node, staged_result: dict | None) -> ApprovalDecision:
        return ApprovalDecision(approved=False, approver=self.identity, rationale=self.reason)
