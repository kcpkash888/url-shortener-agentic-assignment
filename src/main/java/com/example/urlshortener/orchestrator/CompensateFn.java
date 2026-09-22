package com.example.urlshortener.orchestrator;

@FunctionalInterface
public interface CompensateFn {
    void compensate(SharedContext ctx, Node node) throws Exception;
}
