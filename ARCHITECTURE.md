# Architecture

Two independent systems live in this repo:

1. **`app/`** -- the URL shortener service itself (Spring Boot + SQLite).
2. **`orchestrator/`** -- a general-purpose agentic SDLC orchestration
   engine. It knows nothing about URL shorteners; it knows about nodes,
   dependencies, gates, retries, and audit trails. `scenarios/*.java` wire
   it up to `app/`-specific "agents" to actually build the three demo
   features.

This split matters: the orchestrator is the part the assignment calls the
"critical differentiator," and it's built to be reusable for a completely
different codebase without changing a line of `orchestrator/`.

## Component map

```
scenarios/*.java  --build graph-->  orchestrator.Graph (Nodes + edges)
                                          |
                                          v
                                orchestrator.Executor
                     (reads graph, drives waves, enforces gates)
                       |        |         |          |
                       v        v         v          v
                  entryPolicies |   requiresApproval  |
                  (Policies.java)|    (Approver)       |
                                v                      v
                          node.run(ctx, node)    exitPolicies
                        (orchestrator/agents/*)     (Policies.java)
                                          |
                                          v
                          orchestrator.SharedContext
                       (outputs, decision lineage, artifacts, approvals)
                                          |
                                          v
                          orchestrator.EventLog
                            (runs/<run_id>/events.jsonl)
                                          |
                                          v
                          orchestrator.MetricsCalculator.compute
                          (success rate, retries, rollbacks, MTTR, latency)
```

## The dependency graph, entry/exit gates

`orchestrator/Graph.java`'s `Graph` holds `Node`s with explicit `dependsOn`
lists -- there is no implicit ordering. A `Node` carries:

- `entryPolicies` -- checked *before* `node.run()` executes; a failure
  blocks the node from running at all (e.g. `Policies.releaseGate(graph)`
  refuses to even attempt a release while a policy violation is
  outstanding).
- `exitPolicies` -- checked *after* `node.run()` returns, against its
  result (e.g. `Policies.testsMustPass` reads the testing agent's real
  `mvn test` result; `Policies.noHardcodedSecrets` scans every file the
  implementation agent wrote for secret-shaped strings).
- `requiresApproval` -- a human (or `AutoApprover` standing in for one,
  see below) is asked before the node runs.
- `retry` / `fallback` / `compensate` -- see "Failure handling" below.

A node's status (`PENDING -> RUNNING -> SUCCEEDED|FAILED`, or `STALE` /
`SKIPPED` / `ROLLED_BACK`, see `NodeStatus.java`) is tracked individually,
not just pass/fail for the whole run -- this is what makes partial
re-execution possible (see re-planning below).

## Sequential and parallel execution with synchronization

`Executor.run()` (`orchestrator/Executor.java`) loops: at each iteration it
asks the graph for the current **wave** -- every node whose dependencies
have all `SUCCEEDED` and which hasn't run yet (`Graph.readyNodes()`). Nodes
in the wave marked `parallelOk=true` (the default) execute concurrently in
a `ThreadPoolExecutor`; the loop does not advance to the next wave until
the whole current wave has resolved. In every scenario, `testing` and
`docs` both depend only on `implementation` and not on each other, so they
run side by side; `release` depends on both, so it is the synchronization
barrier. This is a real (if small-scale) demonstration of "non-linear,
stateful execution" rather than a fixed linear chain.

## Cross-stage context and decision lineage

`orchestrator/SharedContext.java`'s `SharedContext` is threaded through
every node call. Two things live there that matter for the assignment
specifically:

- **`ctx.getOutput(nodeId)`** -- accepted node outputs, so e.g. the docs
  agent reads the design node's exact API contract to generate the
  changelog entry (it literally cannot document an endpoint the design node
  didn't approve).
- **`ctx.decisions`** -- an ordered list of `Decision` records
  (`stage`, `summary`, `rationale`, `alternativesConsidered`), written by
  every agent, not just the interesting ones. This is the "decision
  lineage": for the ambiguous scenario it holds every rejected
  interpretation and why it lost; for brownfield it holds the impact
  analysis and the mid-run replanning decision. It's rendered verbatim into
  each run's `REPORT.md` and the release note.

## Approvals -- human checkpoints

`orchestrator/Approver.java` defines an `Approver` interface with one
method, `decide(ctx, node, stagedResult) -> ApprovalDecision`.
`AutoApprover` is the demo/CI stand-in used by all three scenarios: it
approves only when there are no *currently unresolved* policy violations
in the run (see "Guardrails" below), and it genuinely withholds approval
when there are -- this is exercised for real in the brownfield
security-incident run. A real deployment swaps `AutoApprover` for an
implementation of the same `decide()` method backed by a Slack approval, a
web UI, or a CLI prompt; nothing in `Executor.java` changes.

## Guardrails (policy engine)

`orchestrator/Policies.java` holds the concrete guardrails used in this
repo:

| policy | gate | checks |
|---|---|---|
| `noHardcodedSecrets` | exit | recursively scans everything a node returned (including nested file-content maps) for AWS-key-shaped strings, PEM private key headers, `api_key = "..."` literals |
| `dependencyAllowlist` | exit | any `new_dependencies` an implementation node introduces must be on a pre-approved list |
| `testsMustPass` | exit | the testing agent's real `mvn test` result must report success |
| `releaseGate(graph)` | entry (release node) | refuses to proceed while any policy violation is still *unresolved* -- see below |

**Resolution, not permanent blocking.** A naive "if there's ever been a
violation, block forever" rule would make recovery impossible to
demonstrate: fix the bug, re-run, and the release gate would still refuse
because the violation happened *once*. `Policies.unresolvedPolicyViolations
(ctx, graph)` instead treats a violation as resolved once the node that
caused it currently shows `SUCCEEDED` (i.e. it was fixed and re-run via
re-planning). The full history stays in `ctx.policyViolations` for the
audit trail regardless -- only the release gate's live-vs-resolved judgment
changes. `AutoApprover` and `releaseGate` share this exact function so they
can't disagree with each other.

## Failure handling: bounded retries, fallback, rollback, safe-stop

Inside `Executor.executeNode`:

1. Entry gates run. A failure here fails the node immediately (no retry --
   a policy violation isn't transient).
2. The approval checkpoint runs, if configured.
3. `node.run()` executes, up to `node.retry.maxAttempts()` times with a
   linear backoff. An exception, or an exit-policy rejection of the
   result, both count as a failed attempt and are retried the same way.
   The brownfield scenario demonstrates a transient failure recovering on
   retry without any code change (a simulated CI hiccup).
4. If all attempts are exhausted and a `fallback` callable is configured,
   it runs once as a last resort.
5. If the node still hasn't succeeded, it's `FAILED`.

When a node in the current wave ends up `FAILED`, `Executor.run()` takes
one of two paths for the whole run, chosen per-executor via
`rollbackOnFailure`:

- **Rollback** (`Executor.rollback`): every node with a `compensate`
  callable and a recorded `lastResult` -- including the node that just
  failed, since it may have had side effects (e.g. written a bad file)
  *before* its exit gate rejected it -- is compensated in reverse
  topological order. `ImplementationAgent.revertFiles`'s compensate
  function restores each file's pre-write backup (or deletes it, if the
  node created a new file). Every remaining `PENDING`/`STALE` node is
  marked `SKIPPED`.
- **Safe-stop** (`Executor.safeStop`): no compensation; every remaining
  node is marked `SKIPPED` and execution halts, leaving whatever already
  succeeded in place as the last-known-good state. (Not exercised by the
  demo scenarios, which all use rollback, but it's the same code path with
  `rollbackOnFailure=false`.)

This is exercised for real in `BrownfieldRun.java` part 2: a deliberately
planted hardcoded-secret defect fails `noHardcodedSecrets`, and the file it
wrote is actually reverted on disk.

## Dynamic re-planning

`Graph.markStale(nodeId)` walks the graph's descendants and resets that
node plus everything downstream to `STALE` (clearing `attempts`,
`lastError`, and `lastResult`). Calling `Executor.run()` again on the same
graph/context picks up exactly where the stale set begins --
`Graph.readyNodes()` only ever returns `PENDING`/`STALE` nodes, so anything
still `SUCCEEDED` (e.g. `requirements`, `codebase_analysis`) is left alone
and not re-executed. Both brownfield sub-scenarios exercise this: part 1
simulates a stakeholder adding an acceptance criterion mid-flow (re-plans
from `design` downward); part 2 simulates fixing the root cause of a
rollback (re-plans from `implementation` downward) and the metrics module
picks up a real MTTR from the gap between the two runs.

## Observability, audit trail, and metrics

`orchestrator/EventLog.java`'s `EventLog` writes one JSON object per line
to `runs/<run_id>/events.jsonl`, flushed immediately, for every state
transition (node started/retried/succeeded/failed, policy violations,
approval decisions, rollback/safe-stop, replans). It's a complete,
independently-replayable trace -- `orchestrator/MetricsCalculator.java`'s
`compute` derives every reliability number (success rate, retry/rollback
counts, safe-stop count, replan count, approvals granted vs. withheld,
per-stage and end-to-end latency, and MTTR per node measured as the gap
between a `node_failed` and the next `node_succeeded` for that node) purely
by scanning this log plus final node statuses -- never from separate
counters kept during execution -- so the metrics can never drift from what
actually happened, and can be recomputed for any historical run.
`scenarios/Common.java`'s `writeReport` renders the same information as a
human-readable `REPORT.md` per run.

## Agents vs. LLM calls

Each class under `orchestrator/agents/` exposes a plain static Java method,
not an LLM call. `RequirementsAgent.normalize`, for instance, uses a small
trigger-term table plus an impact/effort scoring heuristic to do what an
LLM-backed requirements agent would: flag ambiguity, generate candidate
interpretations, and pick one with a documented rationale. This is an
honest trade-off for a prototype that has to run deterministically and
offline in an interview setting, not a claim that a heuristic table is a
substitute for language understanding at scale. Two things make this a
defensible seam rather than a shortcut: (1) every agent method's signature
is `(ctx, node, ...) -> Map<String, Object>` and does its real, substantive
work against the actual codebase -- `CodebaseAnalysisAgent.analyze` really
parses `src/main/java/.../app/` with regex, `TestingAgent.runTests` really
shells out to `mvn test`, `ImplementationAgent.writeFiles` really writes
files to disk -- so swapping the *reasoning* step (which interpretation to
choose, what code to write) for an actual model call is a localized change
inside one method, not a rewrite of the graph/executor/policy layer; and
(2) the requirements agent's scoring table and the design agent's
route-collision check are exactly the kind of structured judgment call an
LLM call would also need grounding rules for -- writing them out explicitly
here is what "policy guardrails" and "engineering judgment" mean in a
codebase an LLM agent would actually have to operate inside.

## Key design decisions and why

- **SQLite behind a single-connection Hikari pool, not one connection per
  request.** `application.properties` caps
  `spring.datasource.hikari.maximum-pool-size` at 1: SQLite serializes
  writes at the file level, so a bigger pool just trades "database is
  locked" errors for connection-pool contention instead of removing the
  bottleneck. This trades a little write concurrency for correctness
  simplicity, the right call at this scale (see [TESTING.md](TESTING.md)
  for the concrete bug this avoids).
- **Node output vs. node "last result" are different things
  (`ctx.getOutput(nodeId)` vs. `node.lastResult`).** A node that fails its
  own exit policy has already run and may have side effects that need
  undoing, but its result must never become visible to downstream nodes
  (which require `SUCCEEDED`, not just "ran"). Keeping these separate is
  what makes rollback-of-a-failing-node's-own-writes possible without also
  letting a rejected result leak downstream.
- **Nodes are immutable; re-planning replaces them, not mutates them.**
  `Node.run` is `final`, so simulating a stakeholder changing a requirement
  mid-flow means building a *new* `Node` with the new lambda and doing
  `graph.nodes.put("design", newNode)` to replace it in the graph's map --
  the replacement node starts at its default `PENDING` status, which
  `readyNodes()` treats exactly like `STALE`. `BrownfieldRun.java` does
  this for real.
- **Scenarios are not naively idempotent, on purpose.** Greenfield's design
  node genuinely refuses to "add" a route that already exists -- that's the
  same guardrail that should fire if a second, unrelated PR tried to add a
  route that already shipped. `scenarios/ResetDemoState.java` exists so a
  grader can replay the demo repeatably, rather than the guardrail being
  silently weakened to tolerate re-runs.
