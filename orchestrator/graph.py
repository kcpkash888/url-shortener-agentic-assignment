"""Explicit dependency graph for an SDLC workflow.

Nodes declare their upstream dependencies (`depends_on`); the graph is the
single source of truth for what is ready to run next. Status is tracked per
node (not just pass/fail for the whole run) so that:
  * independent branches can execute in parallel once their own deps are met
    ("waves"), with the next wave acting as the synchronization barrier, and
  * re-planning can invalidate ("stale") a node and everything downstream of
    it without discarding unrelated, still-valid work.
"""
from collections import defaultdict, deque
from dataclasses import dataclass, field
from typing import Callable, Optional

from orchestrator.context import SharedContext

NodeFn = Callable[[SharedContext, "Node"], dict]
PolicyFn = Callable[[SharedContext, "Node", Optional[dict]], "PolicyResult"]

PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED, STALE, ROLLED_BACK = (
    "PENDING", "RUNNING", "SUCCEEDED", "FAILED", "SKIPPED", "STALE", "ROLLED_BACK",
)


@dataclass
class PolicyResult:
    passed: bool
    message: str = ""


@dataclass
class RetryPolicy:
    max_attempts: int = 1
    backoff_seconds: float = 0.1


@dataclass
class Node:
    id: str
    name: str
    stage: str  # requirements | design | implementation | testing | docs | release
    run: NodeFn
    depends_on: list[str] = field(default_factory=list)
    requires_approval: bool = False
    entry_policies: list[PolicyFn] = field(default_factory=list)
    exit_policies: list[PolicyFn] = field(default_factory=list)
    retry: RetryPolicy = field(default_factory=RetryPolicy)
    fallback: Optional[NodeFn] = None
    compensate: Optional[Callable[[SharedContext, "Node"], None]] = None
    parallel_ok: bool = True

    status: str = PENDING
    attempts: int = 0
    last_error: str | None = None
    # The most recent value node.run() returned, regardless of whether the exit
    # gate then accepted it. Kept separate from SharedContext.data (which only
    # ever holds *accepted* outputs downstream nodes may depend on) so that
    # `compensate` can still undo side effects from a run that failed its own
    # exit policy -- see Executor._rollback.
    last_result: Optional[dict] = None


class CycleError(Exception):
    pass


class Graph:
    def __init__(self):
        self.nodes: dict[str, Node] = {}

    def add(self, node: Node) -> "Graph":
        self.nodes[node.id] = node
        return self

    def validate(self) -> None:
        for node in self.nodes.values():
            for dep in node.depends_on:
                if dep not in self.nodes:
                    raise ValueError(f"node '{node.id}' depends on unknown node '{dep}'")
        # Kahn's algorithm to detect cycles
        indegree = {nid: 0 for nid in self.nodes}
        for node in self.nodes.values():
            for dep in node.depends_on:
                indegree[node.id] += 1
        queue = deque([nid for nid, d in indegree.items() if d == 0])
        visited = 0
        children = defaultdict(list)
        for node in self.nodes.values():
            for dep in node.depends_on:
                children[dep].append(node.id)
        while queue:
            nid = queue.popleft()
            visited += 1
            for child in children[nid]:
                indegree[child] -= 1
                if indegree[child] == 0:
                    queue.append(child)
        if visited != len(self.nodes):
            raise CycleError("dependency graph contains a cycle")

    def ready_nodes(self) -> list[Node]:
        """Nodes whose dependencies are all SUCCEEDED and which are themselves
        runnable (PENDING or STALE)."""
        ready = []
        for node in self.nodes.values():
            if node.status not in (PENDING, STALE):
                continue
            if all(self.nodes[dep].status == SUCCEEDED for dep in node.depends_on):
                ready.append(node)
        return ready

    def all_terminal(self) -> bool:
        return all(n.status in (SUCCEEDED, FAILED, SKIPPED, ROLLED_BACK) for n in self.nodes.values())

    def descendants(self, node_id: str) -> set[str]:
        children = defaultdict(list)
        for node in self.nodes.values():
            for dep in node.depends_on:
                children[dep].append(node.id)
        out: set[str] = set()
        queue = deque(children[node_id])
        while queue:
            nid = queue.popleft()
            if nid in out:
                continue
            out.add(nid)
            queue.extend(children[nid])
        return out

    def mark_stale(self, node_id: str) -> list[str]:
        """Invalidate a node and everything downstream so the executor will
        re-run exactly the affected subgraph on the next pass. Returns the
        list of node ids that were marked stale."""
        affected = [node_id] + sorted(self.descendants(node_id))
        for nid in affected:
            node = self.nodes[nid]
            if node.status in (SUCCEEDED, FAILED, SKIPPED, ROLLED_BACK):
                node.status = STALE
                node.attempts = 0
                node.last_error = None
                node.last_result = None
        return affected

    def topological_order(self) -> list[str]:
        indegree = {nid: len(n.depends_on) for nid, n in self.nodes.items()}
        children = defaultdict(list)
        for node in self.nodes.values():
            for dep in node.depends_on:
                children[dep].append(node.id)
        queue = deque(sorted([nid for nid, d in indegree.items() if d == 0]))
        order = []
        while queue:
            nid = queue.popleft()
            order.append(nid)
            for child in sorted(children[nid]):
                indegree[child] -= 1
                if indegree[child] == 0:
                    queue.append(child)
        return order
