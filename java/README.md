# URL Shortener + Agentic SDLC Orchestrator (Java / Spring Boot port)

A behavior-preserving Java port of the [Python implementation](../README.md)
one directory up: the same URL shortener service, the same from-scratch
agentic orchestration engine, and the same three demo scenarios (greenfield,
brownfield, ambiguous), rebuilt with Java 21 + Spring Boot instead of
Python + FastAPI. Read the top-level [`ARCHITECTURE.md`](../ARCHITECTURE.md),
[`SCENARIOS.md`](../SCENARIOS.md), and [`SUMMARY.md`](../SUMMARY.md) first --
the design rationale is identical for both ports; this file only covers
what's Java-specific.

## Setup

Requires **Java 21+** and **Maven 3.9+**. No external services, no API keys,
no IDE required.

```bash
mvn install
```

## Run the service

```bash
mvn spring-boot:run
```

or build and run the jar directly:

```bash
mvn package -DskipTests
java -jar target/urlshortener-agentic.jar
```

Then, for example:

```bash
curl -X POST http://localhost:8080/api/urls -H "Content-Type: application/json" \
  -d '{"url": "https://example.com/some/long/path"}'
curl -i http://localhost:8080/<code>
curl http://localhost:8080/api/urls/<code>/analytics
```

## Run the tests

```bash
mvn test
```

## Run the orchestrated scenarios

Same three scenarios as the Python port, run via Maven's exec plugin:

```bash
mvn exec:java -Dexec.mainClass=com.example.urlshortener.scenarios.GreenfieldRun
mvn exec:java -Dexec.mainClass=com.example.urlshortener.scenarios.BrownfieldRun
mvn exec:java -Dexec.mainClass=com.example.urlshortener.scenarios.AmbiguousRun
```

Run them in that order the first time. Each writes a run report to
`runs/<run-id>/REPORT.md` and a full event trace to
`runs/<run-id>/events.jsonl`, exactly like the Python port.

**To replay from a clean baseline:**

```bash
mvn exec:java -Dexec.mainClass=com.example.urlshortener.scenarios.ResetDemoState
```

## What's different from the Python port, and why

The orchestration engine (`orchestrator/`) is a line-for-line-equivalent
port -- same `Graph`/`Node`/`Executor` design, same wave-based
parallel-with-synchronization execution, same entry/exit policy gates,
same retry/fallback/rollback/safe-stop semantics, same decision-lineage and
audit-trail model, same metrics (including the same fix for "policy
violations must resolve, not block forever" and "a node must compensate its
own side effects, not just previously-succeeded nodes' side effects" --
see [`TESTING.md`](../TESTING.md) for why those exist). A few things had to
change because Java is a compiled, statically-typed language and Python
isn't:

- **The implementation agent's output must compile.** In Python, the
  "implementation" step writes a `.py` file and it's live the next time
  anything imports it. In Java, `ImplementationAgent.writeFiles` writes real
  `.java` source, but nothing happens with it until something compiles it.
  `TestingAgent.runTests` shells out to `mvn test`, and Maven compiles
  before it tests -- so the testing node's exit gate (`testsMustPass`) is
  *also* the compile gate. A syntax error in generated code fails the same
  node, for the same reason (`tests_must_pass`), that a failing assertion
  would. This is a genuinely Java-specific failure mode the Python port
  doesn't have, and the engine didn't need any special-casing to absorb it.
- **Records instead of dicts.** Python's agents pass around `dict[str, Any]`
  freely; adding a field to what `getAnalytics` returns is a one-line
  change. Java's `AnalyticsResult` is a `record`, so the brownfield
  scenario's two-phase enhancement (`top_referrers`, then
  `unique_referrer_count` after a simulated requirement change) has to
  regenerate the whole record definition and every call site that
  constructs it, not just add a dict key. `BrownfieldRun` handles this with
  three known-content blocks (baseline / phase A / phase A+B) instead of
  Python's incremental string-replace-if-not-present checks -- more
  verbose, but the same idempotent-on-rerun property.
- **Nodes are immutable; re-planning replaces them.** Python's `Node` is a
  mutable dataclass, so `graph.nodes["design"].run = new_lambda` is how the
  brownfield scenario simulates a stakeholder changing the requirement
  mid-flow. Java's `Node.run` is `final`. `BrownfieldRun` instead builds a
  *new* `Node` with the new lambda and does `graph.nodes.put("design", ...)`
  to replace it in the graph's map -- functionally identical (the
  replacement node starts at its default `PENDING` status, which
  `readyNodes()` treats exactly like `STALE`), just expressed as
  replacement rather than mutation.
- **`mvn test` is slower than `pytest`.** Each Spring context start is a
  few seconds; a scenario that invokes `mvn test` two or three times (the
  brownfield scenario does, across its retry demo, replan, and the
  rollback-then-recovery in part 2) takes on the order of a minute or two
  end to end, versus low single-digit seconds for the Python port's
  `pytest` calls. The mechanism (real build, real test run, as a real exit
  gate) is identical; only the wall-clock cost of "real" changed.

## Test suite

48 tests, all passing (`mvn test`): unit tests for `TtlCache` and
`RateLimiter`, `@SpringBootTest` integration tests for `ShortenerService`
and the HTTP layer (via `TestRestTemplate` against an in-memory SQLite
datasource), 14 direct unit tests for the orchestration engine with
synthetic graphs (`ExecutorTest`, a straight port of the Python port's
`test_orchestrator.py`), and the tests the three scenarios wrote for
themselves while running.
