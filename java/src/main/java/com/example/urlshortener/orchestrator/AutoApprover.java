package com.example.urlshortener.orchestrator;

import java.util.List;
import java.util.Map;

/**
 * Demo/CI stand-in for a human approval checkpoint. Approves only when the
 * run has no currently-unresolved policy violations -- it can and does
 * withhold approval, and every call is logged with its rationale.
 */
public class AutoApprover implements Approver {
    private final Graph graph;
    private final String identity;

    public AutoApprover(Graph graph) {
        this(graph, "auto-approver@demo-policy");
    }

    public AutoApprover(Graph graph, String identity) {
        this.graph = graph;
        this.identity = identity;
    }

    @Override
    public ApprovalDecision decide(SharedContext ctx, Node node, Map<String, Object> stagedResult) {
        List<Map<String, Object>> unresolved = Policies.unresolvedPolicyViolations(ctx, graph);
        if (!unresolved.isEmpty()) {
            return new ApprovalDecision(false, identity,
                    "withheld: " + unresolved.size() + " unresolved policy violation(s) in this run "
                            + "(in production this would route to a named human reviewer for a manual call)");
        }
        return new ApprovalDecision(true, identity,
                "auto-approved under demo policy: all upstream stages succeeded and no policy violations are "
                        + "outstanding; in production this checkpoint would block on a human reviewer via the same "
                        + "decide() interface");
    }
}
