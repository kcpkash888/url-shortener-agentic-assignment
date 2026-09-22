# Release note -- ambiguous: 'make links safer' [Java] (run ambiguous-java-20260916T173109-2a5356)

Generated 2026-09-16T17:31:35.544183800Z

**Original request:** Can we make the short links a bit safer? A couple of users raised concerns.

## Decision lineage

- **[requirements/requirements]** Normalized ambiguous request into: Reject URLs that point at known-malicious or disallowed domains, and reserve system-critical short codes (api, health, admin) so they cannot be squatted.
  - rationale: Detected vague term(s) [safer] with no explicit acceptance criteria. Generated 3 candidate interpretation(s), scored each on impact (1-5) minus effort (1-5) x weighting, and selected the highest-scoring option because: Directly closes an abuse vector (phishing redirect, route hijack) with a small, well-scoped change to input validation.
  - rejected alternative: Scan link targets against a live third-party threat-intelligence API before allowing creation. (impact=4, effort=4) -- rejected: Effective but introduces an external dependency, latency on the write path, and a new vendor/cost surface -- not justified without an explicit requirement for it.
  - rejected alternative: Add authentication and per-user link ownership so only the creator can edit or delete a link. (impact=4, effort=5) -- rejected: Real hardening, but requires an auth subsystem that doesn't exist yet -- out of proportion to a single ambiguous ask.
- **[design/design]** Approved design for 'Reject URLs that point at known-malicious or disallowed domains, and reserve system-critical short codes (api, health, admin) so they cannot be squatted.': [POST /api/urls, POST /api/urls/bulk]
  - rationale: Checked proposed routes against UrlController.java's current route table -- no problems. Data model changes: [No schema/table change; validation happens in ShortenerService.createLink.]. Known risks accepted for this scope: [The domain blocklist is static and requires manual maintenance -- a live threat-intel feed was considered and explicitly rejected for this change (see requirements decision lineage) as disproportionate effort for the ask as stated., Reserved-alias list is hardcoded; adding a new reserved word later requires a code change, not just configuration.].
- **[implementation/implementation]** Wrote/modified 6 file(s): [src/main/java/com/example/urlshortener/app/exceptions/UnsafeTargetException.java, src/main/java/com/example/urlshortener/app/exceptions/ReservedAliasException.java, src/main/java/com/example/urlshortener/app/ShortenerService.java, src/main/java/com/example/urlshortener/app/GlobalExceptionHandler.java, src/main/java/com/example/urlshortener/app/UrlController.java, src/test/java/com/example/urlshortener/app/SafetyValidationTest.java]
  - rationale: Implementation follows the approved design's API contract and data model changes verbatim.
- **[docs/docs]** Documented 'Reject URLs that point at known-malicious or disallowed domains, and reserve system-critical short codes (api, health, admin) so they cannot be squatted.' in docs/API_CHANGELOG.md
  - rationale: Doc content is generated directly from the design node's approved API contract, so it cannot describe an endpoint that wasn't actually implemented.
- **[testing/testing]** mvn test passed: Tests run: 48, Failures: 0, Errors: 0, Skipped: 0
  - rationale: Ran the real build+test as an exit gate (Maven compiles before testing, so this also catches compile errors); downstream docs/release nodes are policy-blocked from proceeding on a failing result.

## Artifacts touched

- src/main/java/com/example/urlshortener/app/exceptions/UnsafeTargetException.java
- src/main/java/com/example/urlshortener/app/exceptions/ReservedAliasException.java
- src/main/java/com/example/urlshortener/app/ShortenerService.java
- src/main/java/com/example/urlshortener/app/GlobalExceptionHandler.java
- src/main/java/com/example/urlshortener/app/UrlController.java
- src/test/java/com/example/urlshortener/app/SafetyValidationTest.java
- docs/API_CHANGELOG.md

## Approvals

- node `release`: APPROVED by auto-approver@demo-policy -- auto-approved under demo policy: all upstream stages succeeded and no policy violations are outstanding; in production this checkpoint would block on a human reviewer via the same decide() interface

## Policy violations recorded during this run

- none
