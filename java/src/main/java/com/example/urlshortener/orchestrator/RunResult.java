package com.example.urlshortener.orchestrator;

public record RunResult(SharedContext ctx, Graph graph, RunMetrics metrics, EventLog eventLog, String outcome) {}
