# Release note -- greenfield: bulk short-link creation (Java) (run greenfield-java-20260916T172914-e51d57)

Generated 2026-09-16T17:29:41.106113500Z

**Original request:** Add a bulk short-link creation endpoint: POST /api/urls/bulk must accept a list of up to 50 URLs and return one short link (or a per-item error) for each, reusing the existing validation and code-generation logic in ShortenerService.createLink.

## Decision lineage

- **[requirements/requirements]** Treated request as already concrete; proceeding without a clarification step.
  - rationale: No vague trigger terms were found, or the request already states explicit acceptance criteria ('Add a bulk short-link creation endpoint: POST /api/urls/bulk must accept a list of up to 50 URLs and return one short link (or a per-item error) for each, reusing the existing validation and code-generation logic in ShortenerService.createLink.').
- **[design/design]** Approved design for 'Bulk short-link creation': [POST /api/urls/bulk]
  - rationale: Checked proposed routes against UrlController.java's current route table -- no problems. Data model changes: [No table/schema migration required; reuses the existing `links` table.]. Known risks accepted for this scope: [A large batch is processed synchronously in-request; capped at 50 items to bound worst-case request latency., Partial failure (e.g. one alias conflict in a batch) does not roll back the other items in the batch -- each item is independent by design.].
- **[implementation/implementation]** Wrote/modified 7 file(s): [src/main/java/com/example/urlshortener/app/dto/BulkCreateItem.java, src/main/java/com/example/urlshortener/app/dto/BulkCreateRequest.java, src/main/java/com/example/urlshortener/app/dto/BulkResultItem.java, src/main/java/com/example/urlshortener/app/dto/BulkCreateResponse.java, src/main/java/com/example/urlshortener/app/UrlController.java, src/main/java/com/example/urlshortener/app/GlobalExceptionHandler.java, src/test/java/com/example/urlshortener/app/BulkControllerTest.java]
  - rationale: Implementation follows the approved design's API contract and data model changes verbatim.
- **[docs/docs]** Documented 'Bulk short-link creation' in docs/API_CHANGELOG.md
  - rationale: Doc content is generated directly from the design node's approved API contract, so it cannot describe an endpoint that wasn't actually implemented.
- **[testing/testing]** mvn test passed: Tests run: 43, Failures: 0, Errors: 0, Skipped: 0
  - rationale: Ran the real build+test as an exit gate (Maven compiles before testing, so this also catches compile errors); downstream docs/release nodes are policy-blocked from proceeding on a failing result.

## Artifacts touched

- src/main/java/com/example/urlshortener/app/dto/BulkCreateItem.java
- src/main/java/com/example/urlshortener/app/dto/BulkCreateRequest.java
- src/main/java/com/example/urlshortener/app/dto/BulkResultItem.java
- src/main/java/com/example/urlshortener/app/dto/BulkCreateResponse.java
- src/main/java/com/example/urlshortener/app/UrlController.java
- src/main/java/com/example/urlshortener/app/GlobalExceptionHandler.java
- src/test/java/com/example/urlshortener/app/BulkControllerTest.java
- docs/API_CHANGELOG.md

## Approvals

- node `release`: APPROVED by auto-approver@demo-policy -- auto-approved under demo policy: all upstream stages succeeded and no policy violations are outstanding; in production this checkpoint would block on a human reviewer via the same decide() interface

## Policy violations recorded during this run

- none
