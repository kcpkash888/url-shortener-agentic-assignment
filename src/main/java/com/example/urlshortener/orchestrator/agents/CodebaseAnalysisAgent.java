package com.example.urlshortener.orchestrator.agents;

import com.example.urlshortener.orchestrator.Node;
import com.example.urlshortener.orchestrator.SharedContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Brownfield codebase-impact-analysis agent: a real (if simple) static scan
 * of the existing app/ source tree to answer which files mention the target
 * concept, and which HTTP endpoints in UrlController.java would be
 * affected. Direct port of orchestrator/agents/codebase_analysis_agent.py.
 */
public final class CodebaseAnalysisAgent {
    private CodebaseAnalysisAgent() {}

    private static final Pattern ROUTE_RE =
            Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping\\(\"([^\"]*)\"\\)");

    public static Map<String, Object> analyze(SharedContext ctx, Node node, String keyword, Path projectRoot) {
        Path appDir = projectRoot.resolve("src/main/java/com/example/urlshortener/app");
        List<Map<String, Object>> impactedFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(appDir)) {
            List<Path> javaFiles = paths.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            for (Path path : javaFiles) {
                String content = Files.readString(path);
                if (content.toLowerCase().contains(keyword.toLowerCase())) {
                    List<Integer> matchingLines = new ArrayList<>();
                    String[] lines = content.split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].toLowerCase().contains(keyword.toLowerCase())) matchingLines.add(i + 1);
                    }
                    String relPath = "src/main/java/com/example/urlshortener/app/" + appDir.relativize(path).toString().replace('\\', '/');
                    impactedFiles.add(Map.of("file", relPath, "matching_lines", matchingLines));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Path controllerPath = appDir.resolve("UrlController.java");
        String controllerText;
        try {
            controllerText = Files.readString(controllerPath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> impactedEndpoints = new ArrayList<>();
        Matcher m = ROUTE_RE.matcher(controllerText);
        while (m.find()) {
            String route = m.group(2);
            if (route.toLowerCase().contains(keyword.toLowerCase())) {
                impactedEndpoints.add(m.group(1).toUpperCase() + " " + route);
            }
        }
        boolean controllerImpacted = impactedFiles.stream().anyMatch(f -> "src/main/java/com/example/urlshortener/app/UrlController.java".equals(f.get("file")));
        if (impactedEndpoints.isEmpty() && controllerImpacted) {
            Matcher m2 = ROUTE_RE.matcher(controllerText);
            while (m2.find()) impactedEndpoints.add(m2.group(1).toUpperCase() + " " + m2.group(2));
        }

        List<String> fileNames = impactedFiles.stream().map(f -> (String) f.get("file")).toList();
        String dependencyNotes = "'" + keyword + "' appears in " + impactedFiles.size() + " existing module(s): "
                + fileNames + ". Downstream consumers that must keep working: UrlController.java's HTTP layer, "
                + "ShortenerServiceTest.java's unit tests, and UrlControllerTest.java's integration tests -- any "
                + "signature change to ShortenerService.java has to stay backward compatible or all three need "
                + "updating together.";

        ctx.recordDecision(
                node.stage, node.id,
                "Static impact scan for '" + keyword + "' across app/: " + impactedFiles.size() + " file(s), "
                        + impactedEndpoints.size() + " endpoint(s) affected.",
                dependencyNotes
        );

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keyword", keyword);
        out.put("impacted_files", impactedFiles);
        out.put("impacted_endpoints", impactedEndpoints);
        out.put("dependency_notes", dependencyNotes);
        return out;
    }
}
