package com.example.urlshortener.orchestrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A single step in an SDLC workflow graph. Mutable by design: the executor
 * updates status/attempts/lastError/lastResult in place as the run
 * progresses, and re-planning resets exactly those fields via
 * {@link Graph#markStale}.
 */
public class Node {
    public final String id;
    public final String name;
    public final String stage; // requirements | design | implementation | testing | docs | release
    public final NodeFn run;
    public final List<String> dependsOn;
    public final boolean requiresApproval;
    public final List<NamedPolicy> entryPolicies;
    public final List<NamedPolicy> exitPolicies;
    public final RetryPolicy retry;
    public final NodeFn fallback; // nullable
    public final CompensateFn compensate; // nullable
    public final boolean parallelOk;

    public volatile NodeStatus status = NodeStatus.PENDING;
    public volatile int attempts = 0;
    public volatile String lastError = null;
    public volatile Map<String, Object> lastResult = null;

    private Node(Builder b) {
        this.id = b.id;
        this.name = b.name;
        this.stage = b.stage;
        this.run = b.run;
        this.dependsOn = List.copyOf(b.dependsOn);
        this.requiresApproval = b.requiresApproval;
        this.entryPolicies = List.copyOf(b.entryPolicies);
        this.exitPolicies = List.copyOf(b.exitPolicies);
        this.retry = b.retry;
        this.fallback = b.fallback;
        this.compensate = b.compensate;
        this.parallelOk = b.parallelOk;
    }

    public static Builder builder(String id, String stage, NodeFn run) {
        return new Builder(id, stage, run);
    }

    public static class Builder {
        private final String id;
        private String name;
        private final String stage;
        private final NodeFn run;
        private List<String> dependsOn = new ArrayList<>();
        private boolean requiresApproval = false;
        private List<NamedPolicy> entryPolicies = new ArrayList<>();
        private List<NamedPolicy> exitPolicies = new ArrayList<>();
        private RetryPolicy retry = RetryPolicy.none();
        private NodeFn fallback = null;
        private CompensateFn compensate = null;
        private boolean parallelOk = true;

        Builder(String id, String stage, NodeFn run) {
            this.id = id;
            this.stage = stage;
            this.run = run;
            this.name = id;
        }

        public Builder name(String name) { this.name = name; return this; }
        public Builder dependsOn(String... ids) { this.dependsOn = List.of(ids); return this; }
        public Builder requiresApproval(boolean v) { this.requiresApproval = v; return this; }
        public Builder entryPolicy(String name, PolicyFn fn) { this.entryPolicies.add(new NamedPolicy(name, fn)); return this; }
        public Builder exitPolicy(String name, PolicyFn fn) { this.exitPolicies.add(new NamedPolicy(name, fn)); return this; }
        public Builder retry(RetryPolicy r) { this.retry = r; return this; }
        public Builder fallback(NodeFn fn) { this.fallback = fn; return this; }
        public Builder compensate(CompensateFn fn) { this.compensate = fn; return this; }
        public Builder parallelOk(boolean v) { this.parallelOk = v; return this; }

        public Node build() { return new Node(this); }
    }
}
