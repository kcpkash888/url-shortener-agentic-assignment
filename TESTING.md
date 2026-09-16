# Testing approach, limitations, and trade-offs

## What's tested and how

47 tests, all passing (`python -m pytest tests/ -v`):

| file | count | covers |
|---|---|---|
| `tests/test_shortener.py` | 9 | core domain logic directly: code generation/collision, click counting, custom alias + conflicts, expiry, deactivation, analytics, cache usage |
| `tests/test_api.py` | 11 | the HTTP layer via FastAPI's `TestClient`: validation errors, redirects, 404/410 semantics, rate limiting, health check |
| `tests/test_cache.py` | 4 | the TTL/LRU cache in isolation: hit/miss stats, expiry, eviction |
| `tests/test_orchestrator.py` | 14 | the orchestration engine itself, with small synthetic graphs: cycle detection, dependency-gated readiness, `mark_stale` scoping, retries, fallback, entry/exit policy gates, approval withholding, rollback (including compensating the failing node's own side effects), safe-stop, re-planning only re-executing the stale subgraph, and metrics (success rate, MTTR) |
| `tests/test_bulk.py`, `tests/test_analytics_enhancement.py`, `tests/test_url_safety.py` | 9 | written by the orchestrator itself, not by hand, as part of the three scenarios -- see below |

The last group is worth calling out specifically: `implementation_agent
.write_files` in each scenario writes its feature's test file alongside
the feature code in the same node, and `testing_agent.run_tests` then
actually executes the *entire* suite (not just the new file) as an exit
gate before docs/release are allowed to proceed. This is deliberate --
"testing improvements" in this repo are themselves an orchestrated,
gated engineering output, not just something I wrote by hand ahead of
time.

## Why `tests/test_orchestrator.py` matters

The assignment calls workflow orchestration the "critical differentiator."
Testing only the URL shortener and leaving the orchestration engine's
control flow (gates, retries, rollback, re-planning) unverified would have
been a real gap in exactly the part of this project meant to carry the
most weight. `test_orchestrator.py` tests the engine directly with tiny
synthetic 1-3 node graphs, independent of `app/` -- it would catch a
regression in `executor.py` even if every scenario script were deleted.

Two of its tests correspond directly to real bugs caught and fixed during
development (see below): `test_rollback_compensates_succeeded_nodes_and_the
_failing_node_itself` and the SQLite thread-local issue (covered
indirectly, since every API test would have failed under the old
per-thread-connection design).

## Bugs this test suite (and the scenario runs) actually caught

Documented here rather than quietly fixed, since finding-and-fixing them
during a "2-3 day" build is itself part of the deliverable:

1. **Per-thread SQLite connections silently created isolated in-memory
   databases.** `db.py` originally cached one connection per thread. Under
   FastAPI's `TestClient`, request handling can run on a different thread
   than the test itself; with `DB_PATH=":memory:"` each thread's connection
   is a *separate* database, so writes from one request were invisible to
   the next. Fixed by caching one shared connection per DB path, guarded by
   a lock for writes. Found while writing `tests/conftest.py`, before any
   orchestration code existed.
2. **The secret-scan policy couldn't see the content it was supposed to
   scan.** `no_hardcoded_secrets` originally only checked top-level string
   values of a node's result dict; `implementation_agent.write_files`'s
   result held new file content one level deeper (inside a `backups`
   dict, and not returned as new content at all). A deliberately-planted
   secret in the brownfield security-incident scenario would have sailed
   through undetected. Fixed by having `write_files` return a
   `file_contents` field and making the policy scan recursively. Caught by
   actually running the scenario end-to-end, not by unit tests -- a good
   argument for keeping the scenario runs as a testing layer in their own
   right, not just a demo.
3. **A node that fails its own exit gate still needs its side effects
   undone.** The first rollback implementation only compensated nodes with
   `status == SUCCEEDED`. But `implementation_agent.write_files` writes
   files to disk and returns *before* the exit-gate policy runs against
   that result -- a node that fails its own gate can still have real side
   effects. The original code left a bad file write in place after a
   "successful" rollback. Fixed by giving `Node` a `last_result` field set
   as soon as `run()` returns (regardless of gate outcome) and compensating
   any node with `status in (SUCCEEDED, FAILED)` that has one. Now covered
   by `test_rollback_compensates_succeeded_nodes_and_the_failing_node_itself`.
4. **Policy violations blocked release forever, even after a fix.** The
   first version of the release gate and `AutoApprover` both checked "is
   `ctx.policy_violations` non-empty," which is permanently true once
   anything has ever gone wrong in a run -- so the brownfield recovery
   demo could roll back, get fixed, re-plan, and *still* never release.
   Fixed by `unresolved_policy_violations(ctx, graph)`, which treats a
   violation as resolved once the node that caused it currently shows
   `SUCCEEDED`; the full history stays in `ctx.policy_violations`
   regardless, for audit purposes. `AutoApprover` and the release gate now
   share this one function so they can't disagree.
5. **The scenario-replay assertion assumed a pristine repo.** The
   brownfield rollback demo originally asserted the planted-secret file
   was *removed* by rollback -- true the first time it's ever run against
   a clean repo, false on a second run where a prior good version already
   existed to revert to. Fixed by checking for the absence of the secret
   pattern rather than the absence of the file, and led directly to
   writing `scenarios/reset_demo_state.py` so re-runs have a defined clean
   starting point instead of accumulating state.

## Limitations and trade-offs

- **No load/concurrency testing.** The rate limiter and cache are
  correctness-tested (hit/miss, eviction, 429 on limit) but not
  load-tested; `RateLimiter` and `TTLCache` are in-process and
  single-instance by design (see `ARCHITECTURE.md`), so this would only be
  meaningful once a multi-instance deployment target existed.
- **No auth/authorization.** Anyone can delete or view analytics for any
  link if they know its code. Explicitly out of scope for this exercise
  (see `SUMMARY.md`'s assumptions) and was one of the interpretations the
  ambiguous-requirement agent considered and *rejected* as disproportionate
  effort for the ask as given -- not an oversight.
- **The orchestrator's "agents" are deterministic Python, not LLM calls**
  (see `ARCHITECTURE.md#agents-vs-llm-calls`), so their test coverage
  verifies the control-flow contract (a function's real side effects and
  return shape) rather than the quality of an open-ended language-model
  judgment call. Swapping in a real model call would need its own
  evaluation strategy (e.g. golden-output regression tests with tolerance,
  or a human-in-the-loop review sample) layered on top of, not instead of,
  this suite.
- **MTTR in the demo runs is artificially small** (single-digit
  milliseconds) because the "fix" in the brownfield recovery scenario is
  applied instantly by the script re-assigning a node's `run` function. In
  a real deployment the same metric would measure actual human
  remediation time between a `node_failed` event and the next
  `node_succeeded` for that node -- the mechanism is real, only the demo's
  timescale is compressed.
- **`docs/API_CHANGELOG.md` is append-only and not de-duplicated.**
  Running a scenario twice (without `reset_demo_state`) adds a second
  entry for the same feature rather than replacing the first. This is a
  deliberate "audit trail, not a cleaned-up summary" choice (see
  `ARCHITECTURE.md`), but it does mean the changelog isn't meant to be
  read as a polished, user-facing document without editorial pass -- it's
  closer to a commit log.
- **Threading-based parallelism, not process/async-based.** Parallel nodes
  run in a `ThreadPoolExecutor`; this is sufficient for the demo's I/O
  -bound agent work (file writes, subprocess calls) but wouldn't scale to
  CPU-bound agent logic without moving to multiprocessing.
