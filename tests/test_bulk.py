"""Tests for the bulk short-link creation endpoint, added by the greenfield
orchestration scenario (scenarios/greenfield_run.py)."""


def test_bulk_create_returns_link_per_item(client):
    resp = client.post(
        "/api/urls/bulk",
        json={"urls": [{"url": "https://example.com/a"}, {"url": "https://example.com/b"}]},
    )
    assert resp.status_code == 201
    body = resp.json()
    assert len(body["results"]) == 2
    assert all(r["success"] for r in body["results"])
    assert all(r["link"]["target_url"].startswith("https://example.com") for r in body["results"])


def test_bulk_create_reports_partial_failure_on_alias_conflict(client):
    client.post("/api/urls", json={"url": "https://example.com", "custom_alias": "dup"})
    resp = client.post(
        "/api/urls/bulk",
        json={
            "urls": [
                {"url": "https://example.com/x", "custom_alias": "dup"},
                {"url": "https://example.com/y"},
            ]
        },
    )
    body = resp.json()
    assert body["results"][0]["success"] is False
    assert "error" in body["results"][0] and body["results"][0]["error"]
    assert body["results"][1]["success"] is True


def test_bulk_create_rejects_over_50_items(client):
    resp = client.post("/api/urls/bulk", json={"urls": [{"url": "https://example.com"}] * 51})
    assert resp.status_code == 422
