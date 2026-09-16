"""Restore app/, tests/, docs/, and runs/ to their pristine pre-scenario
state so the three demo scenarios can be replayed from a clean baseline.

Why this exists: the scenarios are not simply idempotent against an already
-mutated codebase -- and that's deliberate, not an oversight. Greenfield's
design node, for example, genuinely refuses to "add" a route that already
exists (that guardrail is exactly what should fire if a real second PR tried
to add a route that already shipped). To let a grader run
`greenfield -> brownfield -> ambiguous` repeatedly and see the same
before/after diffs each time, we reset the working tree first, the same way
a CI job checks out a clean ref before each run.

Run with:  python -m scenarios.reset_demo_state
"""
import os
import shutil

from scenarios.common import PROJECT_ROOT

PRISTINE_MAIN_PY = '''import logging
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import RedirectResponse, JSONResponse

from app import config, db, shortener
from app.rate_limit import RateLimiter
from app.schemas import AnalyticsResponse, CreateLinkRequest, LinkResponse

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
logger = logging.getLogger("urlshortener")

limiter = RateLimiter(config.RATE_LIMIT_REQUESTS, config.RATE_LIMIT_WINDOW_SECONDS)


@asynccontextmanager
async def lifespan(_: FastAPI):
    db.init_db()
    logger.info("database initialized at %s", config.DB_PATH)
    yield


app = FastAPI(title="URL Shortener", version="1.0.0", lifespan=lifespan)


@app.middleware("http")
async def rate_limit_middleware(request: Request, call_next):
    # Redirects are the hot path and are deliberately excluded from the write-API
    # rate limit; abusive redirect traffic is a separate concern (CDN/WAF layer).
    if request.url.path.startswith("/api/"):
        client_key = request.client.host if request.client else "unknown"
        allowed, retry_after = limiter.allow(client_key)
        if not allowed:
            return JSONResponse(
                status_code=429,
                content={"detail": "rate limit exceeded"},
                headers={"Retry-After": str(retry_after)},
            )
    start = time.perf_counter()
    response = await call_next(request)
    duration_ms = (time.perf_counter() - start) * 1000
    logger.info("%s %s -> %s (%.1fms)", request.method, request.url.path, response.status_code, duration_ms)
    return response


def _to_response(link: dict) -> LinkResponse:
    return LinkResponse(
        code=link["code"],
        short_url=f"{config.BASE_HOST}/{link['code']}",
        target_url=link["target_url"],
        created_at=link["created_at"],
        expires_at=link["expires_at"],
        is_custom_alias=link["is_custom_alias"],
        click_count=link["click_count"],
        is_active=link["is_active"],
    )


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "cache": shortener.redirect_cache.stats()}


@app.post("/api/urls", response_model=LinkResponse, status_code=201)
def create_url(req: CreateLinkRequest) -> LinkResponse:
    try:
        link = shortener.create_link(req.url, req.custom_alias, req.expires_in_days)
    except shortener.AliasConflictError as e:
        raise HTTPException(status_code=409, detail=str(e))
    return _to_response(link)


@app.get("/api/urls/{code}", response_model=LinkResponse)
def get_url(code: str) -> LinkResponse:
    try:
        link = shortener.get_link(code, allow_inactive=True)
    except shortener.LinkNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e))
    return _to_response(link)


@app.get("/api/urls")
def list_urls(limit: int = 50, offset: int = 0) -> list[LinkResponse]:
    limit = max(1, min(limit, 200))
    return [_to_response(link) for link in shortener.list_links(limit, offset)]


@app.delete("/api/urls/{code}", status_code=204)
def delete_url(code: str) -> None:
    try:
        shortener.deactivate_link(code)
    except shortener.LinkNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e))


@app.get("/api/urls/{code}/analytics", response_model=AnalyticsResponse)
def get_analytics(code: str) -> AnalyticsResponse:
    try:
        return shortener.get_analytics(code)
    except shortener.LinkNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e))


@app.get("/{code}")
def redirect(code: str, request: Request):
    try:
        target = shortener.resolve_link(
            code,
            referrer=request.headers.get("referer"),
            user_agent=request.headers.get("user-agent"),
        )
    except shortener.LinkNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except shortener.LinkExpiredError as e:
        raise HTTPException(status_code=410, detail=str(e))
    except shortener.LinkInactiveError as e:
        raise HTTPException(status_code=410, detail=str(e))
    return RedirectResponse(url=target, status_code=302)
'''

PRISTINE_SCHEMAS_PY = '''import re
from datetime import datetime

from pydantic import BaseModel, Field, field_validator

ALIAS_RE = re.compile(r"^[A-Za-z0-9_-]+$")


class CreateLinkRequest(BaseModel):
    url: str = Field(..., description="The long URL to shorten")
    custom_alias: str | None = Field(None, description="Optional custom short code")
    expires_in_days: int | None = Field(None, ge=1, le=3650)

    @field_validator("url")
    @classmethod
    def validate_url(cls, v: str) -> str:
        if not re.match(r"^https?://", v.strip(), re.IGNORECASE):
            raise ValueError("url must start with http:// or https://")
        if len(v) > 2048:
            raise ValueError("url exceeds maximum length of 2048 characters")
        return v.strip()

    @field_validator("custom_alias")
    @classmethod
    def validate_alias(cls, v: str | None) -> str | None:
        if v is None:
            return v
        if not (1 <= len(v) <= 32):
            raise ValueError("custom_alias must be between 1 and 32 characters")
        if not ALIAS_RE.match(v):
            raise ValueError("custom_alias may only contain letters, digits, '-', '_'")
        return v


class LinkResponse(BaseModel):
    code: str
    short_url: str
    target_url: str
    created_at: str
    expires_at: str | None
    is_custom_alias: bool
    click_count: int
    is_active: bool


class ClickEvent(BaseModel):
    clicked_at: str
    referrer: str | None
    user_agent: str | None


class AnalyticsResponse(BaseModel):
    code: str
    total_clicks: int
    created_at: str
    expires_at: str | None
    is_active: bool
    recent_clicks: list[ClickEvent]
'''

PRISTINE_SHORTENER_PY = '''"""Core URL-shortening domain logic: code generation, persistence, resolution,
click tracking and analytics. Kept independent of the web framework so it can be
unit-tested directly and reused by the orchestrator's implementation/testing agents.
"""
import secrets
import string
from datetime import datetime, timedelta, timezone

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
    return {
        "code": code,
        "total_clicks": link["click_count"],
        "created_at": link["created_at"],
        "expires_at": link["expires_at"],
        "is_active": link["is_active"],
        "recent_clicks": [dict(r) for r in rows],
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
'''

GENERATED_TEST_FILES = ["test_bulk.py", "test_analytics_enhancement.py", "test_url_safety.py"]
GENERATED_APP_FILES = ["audit_log.py"]


def main() -> None:
    with open(os.path.join(PROJECT_ROOT, "app", "main.py"), "w", encoding="utf-8") as f:
        f.write(PRISTINE_MAIN_PY)
    with open(os.path.join(PROJECT_ROOT, "app", "schemas.py"), "w", encoding="utf-8") as f:
        f.write(PRISTINE_SCHEMAS_PY)
    with open(os.path.join(PROJECT_ROOT, "app", "shortener.py"), "w", encoding="utf-8") as f:
        f.write(PRISTINE_SHORTENER_PY)
    print("restored app/main.py, app/schemas.py, app/shortener.py to pristine state")

    for fname in GENERATED_APP_FILES:
        path = os.path.join(PROJECT_ROOT, "app", fname)
        if os.path.exists(path):
            os.remove(path)
            print(f"removed app/{fname}")

    for fname in GENERATED_TEST_FILES:
        path = os.path.join(PROJECT_ROOT, "tests", fname)
        if os.path.exists(path):
            os.remove(path)
            print(f"removed tests/{fname}")

    changelog_path = os.path.join(PROJECT_ROOT, "docs", "API_CHANGELOG.md")
    if os.path.exists(changelog_path):
        os.remove(changelog_path)
        print("removed docs/API_CHANGELOG.md")

    runs_dir = os.path.join(PROJECT_ROOT, "runs")
    for entry in os.listdir(runs_dir):
        full = os.path.join(runs_dir, entry)
        if os.path.isdir(full):
            shutil.rmtree(full)
    print("cleared runs/")

    db_path = os.path.join(PROJECT_ROOT, "urlshortener.db")
    if os.path.exists(db_path):
        os.remove(db_path)
        print("removed urlshortener.db")

    print("\ndemo state reset -- safe to run greenfield_run, brownfield_run, ambiguous_run from a clean baseline")


if __name__ == "__main__":
    main()
