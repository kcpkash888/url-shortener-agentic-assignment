import re
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
    top_referrers: list[dict] = []
    unique_referrer_count: int | None = None


class BulkCreateItem(BaseModel):
    url: str
    custom_alias: str | None = None
    expires_in_days: int | None = Field(None, ge=1, le=3650)


class BulkCreateRequest(BaseModel):
    urls: list[BulkCreateItem]


class BulkResultItem(BaseModel):
    success: bool
    link: LinkResponse | None = None
    error: str | None = None
    url: str | None = None


class BulkCreateResponse(BaseModel):
    results: list[BulkResultItem]
