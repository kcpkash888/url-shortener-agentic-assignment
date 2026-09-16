package com.example.urlshortener.orchestrator;

import java.util.Map;

/**
 * The seam between the orchestration engine and an actual human. Swapping
 * a real human in means implementing this interface against a Slack/webhook/
 * CLI-prompt backend; nothing else in the executor changes.
 */
public interface Approver {
    ApprovalDecision decide(SharedContext ctx, Node node, Map<String, Object> stagedResult);
}
