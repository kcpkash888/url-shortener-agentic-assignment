"""Tests for the URL-safety hardening added by the ambiguous-requirement
orchestration scenario (scenarios/ambiguous_run.py)."""


def test_blocked_domain_is_rejected(client):
    resp = client.post("/api/urls", json={"url": "https://malicious-example.test/phish"})
    assert resp.status_code == 422


def test_reserved_alias_is_rejected(client):
    resp = client.post("/api/urls", json={"url": "https://example.com", "custom_alias": "api"})
    assert resp.status_code == 422


def test_safe_url_and_alias_still_work(client):
    resp = client.post("/api/urls", json={"url": "https://example.com/fine", "custom_alias": "my-brand-2"})
    assert resp.status_code == 201


def test_bulk_reports_unsafe_item_as_a_per_item_error_not_a_hard_failure(client):
    resp = client.post(
        "/api/urls/bulk",
        json={"urls": [{"url": "https://malicious-example.test/x"}, {"url": "https://example.com/y"}]},
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["results"][0]["success"] is False
    assert body["results"][1]["success"] is True
