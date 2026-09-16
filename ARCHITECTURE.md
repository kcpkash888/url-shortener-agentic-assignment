# Architecture

Two independent systems live in this repo:

1. **`app/`** -- the URL shortener service itself (FastAPI + SQLite).
2. **`orchestrator/`** -- a general-purpose agentic SDLC orchestration
   engine. It knows nothing about URL shorteners; it knows about nodes,
   dependencies, gates, retries, and audit trails. `scenarios/*.py` wire it
   up to `app/`-specific "agents" to actually build the three demo features.

This split matters: the orchestrator is the part the assignment calls the
"critical differentiator," and it's built to be reusable for a completely
different codebase without changing a line of `orchestrator/`.

## Component map

```
scenarios/*.py  --build graph-->  orchestrator.graph.Graph (Nodes + edges)
                                          |
                                          v
                               orchestrator.executor.Executor
                     (reads graph, drives waves, enforces gates)
                       |        |         |          |
                       v        v         v          v
                 entry_policies |   requires_approval |
                  (policy.py)   |    (approvals.py)   |
                                v                      v
                          node.run(ctx, node)    exit_policies
                        (orchestrator/agents/*)     (policy.py)
                                          |
                                          v
                          orchestrator.context.SharedContext
                       (outputs, decision lineage, artifacts, approvals)
                                          |
                                          v
                          orchestrator.observability.EventLog
                            (runs/<run_id>/events.jsonl)
                                          |
                                          v
                            orchestrator.metrics.compute_metrics
                          (success rate, retries, rollbacks, MTTR, latency)
```

## The dependency graph, entry/exit gates

`orchestrator/graph.py`'s `Graph` holds `Node`s with explicit `depends_on`
lists -- there is no implicit ordering. A `Node` carries:

- `entry_policies` -- checked *before* `node.run()` executes; a failure
  blocks the node from running at all (e.g. `release_requires_clean_policy_history`
  refuses to even attempt a release while a policy violation is outstanding).
- `exit_policies` -- checked *after* `node.run()` returns, against its
  result (e.g. `tests_must_pass` reads the testing agent's real pytest
  result; `no_hardcoded_secrets` scans every file the implementation agent
  wrote for secret-shaped strings).
- `requires_approval` -- a human (or `AutoApprover` standing in for one,
  see below) is asked before the node runs.
- `retry` / `fallback` / `compensate` -- see "Failure handling" below.

A node's status (`PENDING -> RUNNING -> SUCCEEDED|FAILED`, or `STALE` /
`SKIPPED` / `ROLLED_BACK`) is tracked individually, not just pass/fail for
the whole run -- this is what makes partial re-execution possible (see
re-planning below).

## Sequential and parallel execution with synchronization

`Executor.run()` (`orchestrator/executor.py`) loops: at each iteration it
asks the graph for the current **wave** -- every node whose dependencies
have all `SUCCEEDED` and which hasn't run yet (`ready_nodes()`). Nodes in
the wave marked `parallel_ok=True` (the default) execute concurrently in a
`ThreadPoolExecutor`; the loop does not advance to the next wave until the
whole current wave has resolved. In every scenario, `testing` and `docs`
both depend only on `implementation` and not on each other, so they run
side by side; `release` depends on both, so it is the synchronization
barrier. This is a real (if small-scale) demonstration of "non-linear,
stateful execution" rather than a fixed linear chain.

## Cross-stage context and decision lineage

`orchestrator/context.py`'s `SharedContext` is threaded through every node
call. Two things live there that matter for the assignment specifically:

- **`ctx.data`** -- accepted node outputs, so e.g. the docs agent reads the
  design node's exact API contract to generate the changelog entry (it
  literally cannot document an endpoint the design node didn't approve).
- **`ctx.decisions`** -- an ordered list of `Decision` records
  (`stage`, `summary`, `rationale`, `alternatives_considered`), written by
  every agent, not just the interesting ones. This is the "decision
  lineage": for the ambiguous scenario it holds every rejected
  interpretation and why it lost; for brownfield it holds the impact
  analysis and the mid-run replanning decision. It's rendered verbatim into
  each run's `REPORT.md` and the release note.

## Approvals -- human checkpoints

`orchestrator/approvals.py` defines an `Approver` protocol with one method,
`decide(ctx, node, staged_result) -> ApprovalDecision`. `AutoApprover` is
the demo/CI stand-in used by all three scenarios: it approves only when
there are no *currently unresolved* policy violations in the run (see
"Guardrails" below), and it genuinely withholds approval when there are --
this is exercised for real in the brownfield security-incident run. A real
deployment swaps `AutoApprover` for an implementation of the same
`decide()` method backed by a Slack approval, a web UI, or a CLI prompt;
nothing in `executor.py` changes.

## Guardrails (policy engine)

`orchestrator/policy.py` holds the concrete guardrails used in this repo:

| policy | gate | checks |
|---|---|---|
| `no_hardcoded_secrets` | exit | recursively scans everything a node returned (including nested file-content dicts) for AWS-key-shaped strings, PEM private key headers, `api_key = "..."` literals |
| `dependency_allowlist` | exit | any `new_dependencies` an implementation node introduces must be on a pre-approved list |
| `tests_must_pass` | exit | the testing agent's real pytest result must report success |
| `make_release_gate(graph)` | entry (release node) | refuses to proceed while any policy violation is still *unresolved* -- see below |

**Resolution, not permanent blocking.** A naive "if there's ever been a
violation, block forever" rule would make recovery impossible to
demonstrate: fix the bug, re-run, and the release gate would still refuse
because the violation happened *once*. `unresolved_policy_violations(ctx,
graph)` instead treats a violation as resolved once the node that caused it
currently shows `SUCCEEDED` (i.e. it was fixed and re-run via re-planning).
The full history stays in `ctx.policy_violations` for the audit trail
regardless -- only the release gate's live-vs-resolved judgment changes.
`AutoApprover` and `make_release_gate` share this exact function so they
can't disagree with each other.

## Failure handling: bounded retries, fallback, rollback, safe-stop

Inside `Executor._execute_node`:

1. Entry gates run. A failure here fails the node immediately (no retry --
   a policy violation isn't transient).
2. The approval checkpoint runs, if configured.
3. `node.run()` executes, up to `node.retry.max_attempts` times with a
   linear backoff. An exception, or an exit-policy rejection of the
   result, both count as a failed attempt and are retried the same way.
   The brownfield scenario demonstrates a transient failure recovering on
   retry without any code change (a simulated CI hiccup).
4. If all attempts are exhausted and a `fallback` callable is configured,
   it runs once as a last resort.
5. If the node still hasn't succeeded, it's `FAILED`.

When a node in the current wave ends up `FAILED`, `Executor.run()` takes
one of two paths for the whole run, chosen per-executor via
`rollback_on_failure`:

- **Rollback** (`_rollback`): every node with a `compensate` callable and
  recorded output -- including the node that just failed, since it may have
  had side effects (e.g. written a bad file) *before* its exit gate rejected
  it -- is compensated in reverse topological order. `implementation_agent
  .revert_files`'s compensate function restores each file's pre-write
  backup (or deletes it, if the node created a new file). Every remaining
  `PENDING`/`STALE` node is marked `SKIPPED`.
- **Safe-stop** (`_safe_stop`): no compensation; every remaining node is
  marked `SKIPPED` and execution halts, leaving whatever already succeeded
  in place as the last-known-good state. (Not exercised by the demo
  scenarios, which all use rollback, but it's the same code path with
  `rollback_on_failure=False`.)

This is exercised for real in `brownfield_run.py` part 2: a deliberately
planted hardcoded-secret defect fails `no_hardcoded_secrets`, and the file
it wrote is actually reverted on disk.

## Dynamic re-planning

`Graph.mark_stale(node_id)` walks the graph's descendants and resets that
node plus everything downstream to `STALE` (clearing `attempts`,
`last_error`, and `last_result`). Calling `Executor.run()` again on the
same graph/context picks up exactly where the stale set begins --
`ready_nodes()` only ever returns `PENDING`/`STALE` nodes, so anything
still `SUCCEEDED` (e.g. `requirements`, `codebase_analysis`) is left alone
and not re-executed. Both brownfield sub-scenarios exercise this: part 1
simulates a stakeholder adding an acceptance criterion mid-flow (re-plans
from `design` downward); part 2 simulates fixing the root cause of a
rollback (re-plans from `implementation` downward) and the metrics module
picks up a real MTTR from the gap between the two runs.

## Observability, audit trail, and metrics

`orchestrator/observability.py`'s `EventLog` writes one JSON object per
line to `runs/<run_id>/events.jsonl`, flushed immediately, for every state
transition (node started/retried/succeeded/failed, policy violations,
approval decisions, rollback/safe-stop, replans). It's a complete,
independently-replayable trace -- `orchestrator/metrics.py`'s
`compute_metrics` derives every reliability number (success rate,
retry/rollback counts, safe-stop count, replan count, approvals granted vs.
withheld, per-stage and end-to-end latency, and MTTR per node measured as
the gap between a `node_failed` and the next `node_succeeded` for that
node) purely by scanning this log plus final node statuses -- never from
separate counters kept during execution -- so the metrics can never drift
from what actually happened, and can be recomputed for any historical run.
`scenarios/common.py`'s `write_report` renders the same information as a
human-readable `REPORT.md` per run.

## Agents vs. LLM calls

Each file under `orchestrator/agents/` is a plain Python function, not an
LLM call. `requirements_agent.normalize`, for instance, uses a small
trigger-term table plus an impact/effort scoring heuristic to do what an
LLM-backed requirements agent would: flag ambiguity, generate candidate
interpretations, and pick one with a documented rationale. This is an
honest trade-off for a prototype that has to run deterministically and
offline in an interview setting, not a claim that a heuristic table is a
substitute for language understanding at scale. Two things make this a
defensible seam rather than a shortcut: (1) every agent function's
signature is `(ctx, node, ...) -> dict` and does its real, substantive work
against the actual codebase -- `codebase_analysis_agent` really parses
`app/` with regex, `testing_agent` really shells out to `pytest`,
`implementation_agent` really writes files to disk -- so swapping the
*reasoning* step (which interpretation to choose, what code to write) for
an actual model call is a localized change inside one function, not a
rewrite of the graph/executor/policy layer; and (2) the requirements
agent's scoring table and the design agent's route-collision check are
exactly the kind of structured judgment call an LLM call would also need
grounding rules for -- writing them out explicitly here is what "policy
guardrails" and "engineering judgment" mean in a codebase an LLM agent
would actually have to operate inside.

## Key design decisions and why

- **SQLite behind a single shared connection, not one per thread.**
  Per-thread connections would each get an isolated, empty database when
  the DB path is `:memory:` (used by the test suite) -- a real bug caught
  during development (see [TESTING.md](TESTING.md)). Write access is
  serialized with a lock; this trades a little write concurrency for
  correctness simplicity, the right call at this scale.
- **Node output vs. node "last result" are different things
  (`ctx.data` vs. `node.last_result`).** A node that fails its own exit
  policy has already run and may have side effects that need undoing, but
  its result must never become visible to downstream nodes (which require
  `SUCCEEDED`, not just "ran"). Keeping these separate is what makes
  rollback-of-a-failing-node's-own-writes possible without also letting a
  rejected result leak downstream.
- **Scenarios are not naively idempotent, on purpose.** Greenfield's design
  node genuinely refuses to "add" a route that already exists -- that's the
  same guardrail that should fire if a second, unrelated PR tried to add a
  route that already shipped. `scenarios/reset_demo_state.py` exists so a
  grader can replay the demo repeatably, rather than the guardrail being
  silently weakened to tolerate re-runs.
