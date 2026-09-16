# API Changelog

## Bulk short-link creation

_Generated 2026-09-16T15:35:41.729561+00:00 by the docs agent, run `greenfield-20260916T153541-e8a181`._

### API contract

- `POST /api/urls/bulk` -- Create up to 50 short links in a single request; per-item success/error.

### Data model changes

- No table/schema migration required; reuses the existing `links` table.

### Known risks

- A large batch is processed synchronously in-request; capped at 50 items to bound worst-case request latency.
- Partial failure (e.g. one alias conflict in a batch) does not roll back the other items in the batch -- each item is independent by design.

---

## Analytics top_referrers breakdown

_Generated 2026-09-16T15:35:44.002298+00:00 by the docs agent, run `brownfield-analytics-20260916T153543-183c0b`._

### API contract

- `GET /api/urls/{code}/analytics` -- Now also returns top_referrers: top 5 referrers by click count.

### Data model changes

- AnalyticsResponse gains `top_referrers: list[dict]`.

### Known risks

- Referrer aggregation adds one more query per analytics call; acceptable at current scale.

---

## Analytics top_referrers breakdown (+ unique_referrer_count)

_Generated 2026-09-16T15:35:46.355853+00:00 by the docs agent, run `brownfield-analytics-20260916T153543-183c0b`._

### API contract

- `GET /api/urls/{code}/analytics` -- Now also returns top_referrers and unique_referrer_count.

### Data model changes

- AnalyticsResponse gains `top_referrers: list[dict]`.
- AnalyticsResponse gains `unique_referrer_count: int`.

### Known risks

- Referrer aggregation adds one more query per analytics call; acceptable at current scale.

---

## Reject URLs that point at known-malicious or disallowed domains, and reserve system-critical short codes (api, health, admin) so they cannot be squatted.

_Generated 2026-09-16T15:35:50.800297+00:00 by the docs agent, run `ambiguous-20260916T153550-a27fb2`._

### API contract

- `POST /api/urls` -- Rejects blocklisted target domains and reserved short-code aliases with 422.
- `POST /api/urls/bulk` -- Applies the same safety checks per item, consistent with the single-create endpoint.

### Data model changes

- No schema/table change; validation happens in app/shortener.py:create_link.

### Known risks

- The domain blocklist is static and requires manual maintenance -- a live threat-intel feed was considered and explicitly rejected for this change (see requirements decision lineage) as disproportionate effort for the ask as stated.
- Reserved-alias list is hardcoded; adding a new reserved word later requires a code change, not just configuration.
