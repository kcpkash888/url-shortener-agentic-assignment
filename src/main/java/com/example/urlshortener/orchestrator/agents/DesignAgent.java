package com.example.urlshortener.orchestrator.agents;

import com.example.urlshortener.orchestrator.Node;
import com.example.urlshortener.orchestrator.SharedContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Design agent: turns a normalized requirement into a concrete API/data
 * contract, and validates it against the real codebase before approving it
 * -- refuses a design that would collide with (mode "add") or is missing
 * (mode "modify") a route already defined in UrlController.java, checked by
 * reading that file, not asserted. Direct port of orchestrator/agents/design_agent.py.
 */
public final class DesignAgent {
    private DesignAgent() {}

    private static final Pattern ROUTE_RE =
            Pattern.compile("@(?:Get|Post|Put|Delete|Patch)Mapping\\(\"([^\"]*)\"\\)");

    public record Endpoint(String method, String path, String description) {}

    public static Map<String, Object> propose(
            SharedContext ctx, Node node, String featureName, List<Endpoint> apiContract,
            List<String> dataModelChanges, List<String> risks, Path projectRoot, String mode
    ) {
        Path controllerPath = projectRoot.resolve("src/main/java/com/example/urlshortener/app/UrlController.java");
        String content;
        try {
            content = Files.readString(controllerPath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> existingRoutes = new HashSet<>();
        Matcher m = ROUTE_RE.matcher(content);
        while (m.find()) existingRoutes.add(m.group(1));

        if ("add".equals(mode)) {
            List<String> collisions = apiContract.stream().map(Endpoint::path).filter(existingRoutes::contains).toList();
            if (!collisions.isEmpty()) {
                throw new IllegalStateException("design proposes route(s) that already exist and would be silently shadowed: " + collisions);
            }
        } else if ("modify".equals(mode)) {
            List<String> missing = apiContract.stream().map(Endpoint::path).filter(p -> !existingRoutes.contains(p)).toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("design claims to modify route(s) that don't exist yet: " + missing);
            }
        } else {
            throw new IllegalArgumentException("unknown design mode '" + mode + "'");
        }

        List<String> routeSummaries = apiContract.stream().map(e -> e.method() + " " + e.path()).toList();
        ctx.recordDecision(
                node.stage, node.id,
                "Approved design for '" + featureName + "': " + routeSummaries,
                "Checked proposed routes against UrlController.java's current route table -- no problems. "
                        + "Data model changes: " + dataModelChanges + ". Known risks accepted for this scope: " + risks + "."
        );

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("feature_name", featureName);
        out.put("api_contract", apiContract);
        out.put("data_model_changes", dataModelChanges);
        out.put("risks", risks);
        return out;
    }
}
