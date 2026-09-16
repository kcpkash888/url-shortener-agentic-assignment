import logging
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import RedirectResponse, JSONResponse

from app import config, db, shortener
from app.rate_limit import RateLimiter
from app.schemas import (
    AnalyticsResponse,
    BulkCreateRequest,
    BulkCreateResponse,
    BulkResultItem,
    CreateLinkRequest,
    LinkResponse,
)

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
    except (shortener.UnsafeTargetError, shortener.ReservedAliasError) as e:
        raise HTTPException(status_code=422, detail=str(e))
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


@app.post("/api/urls/bulk", response_model=BulkCreateResponse, status_code=201)
def create_urls_bulk(req: BulkCreateRequest) -> BulkCreateResponse:
    if len(req.urls) > 50:
        raise HTTPException(status_code=422, detail="a bulk request may contain at most 50 urls")
    results: list[BulkResultItem] = []
    for item in req.urls:
        try:
            link = shortener.create_link(item.url, item.custom_alias, item.expires_in_days)
            results.append(BulkResultItem(success=True, link=_to_response(link)))
        except (shortener.AliasConflictError, shortener.UnsafeTargetError, shortener.ReservedAliasError) as e:
            results.append(BulkResultItem(success=False, error=str(e), url=item.url))
    return BulkCreateResponse(results=results)
