import time

import pytest

from app import shortener


def test_create_and_resolve_roundtrip():
    link = shortener.create_link("https://example.com/some/long/path")
    assert len(link["code"]) == 7
    target = shortener.resolve_link(link["code"])
    assert target == "https://example.com/some/long/path"


def test_click_count_increments():
    link = shortener.create_link("https://example.com")
    shortener.resolve_link(link["code"])
    shortener.resolve_link(link["code"])
    refreshed = shortener.get_link(link["code"])
    assert refreshed["click_count"] == 2


def test_custom_alias_used_verbatim():
    link = shortener.create_link("https://example.com", custom_alias="my-brand")
    assert link["code"] == "my-brand"
    assert link["is_custom_alias"] is True


def test_custom_alias_conflict_raises():
    shortener.create_link("https://example.com", custom_alias="dup")
    with pytest.raises(shortener.AliasConflictError):
        shortener.create_link("https://other.com", custom_alias="dup")


def test_resolve_missing_code_raises_not_found():
    with pytest.raises(shortener.LinkNotFoundError):
        shortener.resolve_link("doesnotexist")


def test_expired_link_raises_on_resolve():
    link = shortener.create_link("https://example.com", expires_in_days=1)
    with shortener_db_patched_to_past(link["code"]):
        with pytest.raises(shortener.LinkExpiredError):
            shortener.resolve_link(link["code"])


def test_deactivated_link_is_rejected_on_resolve():
    link = shortener.create_link("https://example.com")
    shortener.deactivate_link(link["code"])
    with pytest.raises(shortener.LinkInactiveError):
        shortener.resolve_link(link["code"])


def test_deactivate_missing_code_raises_not_found():
    with pytest.raises(shortener.LinkNotFoundError):
        shortener.deactivate_link("nope")


def test_analytics_reports_recent_clicks():
    link = shortener.create_link("https://example.com")
    shortener.resolve_link(link["code"], referrer="https://google.com", user_agent="pytest")
    analytics = shortener.get_analytics(link["code"])
    assert analytics["total_clicks"] == 1
    assert analytics["recent_clicks"][0]["referrer"] == "https://google.com"


def test_cache_is_used_on_repeat_resolve():
    link = shortener.create_link("https://example.com")
    shortener.resolve_link(link["code"])
    hits_before = shortener.redirect_cache.stats()["hits"]
    shortener.resolve_link(link["code"])
    hits_after = shortener.redirect_cache.stats()["hits"]
    assert hits_after == hits_before + 1


class shortener_db_patched_to_past:
    """Directly rewrites expires_at to the past to deterministically test expiry
    without sleeping in the test suite."""

    def __init__(self, code: str):
        self.code = code

    def __enter__(self):
        from app import db

        with db.transaction() as conn:
            conn.execute(
                "UPDATE links SET expires_at = '2000-01-01T00:00:00+00:00' WHERE code = ?",
                (self.code,),
            )
        shortener.redirect_cache.invalidate(self.code)

    def __exit__(self, *exc):
        return False
