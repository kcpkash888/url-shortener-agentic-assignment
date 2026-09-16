package com.example.urlshortener.orchestrator;

import java.util.Map;

@FunctionalInterface
public interface NodeFn {
    Map<String, Object> run(SharedContext ctx, Node node) throws Exception;
}
