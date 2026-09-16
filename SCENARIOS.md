# Scenario walkthroughs

All three scenarios ran against this repo for real; the run IDs, decision
lineage, and metrics quoted below are copied from the actual generated
reports under `runs/` (not hand-written). Re-run them yourself:

```bash
python -m scenarios.reset_demo_state   # start from a clean baseline
python -m scenarios.greenfield_run
python -m scenarios.brownfield_run
python -m scenarios.ambiguous_run
```

---

## 1. Greenfield: bulk short-link creation

**Request** (already concrete -- contrast case for scenario 3):

> "Add a bulk short-link creation endpoint: POST /api/urls/bulk must accept
> a list of up to 50 URLs and return one short link (or a per-item error)
> for each, reusing the existing validation and code-generation logic in
> app/shortener.py."

### Decomposition

| node | stage | depends on |
|---|---|---|
| `requirements` | requirements | -- |
| `design` | design | requirements |
| `implementation` | implementation | design |
| `testing` | testing | implementation |
| `docs` | docs | implementation |
| `release` | release (human-approved) | testing, docs |

`testing` and `docs` both depend only on `implementation` and not on each
other -- they run in parallel; `release` is the synchronization point.

### Orchestration in action

- **Requirements**: the request already names the method, path, and
  behavior, so the requirements agent explicitly records *why* it did
  **not** treat this as ambiguous (see `ARCHITECTURE.md`'s vague-term
  detector) -- this is the deliberate contrast with scenario 3.
- **Design**: `design_agent.propose` reads the live `app/main.py` and
  checks `POST /api/urls/bulk` isn't already a registered route before
  approving it (`mode="add"`). It records two risks up front: a 50-item cap
  to bound worst-case request latency, and that one item's failure doesn't
  roll back the rest of the batch (each item is independent by design).
- **Implementation**: `implementation_agent.write_files` writes three real
  files -- `app/schemas.py` (new `BulkCreateRequest`/`BulkResultItem`/
  `BulkCreateResponse` models), `app/main.py` (the new endpoint, wired to
  the existing `shortener.create_link`), and `tests/test_bulk.py` (three
  new tests it also wrote). Its exit gates (`no_hardcoded_secrets`,
  `dependency_allowlist`) both passed.
- **Testing / Docs (parallel)**: `testing_agent` shells out to the real
  `pytest` suite; `docs_agent` generates the changelog entry directly from
  the design node's approved API contract (so it can't describe an
  endpoint that wasn't actually built).
- **Release**: gated on `requires_approval=True` and the
  zero-unresolved-policy-violations entry gate; `AutoApprover` granted it.

### Result

```
outcome=completed   success_rate=1.0   retries=0   rollbacks=0   replans=0
requirements -> design -> implementation -> {testing, docs} -> release   (all SUCCEEDED)
```

### Validation

Live smoke test against the running server after this scenario ran (see
`README.md`):

```json
POST /api/urls/bulk {"urls":[{"url":"https://example.com/a"},{"url":"https://malicious-example.test/b"}]}
-> {"results":[
     {"success": true,  "link": {"code": "JNnRHEy", ...}},
     {"success": false, "error": "target domain 'malicious-example.test' is not allowed", "url": "..."}
   ]}
```
(The second item is rejected by scenario 3's later safety hardening,
confirming the two features compose correctly.)

---

## 2. Brownfield: enhance analytics, re-plan, and recover from a rollback

Two parts, against the *existing* analytics endpoint.

### Part 1 -- add `top_referrers`, then re-plan mid-flow

**Request**: "Enhance GET /api/urls/{code}/analytics so it must also return
a top_referrers breakdown (referrer -> click count, top 5) computed from
existing click records."

**Codebase reasoning**: before any design happens, `codebase_analysis`
does a real static scan of `app/` for the word "analytics" and reports
exactly what it found (from run `brownfield-analytics-20260916T153543-183c0b`):

> "'analytics' appears in 3 existing module(s): ['app/main.py',
> 'app/schemas.py', 'app/shortener.py']. Downstream consumers that must
> keep working: main.py's HTTP layer, tests/test_shortener.py's unit
> tests, and tests/test_api.py's integration tests -- any signature change
> to shortener.py functions has to stay backward compatible or all three
> need updating together."

`design_agent.propose` then runs in `mode="modify"`, which requires the
route to *already exist* (the opposite check from greenfield's `mode="add"`)
-- a genuine guard against a brownfield change accidentally becoming an
unplanned new route.

**Bounded retry**: the testing node is wrapped to fail its first attempt
with a simulated transient CI-runner hiccup, then succeed on the real
retry -- demonstrated for real: `testing` shows `attempts=2` in the report,
with a `node_retry` event in between.

**Dynamic re-planning**: after the first run completes and releases
successfully, we simulate a stakeholder adding a follow-up acceptance
criterion ("also report `unique_referrer_count`"). The decision is logged:

> "[requirements/requirements] Stakeholder added a follow-up acceptance
> criterion after reviewing the first cut. rationale: Also report
> unique_referrer_count alongside top_referrers."

`graph.mark_stale("design")` invalidates `design` and everything downstream
(`implementation`, `testing`, `docs`, `release`); `requirements` and
`codebase_analysis` are left `SUCCEEDED` and are **not** re-run. Calling
`Executor.run()` again picks up exactly at `design`. Final metrics for this
run (`replans=1`, `retry_count=1` from part 1's flaky test, both stages
completing):

```
outcome=completed   success_rate=1.0   retries=1   rollbacks=0   replans=1   e2e_latency_ms=4553.08
```

### Part 2 -- a real security-guardrail rollback, then recovery

**Request**: "Add a structured audit-log module (app/audit_log.py) that
records admin actions on links."

The implementation deliberately (for the demo) includes a
hardcoded-looking placeholder credential:

```python
api_key = "AKIAABCDEFGHIJKLMNOP"
```

`no_hardcoded_secrets` catches it as an exit-gate policy violation on the
`implementation` node. With `rollback_on_failure=True`, the executor:

1. Compensates the `implementation` node itself (not just previously-
   succeeded nodes -- it already wrote `app/audit_log.py` to disk before
   its exit gate rejected the result), deleting the file since it was
   brand new.
2. Marks `testing` and `release` `SKIPPED`.

```
outcome=rolled_back   success_rate=0.25   rollbacks=1
confirmed: app/audit_log.py did not exist before this run and rollback removed it
```

We then apply the real fix (replace the hardcoded value with a reference to
an environment variable), log why, and `mark_stale("implementation")` to
re-plan from the failed node:

> "[implementation/implementation] Root cause identified: hardcoded
> placeholder credential in audit_log.py. rationale: Replaced with an
> environment-variable reference; re-running from the implementation node."

```
outcome=completed   success_rate=1.0   rollback_count=1   replans=1
average_mttr_ms=2.61   mttr_ms_by_node={"implementation": 2.61}
```

(`rollback_count` stays `1` in the final metrics because it's counted from
the full event history, not reset by the recovery -- the audit trail
remembers the incident even after it's resolved. The MTTR is
artificially small here because the "fix" is applied instantly by the
script; in a real deployment this is the gap until a human actually pushes
the fix.)

### Validation

`python -m pytest tests/` passes (43/43 at this point -- 33 app tests
written up front, `tests/test_analytics_enhancement.py` the pipeline wrote
itself, plus the 14 orchestrator-engine unit tests -- see
[TESTING.md](TESTING.md)).
`docs/API_CHANGELOG.md` accumulates one entry per completed design
(so an entry from the pre-replan design is also visible -- an honest,
append-only record of what actually happened, not a cleaned-up summary).

---

## 3. Ambiguous: "Can we make the short links a bit safer?"

**Request**: "Can we make the short links a bit safer? A couple of users
raised concerns." -- no HTTP method, no acceptance criteria, one vague
trigger word ("safer").

### Requirement understanding in action

`requirements_agent.normalize` detects the vague term, has no acceptance-
criteria markers to fall back on, and generates three concrete candidate
interpretations from its trigger-term table, each scored on
`impact (1-5) x2 - effort (1-5)`:

| candidate | impact | effort | score |
|---|---|---|---|
| Reject URLs targeting malicious/disallowed domains + reserve system short codes | 5 | 2 | 8 |
| Scan targets against a live third-party threat-intel API | 4 | 4 | 4 |
| Add auth + per-user link ownership | 4 | 5 | 3 |

It picked the first and logged why the other two lost:

> "Detected vague term(s) ['safer'] with no explicit acceptance criteria.
> Generated 3 candidate interpretation(s), scored each on impact (1-5)
> minus effort (1-5) x weighting, and selected the highest-scoring option
> because: Directly closes an abuse vector (phishing redirect, route
> hijack) with a small, well-scoped change to input validation."
>
> rejected: "Scan link targets against a live third-party threat-
> intelligence API before allowing creation." -- effective but introduces
> an external dependency, latency on the write path, and a new vendor/cost
> surface -- not justified without an explicit requirement for it.
>
> rejected: "Add authentication and per-user link ownership..." -- real
> hardening, but requires an auth subsystem that doesn't exist yet -- out
> of proportion to a single ambiguous ask.

This is the direct contrast with scenario 1: same pipeline shape, but the
first node's job here is materially harder and its output materially
different.

### Implementation

The chosen interpretation becomes real validation logic in
`app/shortener.py:create_link` -- a domain blocklist check and a
reserved-short-code check -- applied consistently to both `POST /api/urls`
and `POST /api/urls/bulk` (design's `mode="modify"` confirms both routes
already exist before approving the change). Four new tests confirm both
the rejection paths and that legitimate URLs/aliases still work.

```
outcome=completed   success_rate=1.0   retries=0   rollbacks=0   replans=0
ambiguity_detected=True
chosen_interpretation="Reject URLs that point at known-malicious or disallowed domains,
  and reserve system-critical short codes (api, health, admin) so they cannot be squatted."
```

### Validation

Live smoke test:

```
POST /api/urls {"url": "https://malicious-example.test/x"}        -> 422
POST /api/urls {"url": "https://example.com", "custom_alias": "api"} -> 422
POST /api/urls {"url": "https://example.com/fine"}                 -> 201
```

---

## Why the scenarios aren't simply idempotent (and what to do about it)

Re-running any scenario against an already-mutated codebase can trip a
*real* guardrail -- e.g. greenfield's design node refuses to "add" a route
that's already there, exactly as it should if two independent changes
proposed the same new endpoint. `scenarios/reset_demo_state.py` restores
`app/main.py`, `app/schemas.py`, and `app/shortener.py` to their
pre-scenario baseline (and clears the generated test files, changelog, and
`runs/`) so the three scenarios can be replayed from a clean slate as many
times as needed.
