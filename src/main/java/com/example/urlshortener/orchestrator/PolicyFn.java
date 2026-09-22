package com.example.urlshortener.orchestrator;

import java.util.Map;

@FunctionalInterface
public interface PolicyFn {
    PolicyResult check(SharedContext ctx, Node node, Map<String, Object> result);
}
