package com.example.urlshortener.orchestrator;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Policy guardrails: functions run at a node's entry and/or exit gate. See
 * the Python port's orchestrator/policy.py for the full rationale -- this is
 * a direct, behavior-preserving port.
 */
public final class Policies {
    private Policies() {}

    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("(?i)api[_-]?key\\s*=\\s*[\"'][A-Za-z0-9_\\-]{16,}[\"']")
    );

    private static final Set<String> DEPENDENCY_ALLOWLIST = Set.of(
            "spring-boot-starter-web", "spring-boot-starter-jdbc", "spring-boot-starter-validation", "sqlite-jdbc"
    );

    @SuppressWarnings("unchecked")
    private static void collectStrings(Object value, List<String> out) {
        if (value instanceof String s) {
            out.add(s);
        } else if (value instanceof Map<?, ?> m) {
            for (Object v : m.values()) collectStrings(v, out);
        } else if (value instanceof Iterable<?> it) {
            for (Object v : it) collectStrings(v, out);
        }
    }

    public static PolicyResult noHardcodedSecrets(SharedContext ctx, Node node, Map<String, Object> result) {
        List<String> blobs = new ArrayList<>();
        collectStrings(result, blobs);
        for (String blob : blobs) {
            for (Pattern p : SECRET_PATTERNS) {
                if (p.matcher(blob).find()) {
                    return PolicyResult.fail("potential hardcoded secret matched pattern '" + p.pattern() + "'");
                }
            }
        }
        return PolicyResult.ok();
    }

    @SuppressWarnings("unchecked")
    public static PolicyResult dependencyAllowlist(SharedContext ctx, Node node, Map<String, Object> result) {
        List<String> newDeps = result == null ? List.of() : (List<String>) result.getOrDefault("new_dependencies", List.of());
        List<String> disallowed = newDeps.stream().filter(d -> !DEPENDENCY_ALLOWLIST.contains(d)).toList();
        if (!disallowed.isEmpty()) {
            return PolicyResult.fail("dependencies not on the approved allowlist: " + disallowed);
        }
        return PolicyResult.ok();
    }

    public static PolicyResult testsMustPass(SharedContext ctx, Node node, Map<String, Object> result) {
        if (result == null) return PolicyResult.fail("no test result produced");
        Boolean passed = (Boolean) result.get("passed");
        if (passed == null || !passed) {
            return PolicyResult.fail("test suite failed: " + result.getOrDefault("summary", "no summary"));
        }
        return PolicyResult.ok();
    }

    /** A violation is resolved once the node that triggered it currently shows
     * SUCCEEDED -- fixed and re-run via re-planning -- rather than staying a
     * permanent block for the rest of the run. Shared by the release gate and
     * AutoApprover so they can't disagree about what's still "live". */
    public static List<Map<String, Object>> unresolvedPolicyViolations(SharedContext ctx, Graph graph) {
        List<Map<String, Object>> unresolved = new ArrayList<>();
        for (Map<String, Object> v : ctx.policyViolations) {
            String nodeId = (String) v.get("node_id");
            Node n = graph == null ? null : graph.nodes.get(nodeId);
            if (n == null || n.status != NodeStatus.SUCCEEDED) {
                unresolved.add(v);
            }
        }
        return unresolved;
    }

    public static NamedPolicy releaseGate(Graph graph) {
        return new NamedPolicy("release_requires_clean_policy_history", (ctx, node, result) -> {
            List<Map<String, Object>> unresolved = unresolvedPolicyViolations(ctx, graph);
            if (!unresolved.isEmpty()) {
                return PolicyResult.fail(unresolved.size() + " unresolved policy violation(s): " + unresolved);
            }
            return PolicyResult.ok();
        });
    }
}
