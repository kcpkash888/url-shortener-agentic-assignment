package com.example.urlshortener.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Append-only, structured event log for a workflow run -- the audit trail.
 * Every state transition is written as one JSON object per line, flushed
 * immediately, to {@code runs/<run_id>/events.jsonl}.
 */
public class EventLog implements AutoCloseable {
    public final String runId;
    private final Path path;
    public final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
    private final PrintWriter writer;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object lock = new Object();

    public EventLog(String runId, Path outDir) {
        this.runId = runId;
        try {
            Files.createDirectories(outDir);
            this.path = outDir.resolve("events.jsonl");
            this.writer = new PrintWriter(Files.newBufferedWriter(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Map<String, Object> emit(String eventType, String nodeId, Map<String, Object> details) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("nanos", System.nanoTime());
        event.put("run_id", runId);
        event.put("event_type", eventType);
        event.put("node_id", nodeId);
        if (details != null) event.putAll(details);
        synchronized (lock) {
            events.add(event);
            try {
                writer.println(mapper.writeValueAsString(event));
                writer.flush();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return event;
    }

    public Map<String, Object> emit(String eventType) {
        return emit(eventType, null, Map.of());
    }

    @Override
    public void close() {
        writer.close();
    }
}
