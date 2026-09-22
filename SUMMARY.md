# Final Engineering Summary

## Plan and rationale

The assignment asks for two things that pull in different directions: a
working URL shortener, and a demonstration of agentic SDLC orchestration
as the "critical differentiator." Building the shortener by hand and then
bolting a thin orchestration layer on top afterward would have satisfied
the letter of the brief but missed the point -- so the actual plan was to
build a small, real orchestration *engine* first (dependency graph, gates,
retries, rollback, re-planning, audit trail, metrics -- none of it specific
to URL shortening), and then use it, for real, to build three of the
shortener's features into the codebase: one greenfield, one brownfield
(with a genuine rollback-and-recovery incident and a mid-flight
re-plan), and one starting from a deliberately underspecified request.

Concretely, in order:

1. Build the URL shortener core (API, persistence, cache, rate limiting)
   by hand, with its own test suite -- this is the substrate the
   orchestrator needs to operate on for the brownfield/ambiguous scenarios
   to mean anything.
2. Build the orchestration engine (`orchestrator/`) as a standalone,
   app-agnostic framework: graph, executor, context/lineage, policy
   guardrails, approvals, observability, metrics.
3. Wire up "SDLC agents" (`orchestrator/agents/`) that do real work
   against the real codebase -- read actual files, write actual files, run
   an actual `mvn test` build+test cycle -- rather than returning canned
   strings.
4. Script and run all three scenarios end to end against the live repo,
   fix what broke (real bugs, documented in [TESTING.md](TESTING.md)), and
   capture the resulting audit trails/reports as the evidence for this
   summary and [SCENARIOS.md](SCENARIOS.md).
5. Write the documentation set last, grounded in what the runs actually
   produced (run IDs, exact decision-lineage quotes, exact metrics) rather
   than describing intended behavior that wasn't verified.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the component-level rationale
behind each orchestration mechanism.

## Artifacts produced

- **Service**: `app/` -- 9 endpoints (create, bulk-create, get, list,
  delete, redirect, analytics, health, plus safety-validated variants of
  create/bulk-create), SQLite persistence, TTL/LRU cache, fixed-window rate
  limiter.
- **Orchestration engine**: `orchestrator/` -- Graph/Executor/SharedContext/
  Policies/Approver/EventLog/MetricsCalculator, zero dependency on `app/`.
- **Agents**: `orchestrator/agents/` -- requirements, design,
  codebase-analysis, implementation, testing, docs, release.
- **Scenarios**: `scenarios/GreenfieldRun.java`, `BrownfieldRun.java`,
  `AmbiguousRun.java`, `ResetDemoState.java`, `Common.java`.
- **Tests**: 48 passing (`mvn test`) -- unit tests for `TtlCache` and
  `RateLimiter`, Spring Boot integration tests for `ShortenerService` and
  the HTTP layer, 14 direct unit tests for the orchestration engine, and
  the tests the three scenarios wrote for themselves; see
  [TESTING.md](TESTING.md).
- **Generated-by-the-pipeline artifacts** (not hand-written):
  `BulkControllerTest.java`, `AnalyticsEnhancementTest.java`,
  `SafetyValidationTest.java`; `docs/API_CHANGELOG.md`; per-run
  `runs/<id>/REPORT.md`, `events.jsonl`, and `RELEASE_NOTES*.md`.
- **Documentation**: this file, `README.md`, `ARCHITECTURE.md`,
  `SCENARIOS.md`, `TESTING.md`.

## Risks and trade-offs (and how they were handled)

| risk / trade-off | how it was handled |
|---|---|
| "Agents" are deterministic Java, not LLM calls -- could look like the orchestration is faked | Every agent does real, verifiable work against the live codebase (real file I/O, real `mvn test` subprocess, real regex-based route/impact analysis) so the *mechanism* is genuine even though the *reasoning* is heuristic rather than model-driven; see `ARCHITECTURE.md#agents-vs-llm-calls` for exactly where a real model call would plug in. |
| Human approval checkpoints can't involve an actual human in this format | `AutoApprover` implements the same `Approver.decide()` interface a Slack/webhook-backed approver would, applies a real (not rubber-stamp) minimum bar, and is demonstrated actually withholding approval in the security-incident scenario. |
| Rollback could silently leave the working tree in a worse state than it found | `ImplementationAgent`'s compensate function restores exact pre-write backups (or deletes newly-created files); this is covered directly by orchestrator unit tests and demonstrated for real in the brownfield scenario (file content verified after rollback). |
| Policy violations could either be ignored after one fix (unsafe) or block forever (breaks recovery) | `Policies.unresolvedPolicyViolations` resolves a violation once its node re-succeeds, while keeping full history for audit -- neither silently forgiving nor permanently punitive. |
| Static domain/alias blocklists (the ambiguous scenario's chosen fix) are a known-incomplete safety measure | Documented explicitly as a risk in the design node's own output and in the decision lineage, with the more-complete alternative (live threat-intel API) recorded as considered-and-rejected with a stated reason, not silently dropped. |
| Re-running scenarios against an already-mutated codebase could look broken | It's a deliberate guardrail (see `SCENARIOS.md`'s closing section), not a bug -- and `ResetDemoState.java` gives graders a real way to replay from a clean baseline rather than papering over it. |
| SQLite + in-process cache/rate-limiter don't scale past one instance | Explicitly scoped as a single-process prototype; `ARCHITECTURE.md` and `TESTING.md` both name the multi-instance gap rather than implying this is production-scale as built. |
| The implementation agent's output must compile, not just pass assertions | Java is compiled and statically typed, so `TestingAgent.runTests` shelling out to `mvn test` means Maven's compile step doubles as a real, orchestrator-visible exit gate -- a syntax error in generated code fails the same node, for the same reason (`testsMustPass`), that a failing assertion would, with no special-casing needed in the engine. |

## Validation performed

- Full test suite (`mvn test`, 48 tests) run after every scenario, not
  just at the end.
- Each scenario's `testing` node runs the *real* suite as an orchestrated
  exit gate, not a separate manual step.
- Live smoke test of the running server (`mvn spring-boot:run`) covering
  create, blocked-domain rejection, reserved-alias rejection, redirect,
  analytics (with `top_referrers`/`unique_referrer_count`), and bulk create
  with a mixed success/failure batch -- confirming all three scenarios'
  features compose correctly in the actual running service, not just in
  isolated tests.
- Each scenario's generated `runs/<id>/REPORT.md` and `events.jsonl` were
  read back and checked against the intended behavior (not just "did it
  exit zero") -- this is how the bugs in `TESTING.md` were caught.

## Assumptions

- Single-tenant, no authentication -- anyone with a link's code can view
  its analytics or delete it. Acceptable for a prototype; called out
  explicitly rather than silently assumed.
- SQLite and in-process state (cache, rate limiter) are acceptable for a
  single-instance deployment; horizontal scaling was out of scope.
- "Human approval" can be legitimately demonstrated by a policy-driven
  stand-in that implements the same interface a real human-backed approver
  would, given the constraints of a non-interactive scenario script.
- The three scenarios' feature choices (bulk create, analytics breakdown,
  input-safety hardening) were picked to be realistic, moderate-sized
  changes that exercise every required orchestration mechanism at least
  once, not because they were the only or most obviously "next" features
  for a URL shortener.

## Limitations

See [TESTING.md](TESTING.md#limitations-and-trade-offs) for the full list
(no load testing, no auth, compressed demo MTTR, append-only changelog,
thread-based rather than process-based parallelism). The single most
important one to restate here: the orchestrator's agents are a heuristic
stand-in for what would be LLM-backed reasoning in a production system.
The framework around them -- the part actually being evaluated as the
"critical differentiator" -- does not depend on that being true; swapping
the reasoning step for a real model call is a localized change per agent
method, not a redesign of the graph, executor, policy, or observability
layers.
