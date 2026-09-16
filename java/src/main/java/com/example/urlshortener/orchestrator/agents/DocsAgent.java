package com.example.urlshortener.orchestrator.agents;

import com.example.urlshortener.orchestrator.Node;
import com.example.urlshortener.orchestrator.SharedContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Docs agent: writes real Markdown to disk describing what changed, pulled
 * straight from the design node's output so the docs cannot drift from the
 * API contract that was actually implemented. Direct port of
 * orchestrator/agents/docs_agent.py.
 */
public final class DocsAgent {
    private DocsAgent() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> writeChangeDoc(
            SharedContext ctx, Node node, String designNodeId, Path projectRoot, String docRelPath
    ) {
        Map<String, Object> design = ctx.getOutput(designNodeId);
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(design.get("feature_name")).append("\n\n");
        sb.append("_Generated ").append(Instant.now()).append(" by the docs agent, run `").append(ctx.runId).append("`._\n\n");
        sb.append("### API contract\n\n");
        for (Object o : (List<Object>) design.get("api_contract")) {
            DesignAgent.Endpoint e = (DesignAgent.Endpoint) o;
            sb.append("- `").append(e.method()).append(" ").append(e.path()).append("` -- ").append(e.description()).append("\n");
        }
        sb.append("\n### Data model changes\n\n");
        for (Object c : (List<Object>) design.get("data_model_changes")) sb.append("- ").append(c).append("\n");
        sb.append("\n### Known risks\n\n");
        for (Object r : (List<Object>) design.get("risks")) sb.append("- ").append(r).append("\n");
        sb.append("\n");

        String content = sb.toString();
        Path abs = projectRoot.resolve(docRelPath);
        try {
            Files.createDirectories(abs.getParent());
            if (Files.exists(abs)) {
                Files.writeString(abs, "\n---\n\n" + content, java.nio.file.StandardOpenOption.APPEND);
            } else {
                Files.writeString(abs, "# API Changelog\n\n" + content);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ctx.addArtifact(docRelPath);

        ctx.recordDecision(
                node.stage, node.id,
                "Documented '" + design.get("feature_name") + "' in " + docRelPath,
                "Doc content is generated directly from the design node's approved API contract, so it cannot "
                        + "describe an endpoint that wasn't actually implemented."
        );

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("doc_path", docRelPath);
        out.put("content", content);
        return out;
    }
}
