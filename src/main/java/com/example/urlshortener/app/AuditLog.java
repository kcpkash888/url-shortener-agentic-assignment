package com.example.urlshortener.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Structured audit logging for admin actions on links. */
public final class AuditLog {
    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    // Webhook credentials are injected via environment, never hardcoded.
    private static final String WEBHOOK_TOKEN_ENV_VAR = "URLSHORT_AUDIT_WEBHOOK_TOKEN";

    private AuditLog() {}

    public static void record(String action, String code, String actor) {
        log.info("AUDIT action={} code={} actor={}", action, code, actor);
    }
}
