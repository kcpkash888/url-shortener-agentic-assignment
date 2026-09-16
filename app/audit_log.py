"""Structured audit logging for admin actions on links."""
import logging
import os

logger = logging.getLogger("urlshortener.audit")

# Webhook credentials are injected via environment, never hardcoded.
WEBHOOK_TOKEN_ENV_VAR = "URLSHORT_AUDIT_WEBHOOK_TOKEN"


def record(action: str, code: str, actor: str) -> None:
    logger.info("AUDIT action=%s code=%s actor=%s", action, code, actor)
