"""Tests for the analytics `top_referrers` enhancement, added by the
brownfield orchestration scenario (scenarios/brownfield_run.py)."""


def test_analytics_reports_top_referrers(client):
    created = client.post("/api/urls", json={"url": "https://example.com"}).json()
    code = created["code"]
    client.get(f"/{code}", headers={"referer": "https://google.com"}, follow_redirects=False)
    client.get(f"/{code}", headers={"referer": "https://google.com"}, follow_redirects=False)
    client.get(f"/{code}", headers={"referer": "https://bing.com"}, follow_redirects=False)

    resp = client.get(f"/api/urls/{code}/analytics")
    body = resp.json()
    assert "top_referrers" in body
    referrer_counts = {r["referrer"]: r["count"] for r in body["top_referrers"]}
    assert referrer_counts["https://google.com"] == 2
    assert referrer_counts["https://bing.com"] == 1
    assert "unique_referrer_count" in body and isinstance(body["unique_referrer_count"], int)
