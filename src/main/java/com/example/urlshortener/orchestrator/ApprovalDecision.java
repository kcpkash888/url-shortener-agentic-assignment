package com.example.urlshortener.orchestrator;

public record ApprovalDecision(boolean approved, String approver, String rationale) {}
