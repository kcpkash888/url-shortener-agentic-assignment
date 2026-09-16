package com.example.urlshortener.orchestrator.agents;

import com.example.urlshortener.orchestrator.Node;
import com.example.urlshortener.orchestrator.SharedContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Implementation agent: performs real, tracked file writes on disk. Every
 * file it touches is backed up in-memory first (original content, or null
 * for a brand-new file) so that {@link #revertFiles} -- wired as this
 * node's compensate function -- can put the working tree back exactly as it
 * found it if a later stage fails and the run rolls back. Direct port of
 * orchestrator/agents/implementation_agent.py.
 */
public final class ImplementationAgent {
    private ImplementationAgent() {}

    public static Map<String, Object> writeFiles(
            SharedContext ctx, Node node, Map<String, String> files, Path projectRoot, List<String> newDependencies
    ) {
        List<String> written = new ArrayList<>();
        Map<String, String> backups = new LinkedHashMap<>(); // value null-sentinel handled via Optional-less map + marker
        Map<String, Boolean> hadPrior = new LinkedHashMap<>();
        try {
            for (var entry : files.entrySet()) {
                Path abs = projectRoot.resolve(entry.getKey());
                if (Files.exists(abs)) {
                    backups.put(entry.getKey(), Files.readString(abs));
                    hadPrior.put(entry.getKey(), true);
                } else {
                    hadPrior.put(entry.getKey(), false);
                }
                Files.createDirectories(abs.getParent());
                Files.writeString(abs, entry.getValue());
                ctx.addArtifact(entry.getKey());
                written.add(entry.getKey());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ctx.recordDecision(
                node.stage, node.id,
                "Wrote/modified " + written.size() + " file(s): " + written,
                "Implementation follows the approved design's API contract and data model changes verbatim."
        );

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("written_files", written);
        out.put("backups", backups);
        out.put("had_prior", hadPrior);
        out.put("new_dependencies", newDependencies == null ? List.of() : newDependencies);
        out.put("project_root", projectRoot.toString());
        out.put("file_contents", files); // new content itself, so exit-gate policies can inspect it
        return out;
    }

    @SuppressWarnings("unchecked")
    public static void revertFiles(SharedContext ctx, Node node) {
        Map<String, Object> result = node.lastResult;
        Path projectRoot = Path.of((String) result.get("project_root"));
        Map<String, String> backups = (Map<String, String>) result.get("backups");
        Map<String, Boolean> hadPrior = (Map<String, Boolean>) result.get("had_prior");
        try {
            for (String relPath : hadPrior.keySet()) {
                Path abs = projectRoot.resolve(relPath);
                if (Boolean.TRUE.equals(hadPrior.get(relPath))) {
                    Files.writeString(abs, backups.get(relPath));
                } else if (Files.exists(abs)) {
                    Files.delete(abs);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
