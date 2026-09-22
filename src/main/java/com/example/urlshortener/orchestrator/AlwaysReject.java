package com.example.urlshortener.orchestrator;

import java.util.Map;

/** Deterministically demonstrates the rollback/safe-stop path without relying
 * on randomness or a real human being unavailable to click approve. */
public class AlwaysReject implements Approver {
    private final String identity;
    private final String reason;

    public AlwaysReject(String reason) {
        this("auto-approver@demo-policy", reason);
    }

    public AlwaysReject(String identity, String reason) {
        this.identity = identity;
        this.reason = reason;
    }

    @Override
    public ApprovalDecision decide(SharedContext ctx, Node node, Map<String, Object> stagedResult) {
        return new ApprovalDecision(false, identity, reason);
    }
}
