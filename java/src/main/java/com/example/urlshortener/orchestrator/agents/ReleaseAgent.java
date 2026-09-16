package com.example.urlshortener.orchestrator.agents;

import com.example.urlshortener.orchestrator.Decision;
import com.example.urlshortener.orchestrator.Node;
import com.example.urlshortener.orchestrator.SharedContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Release-readiness agent: the last node in every scenario graph. By the
 * time it runs, the entry-gate release policy has already refused to let it
 * start if any policy violation is outstanding, and its own
 * requiresApproval=true flag means it only executes at all after the
 * approval checkpoint has granted access -- so reaching this function's body
 * is itself evidence the run is clean. Direct port of
 * orchestrator/agents/release_agent.py.
 */
public final class ReleaseAgent {
    private ReleaseAgent() {}

    public static Map<String, Object> prepareRelease(SharedContext ctx, Node node, Path projectRoot, String releaseNotesRelPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Release note -- ").append(ctx.scenario).append(" (run ").append(ctx.runId).append(")\n\n");
        sb.append("Generated ").append(Instant.now()).append("\n\n");
        sb.append("**Original request:** ").append(ctx.rawRequest).append("\n\n");
        sb.append("## Decision lineage\n\n");
        for (Decision d : ctx.decisions) {
            sb.append("- **[").append(d.stage()).append("/").append(d.nodeId()).append("]** ").append(d.summary()).append("\n");
            sb.append("  - rationale: ").append(d.rationale()).append("\n");
            for (String alt : d.alternativesConsidered()) sb.append("  - rejected alternative: ").append(alt).append("\n");
        }
        sb.append("\n## Artifacts touched\n\n");
        for (String a : ctx.artifacts) sb.append("- ").append(a).append("\n");
        sb.append("\n## Approvals\n\n");
        for (Map<String, Object> a : ctx.approvals) {
            sb.append("- node `").append(a.get("node_id")).append("`: ")
                    .append(Boolean.TRUE.equals(a.get("approved")) ? "APPROVED" : "WITHHELD")
                    .append(" by ").append(a.get("approver")).append(" -- ").append(a.get("rationale")).append("\n");
        }
        sb.append("\n## Policy violations recorded during this run\n\n");
        if (ctx.policyViolations.isEmpty()) {
            sb.append("- none\n");
        } else {
            for (Map<String, Object> v : ctx.policyViolations) {
                sb.append("- `").append(v.get("node_id")).append("` / ").append(v.get("policy")).append(": ").append(v.get("message")).append("\n");
            }
        }

        Path abs = projectRoot.resolve(releaseNotesRelPath);
        try {
            Files.createDirectories(abs.getParent());
            Files.writeString(abs, sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ctx.addArtifact(releaseNotesRelPath);

        ctx.recordDecision(
                node.stage, node.id,
                "Marked release-ready and wrote release note.",
                "Reached this node only because the entry-gate policy found zero unresolved policy violations "
                        + "and the approval checkpoint had already granted access."
        );

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("release_ready", true);
        out.put("release_note_path", releaseNotesRelPath);
        return out;
    }
}
