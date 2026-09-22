# Testing approach, limitations, and trade-offs

## What's tested and how

48 tests, all passing (`mvn test`):

| file | count | covers |
|---|---|---|
| `ShortenerServiceTest.java` | 10 | core domain logic directly: code generation/collision, click counting, custom alias + conflicts, expiry, deactivation, analytics, cache usage |
| `UrlControllerTest.java` | 9 | the HTTP layer via Spring's `TestRestTemplate`: validation errors, redirects, 404/410 semantics, rate limiting, health check |
| `TtlCacheTest.java` | 4 | the TTL/LRU cache in isolation: hit/miss stats, expiry, eviction |
| `RateLimiterTest.java` | 3 | the fixed-window rate limiter in isolation |
| `ExecutorTest.java` | 14 | the orchestration engine itself, with small synthetic graphs: cycle detection, dependency-gated readiness, `markStale` scoping, retries, fallback, entry/exit policy gates, approval withholding, rollback (including compensating the failing node's own side effects), safe-stop, re-planning only re-executing the stale subgraph, and metrics (success rate, MTTR) |
| `BulkControllerTest.java`, `AnalyticsEnhancementTest.java`, `SafetyValidationTest.java` | 8 | written by the orchestrator itself, not by hand, as part of the three scenarios -- see below |

The last group is worth calling out specifically: `ImplementationAgent
.writeFiles` in each scenario writes its feature's test file alongside the
feature code in the same node, and `TestingAgent.runTests` then actually
runs the *entire* `mvn test` build+test cycle (not just the new file) as an
exit gate before docs/release are allowed to proceed. This is deliberate --
"testing improvements" in this repo are themselves an orchestrated, gated
engineering output, not just something I wrote by hand ahead of time.

## Why `ExecutorTest.java` matters

The assignment calls workflow orchestration the "critical differentiator."
Testing only the URL shortener and leaving the orchestration engine's
control flow (gates, retries, rollback, re-planning) unverified would have
been a real gap in exactly the part of this project meant to carry the
most weight. `ExecutorTest` tests the engine directly with tiny synthetic
1-3 node graphs, independent of `app/` -- it would catch a regression in
`Executor.java` even if every scenario class were deleted.

One of its tests corresponds directly to a real bug caught and fixed
during development (see below): compensating both previously-succeeded
nodes and the node that just failed its own exit gate.

## Bugs this test suite (and the scenario runs) actually caught

Documented here rather than quietly fixed, since finding-and-fixing them
during a "2-3 day" build is itself part of the deliverable:

1. **A single, unbounded connection pool against SQLite invites "database
   is locked" errors under concurrent requests.** SQLite serializes writes
   at the file level; a normal-sized Hikari pool (Spring Boot's default is
   10) lets multiple threads race for the same file lock. Fixed by capping
   `spring.datasource.hikari.maximum-pool-size` at 1 in
   `application.properties`, so every request already serializes through
   one connection instead of fighting SQLite's own locking underneath a
   bigger pool. Same underlying trade-off as caching a single shared
   connection by hand, expressed as Hikari config instead of hand-rolled
   connection management.
2. **The secret-scan policy couldn't see the content it was supposed to
   scan.** `Policies.noHardcodedSecrets` originally only checked
   top-level string values of a node's result map; `ImplementationAgent
   .writeFiles`'s result held new file content one level deeper (inside a
   nested map, and not returned as new content at all). A
   deliberately-planted secret in the brownfield security-incident
   scenario would have sailed through undetected. Fixed by having
   `writeFiles` return a `file_contents` field and making the policy scan
   recursively (`Policies.collectStrings` walks maps and iterables). Caught
   by actually running the scenario end-to-end, not by unit tests -- a good
   argument for keeping the scenario runs as a testing layer in their own
   right, not just a demo.
3. **A node that fails its own exit gate still needs its side effects
   undone.** The first rollback implementation only compensated nodes with
   `status == SUCCEEDED`. But `ImplementationAgent.writeFiles` writes files
   to disk and returns *before* the exit-gate policy runs against that
   result -- a node that fails its own gate can still have real side
   effects. The original code left a bad file write in place after a
   "successful" rollback. Fixed by giving `Node` a `lastResult` field set
   as soon as `run()` returns (regardless of gate outcome) and compensating
   any node with `status in (SUCCEEDED, FAILED)` that has one. Now covered
   by a dedicated `ExecutorTest` case.
4. **Policy violations blocked release forever, even after a fix.** The
   first version of the release gate and `AutoApprover` both checked "is
   `ctx.policyViolations` non-empty," which is permanently true once
   anything has ever gone wrong in a run -- so the brownfield recovery demo
   could roll back, get fixed, re-plan, and *still* never release. Fixed by
   `Policies.unresolvedPolicyViolations(ctx, graph)`, which treats a
   violation as resolved once the node that caused it currently shows
   `SUCCEEDED`; the full history stays in `ctx.policyViolations`
   regardless, for audit purposes. `AutoApprover` and the release gate now
   share this one function so they can't disagree.
5. **The scenario-replay assertion assumed a pristine repo.** The
   brownfield rollback demo originally asserted the planted-secret file was
   *removed* by rollback -- true the first time it's ever run against a
   clean repo, false on a second run where a prior good version already
   existed to revert to. Fixed by checking for the absence of the secret
   pattern rather than the absence of the file, and led directly to writing
   `scenarios/ResetDemoState.java` so re-runs have a defined clean starting
   point instead of accumulating state.
6. **The implementation agent's output has to compile, and Java only
   catches that at build time.** Unlike a dynamically-typed script that's
   "live" the moment it's written, a generated `.java` file isn't real
   until something compiles it. `TestingAgent.runTests` shells out to
   `mvn test`, and Maven compiles before it tests -- so `testsMustPass` is
   *also* the compile gate. A syntax error in generated code fails the
   same node, for the same reason, that a failing assertion would; this
   needed no special-casing in the engine, but it's a genuinely
   Java-specific failure mode worth naming, since a port from a
   dynamically-typed language could easily assume "wrote the file" and
   "the feature works" are closer together than they are.

## Limitations and trade-offs

- **No load/concurrency testing.** The rate limiter and cache are
  correctness-tested (hit/miss, eviction, 429 on limit) but not
  load-tested; `RateLimiter` and `TtlCache` are in-process and
  single-instance by design (see `ARCHITECTURE.md`), so this would only be
  meaningful once a multi-instance deployment target existed.
- **No auth/authorization.** Anyone can delete or view analytics for any
  link if they know its code. Explicitly out of scope for this exercise
  (see `SUMMARY.md`'s assumptions) and was one of the interpretations the
  ambiguous-requirement agent considered and *rejected* as disproportionate
  effort for the ask as given -- not an oversight.
- **The orchestrator's "agents" are deterministic Java, not LLM calls**
  (see `ARCHITECTURE.md#agents-vs-llm-calls`), so their test coverage
  verifies the control-flow contract (a method's real side effects and
  return shape) rather than the quality of an open-ended language-model
  judgment call. Swapping in a real model call would need its own
  evaluation strategy (e.g. golden-output regression tests with tolerance,
  or a human-in-the-loop review sample) layered on top of, not instead of,
  this suite.
- **MTTR in the demo runs is artificially small** (single-digit to
  low-double-digit milliseconds) because the "fix" in the brownfield
  recovery scenario is applied instantly by the script replacing a node's
  `run` lambda. In a real deployment the same metric would measure actual
  human remediation time between a `node_failed` event and the next
  `node_succeeded` for that node -- the mechanism is real, only the demo's
  timescale is compressed.
- **`docs/API_CHANGELOG.md` is append-only and not de-duplicated.**
  Running a scenario twice (without `ResetDemoState`) adds a second entry
  for the same feature rather than replacing the first. This is a
  deliberate "audit trail, not a cleaned-up summary" choice (see
  `ARCHITECTURE.md`), but it does mean the changelog isn't meant to be
  read as a polished, user-facing document without editorial pass -- it's
  closer to a commit log.
- **Threading-based parallelism, not process/async-based.** Parallel nodes
  run in a `ThreadPoolExecutor`; this is sufficient for the demo's I/O
  -bound agent work (file writes, subprocess calls) but wouldn't scale to
  CPU-bound agent logic without moving to a different concurrency model.
- **`mvn test` is slower than a script-based test runner.** Each Spring
  context start is a few seconds; a scenario that invokes `mvn test` two or
  three times (the brownfield scenario does, across its retry demo,
  replan, and the rollback-then-recovery in part 2) takes on the order of
  a minute or two end to end. The mechanism (real build, real test run, as
  a real exit gate) is what matters; only the wall-clock cost of "real"
  is higher than a lighter-weight test runner would be.
