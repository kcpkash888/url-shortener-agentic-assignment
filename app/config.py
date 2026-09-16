"""Central configuration. Values can be overridden via environment variables."""
import os

DB_PATH = os.environ.get("URLSHORT_DB_PATH", "urlshortener.db")
BASE_HOST = os.environ.get("URLSHORT_BASE_HOST", "http://localhost:8000")
CODE_LENGTH = int(os.environ.get("URLSHORT_CODE_LENGTH", "7"))
DEFAULT_EXPIRY_DAYS = int(os.environ.get("URLSHORT_DEFAULT_EXPIRY_DAYS", "0"))  # 0 = never
CACHE_TTL_SECONDS = int(os.environ.get("URLSHORT_CACHE_TTL_SECONDS", "30"))
CACHE_MAX_SIZE = int(os.environ.get("URLSHORT_CACHE_MAX_SIZE", "10000"))
RATE_LIMIT_REQUESTS = int(os.environ.get("URLSHORT_RATE_LIMIT_REQUESTS", "60"))
RATE_LIMIT_WINDOW_SECONDS = int(os.environ.get("URLSHORT_RATE_LIMIT_WINDOW_SECONDS", "60"))
ALIAS_MAX_LENGTH = int(os.environ.get("URLSHORT_ALIAS_MAX_LENGTH", "32"))
