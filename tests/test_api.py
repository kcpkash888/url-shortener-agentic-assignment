def test_create_url_returns_short_url(client):
    resp = client.post("/api/urls", json={"url": "https://example.com/page"})
    assert resp.status_code == 201
    body = resp.json()
    assert body["target_url"] == "https://example.com/page"
    assert body["short_url"].endswith(body["code"])


def test_create_url_rejects_invalid_url(client):
    resp = client.post("/api/urls", json={"url": "not-a-url"})
    assert resp.status_code == 422


def test_create_url_rejects_bad_alias_chars(client):
    resp = client.post("/api/urls", json={"url": "https://example.com", "custom_alias": "bad alias!"})
    assert resp.status_code == 422


def test_create_url_with_custom_alias_conflict(client):
    client.post("/api/urls", json={"url": "https://example.com", "custom_alias": "brand"})
    resp = client.post("/api/urls", json={"url": "https://other.com", "custom_alias": "brand"})
    assert resp.status_code == 409


def test_redirect_follows_to_target(client):
    created = client.post("/api/urls", json={"url": "https://example.com/target"}).json()
    resp = client.get(f"/{created['code']}", follow_redirects=False)
    assert resp.status_code == 302
    assert resp.headers["location"] == "https://example.com/target"


def test_redirect_missing_code_is_404(client):
    resp = client.get("/doesnotexist", follow_redirects=False)
    assert resp.status_code == 404


def test_get_url_metadata(client):
    created = client.post("/api/urls", json={"url": "https://example.com"}).json()
    resp = client.get(f"/api/urls/{created['code']}")
    assert resp.status_code == 200
    assert resp.json()["code"] == created["code"]


def test_delete_url_then_redirect_gone(client):
    created = client.post("/api/urls", json={"url": "https://example.com"}).json()
    del_resp = client.delete(f"/api/urls/{created['code']}")
    assert del_resp.status_code == 204
    resp = client.get(f"/{created['code']}", follow_redirects=False)
    assert resp.status_code == 410


def test_analytics_endpoint_tracks_clicks(client):
    created = client.post("/api/urls", json={"url": "https://example.com"}).json()
    client.get(f"/{created['code']}", follow_redirects=False)
    client.get(f"/{created['code']}", follow_redirects=False)
    resp = client.get(f"/api/urls/{created['code']}/analytics")
    assert resp.status_code == 200
    assert resp.json()["total_clicks"] == 2


def test_health_endpoint(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"


def test_rate_limit_returns_429(client, monkeypatch):
    from app.main import limiter

    limiter.max_requests = 3
    limiter.reset()
    for _ in range(3):
        assert client.post("/api/urls", json={"url": "https://example.com"}).status_code == 201
    resp = client.post("/api/urls", json={"url": "https://example.com"})
    assert resp.status_code == 429
    assert "Retry-After" in resp.headers
    limiter.max_requests = 60  # restore default for other tests
