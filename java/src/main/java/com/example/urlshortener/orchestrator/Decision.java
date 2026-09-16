package com.example.urlshortener.orchestrator;

import java.time.Instant;
import java.util.List;

public record Decision(
        String stage, String nodeId, String summary, String rationale,
        List<String> alternativesConsidered, Instant timestamp
) {
    public static Decision of(String stage, String nodeId, String summary, String rationale, List<String> alternatives) {
        return new Decision(stage, nodeId, summary, rationale, alternatives == null ? List.of() : alternatives, Instant.now());
    }
}
