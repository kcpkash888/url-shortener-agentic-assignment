"""Core URL-shortening domain logic: code generation, persistence, resolution,
click tracking and analytics. Kept independent of the web framework so it can be
unit-tested directly and reused by the orchestrator's implementation/testing agents.
"""
import secrets
import string
from datetime import datetime, timedelta, timezone
from urllib.parse import urlparse

from app import config, db
from app.cache import TTLCache

_ALPHABET = string.digits + string.ascii_lowercase + string.ascii_uppercase  # base62


class LinkNotFoundError(Exception):
    pass


class LinkExpiredError(Exception):
    pass


class LinkInactiveError(Exception):
    pass


class AliasConflictError(Exception):
    pass


class UnsafeTargetError(Exception):
    pass


class ReservedAliasError(Exception):
    pass


BLOCKED_DOMAINS = {"malicious-example.test", "phishing-example.test"}
RESERVED_ALIASES = {"api", "health", "admin", "www"}


redirect_cache = TTLCache(max_size=config.CACHE_MAX_SIZE, ttl_seconds=config.CACHE_TTL_SECONDS)


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _generate_code(length: int = config.CODE_LENGTH) -> str:
    return "".join(secrets.choice(_ALPHABET) for _ in range(length))


def _code_exists(code: str) -> bool:
    conn = db.get_connection()
    row = conn.execute("SELECT 1 FROM links WHERE code = ?", (code,)).fetchone()
    return row is not None


def create_link(url: str, custom_alias: str | None = None, expires_in_days: int | None = None) -> dict:
    domain = urlparse(url).netloc.lower()
    if domain in BLOCKED_DOMAINS:
        raise UnsafeTargetError(f"target domain '{domain}' is not allowed")
    if custom_alias and custom_alias.lower() in RESERVED_ALIASES:
        raise ReservedAliasError(f"alias '{custom_alias}' is reserved for internal use")
    if custom_alias:
        if _code_exists(custom_alias):
            raise AliasConflictError(f"alias '{custom_alias}' is already in use")
        code = custom_alias
        is_custom = 1
    else:
        code = _generate_code()
        attempts = 0
        while _code_exists(code):
            attempts += 1
            if attempts > 5:
                raise RuntimeError("failed to generate a unique code after 5 attempts")
            code = _generate_code()
        is_custom = 0

    created_at = _now_iso()
    expires_at = None
    if expires_in_days:
        expires_at = (datetime.now(timezone.utc) + timedelta(days=expires_in_days)).isoformat()

    with db.transaction() as conn:
        conn.execute(
            "INSERT INTO links (code, target_url, created_at, expires_at, is_custom_alias, click_count, is_active) "
            "VALUES (?, ?, ?, ?, ?, 0, 1)",
            (code, url, created_at, expires_at, is_custom),
        )

    return get_link(code, allow_inactive=True)


def get_link(code: str, allow_inactive: bool = False) -> dict:
    conn = db.get_connection()
    row = conn.execute("SELECT * FROM links WHERE code = ?", (code,)).fetchone()
    if row is None:
        raise LinkNotFoundError(f"no link found for code '{code}'")
    link = dict(row)
    link["is_custom_alias"] = bool(link["is_custom_alias"])
    link["is_active"] = bool(link["is_active"])
    if not allow_inactive and not link["is_active"]:
        raise LinkInactiveError(f"link '{code}' has been deactivated")
    return link


def resolve_link(code: str, referrer: str | None = None, user_agent: str | None = None) -> str:
    """Resolve a short code to its target URL, recording a click event.
    Uses the TTL cache for the hot lookup path; click recording always hits the DB
    (writes must not be lost even if the read path is cached).
    """
    cached_target = redirect_cache.get(code)
    if cached_target is not None:
        target_url = cached_target
    else:
        link = get_link(code)
        if link["expires_at"] and datetime.fromisoformat(link["expires_at"]) < datetime.now(timezone.utc):
            raise LinkExpiredError(f"link '{code}' expired at {link['expires_at']}")
        target_url = link["target_url"]
        redirect_cache.set(code, target_url)

    with db.transaction() as conn:
        conn.execute("UPDATE links SET click_count = click_count + 1 WHERE code = ?", (code,))
        conn.execute(
            "INSERT INTO clicks (code, clicked_at, referrer, user_agent) VALUES (?, ?, ?, ?)",
            (code, _now_iso(), referrer, user_agent),
        )
    return target_url


def deactivate_link(code: str) -> None:
    get_link(code, allow_inactive=True)  # raises LinkNotFoundError if missing
    with db.transaction() as conn:
        conn.execute("UPDATE links SET is_active = 0 WHERE code = ?", (code,))
    redirect_cache.invalidate(code)


def get_analytics(code: str, recent_limit: int = 20) -> dict:
    link = get_link(code, allow_inactive=True)
    conn = db.get_connection()
    rows = conn.execute(
        "SELECT clicked_at, referrer, user_agent FROM clicks WHERE code = ? "
        "ORDER BY clicked_at DESC LIMIT ?",
        (code, recent_limit),
    ).fetchall()
    referrer_rows = conn.execute(
        "SELECT COALESCE(referrer, 'direct') AS referrer, COUNT(*) as count FROM clicks "
        "WHERE code = ? GROUP BY referrer ORDER BY count DESC LIMIT 5",
        (code,),
    ).fetchall()
    top_referrers = [{"referrer": r["referrer"], "count": r["count"]} for r in referrer_rows]
    unique_referrer_count = len(referrer_rows)
    return {
        "code": code,
        "total_clicks": link["click_count"],
        "created_at": link["created_at"],
        "expires_at": link["expires_at"],
        "is_active": link["is_active"],
        "recent_clicks": [dict(r) for r in rows],
        "top_referrers": top_referrers,
        "unique_referrer_count": unique_referrer_count,
    }


def list_links(limit: int = 50, offset: int = 0) -> list[dict]:
    conn = db.get_connection()
    rows = conn.execute(
        "SELECT * FROM links ORDER BY created_at DESC LIMIT ? OFFSET ?", (limit, offset)
    ).fetchall()
    out = []
    for r in rows:
        d = dict(r)
        d["is_custom_alias"] = bool(d["is_custom_alias"])
        d["is_active"] = bool(d["is_active"])
        out.append(d)
    return out
