package com.example.urlshortener.orchestrator;

import java.util.*;

/**
 * Explicit dependency graph for an SDLC workflow. See the Python port's
 * orchestrator/graph.py for the full rationale -- this is a direct,
 * behavior-preserving port.
 */
public class Graph {
    public final LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();

    public Graph add(Node node) {
        nodes.put(node.id, node);
        return this;
    }

    public void validate() {
        for (Node node : nodes.values()) {
            for (String dep : node.dependsOn) {
                if (!nodes.containsKey(dep)) {
                    throw new IllegalArgumentException("node '" + node.id + "' depends on unknown node '" + dep + "'");
                }
            }
        }
        // Kahn's algorithm to detect cycles
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> children = new HashMap<>();
        for (Node node : nodes.values()) {
            indegree.putIfAbsent(node.id, 0);
            for (String dep : node.dependsOn) {
                indegree.merge(node.id, 1, Integer::sum);
                children.computeIfAbsent(dep, k -> new ArrayList<>()).add(node.id);
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        for (var e : indegree.entrySet()) if (e.getValue() == 0) queue.add(e.getKey());
        int visited = 0;
        while (!queue.isEmpty()) {
            String nid = queue.poll();
            visited++;
            for (String child : children.getOrDefault(nid, List.of())) {
                indegree.merge(child, -1, Integer::sum);
                if (indegree.get(child) == 0) queue.add(child);
            }
        }
        if (visited != nodes.size()) {
            throw new IllegalStateException("dependency graph contains a cycle");
        }
    }

    public List<Node> readyNodes() {
        List<Node> ready = new ArrayList<>();
        for (Node node : nodes.values()) {
            if (node.status != NodeStatus.PENDING && node.status != NodeStatus.STALE) continue;
            boolean depsOk = node.dependsOn.stream().allMatch(dep -> nodes.get(dep).status == NodeStatus.SUCCEEDED);
            if (depsOk) ready.add(node);
        }
        return ready;
    }

    private Map<String, List<String>> childrenMap() {
        Map<String, List<String>> children = new HashMap<>();
        for (Node node : nodes.values()) {
            for (String dep : node.dependsOn) {
                children.computeIfAbsent(dep, k -> new ArrayList<>()).add(node.id);
            }
        }
        return children;
    }

    public Set<String> descendants(String nodeId) {
        Map<String, List<String>> children = childrenMap();
        Set<String> out = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(children.getOrDefault(nodeId, List.of()));
        while (!queue.isEmpty()) {
            String nid = queue.poll();
            if (!out.add(nid)) continue;
            queue.addAll(children.getOrDefault(nid, List.of()));
        }
        return out;
    }

    /** Invalidates a node and everything downstream so the executor will
     * re-run exactly the affected subgraph on the next pass. */
    public List<String> markStale(String nodeId) {
        List<String> affected = new ArrayList<>();
        affected.add(nodeId);
        affected.addAll(descendants(nodeId));
        for (String nid : affected) {
            Node node = nodes.get(nid);
            if (node.status == NodeStatus.SUCCEEDED || node.status == NodeStatus.FAILED
                    || node.status == NodeStatus.SKIPPED || node.status == NodeStatus.ROLLED_BACK) {
                node.status = NodeStatus.STALE;
                node.attempts = 0;
                node.lastError = null;
                node.lastResult = null;
            }
        }
        return affected;
    }

    public List<String> topologicalOrder() {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> children = childrenMap();
        for (Node node : nodes.values()) indegree.put(node.id, node.dependsOn.size());
        List<String> queue = new ArrayList<>();
        for (var e : indegree.entrySet()) if (e.getValue() == 0) queue.add(e.getKey());
        Collections.sort(queue);
        List<String> order = new ArrayList<>();
        int i = 0;
        while (i < queue.size()) {
            String nid = queue.get(i++);
            order.add(nid);
            List<String> kids = new ArrayList<>(children.getOrDefault(nid, List.of()));
            Collections.sort(kids);
            for (String child : kids) {
                indegree.merge(child, -1, Integer::sum);
                if (indegree.get(child) == 0) queue.add(child);
            }
        }
        return order;
    }
}
