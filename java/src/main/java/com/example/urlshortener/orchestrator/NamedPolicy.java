package com.example.urlshortener.orchestrator;

/** Wraps a PolicyFn with a name so audit events/policy-violation records can
 * identify which guardrail fired, without relying on lambda class names. */
public record NamedPolicy(String name, PolicyFn fn) {
    public PolicyResult check(SharedContext ctx, Node node, java.util.Map<String, Object> result) {
        return fn.check(ctx, node, result);
    }
}
