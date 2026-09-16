package com.example.urlshortener.orchestrator;

public record PolicyResult(boolean passed, String message) {
    public static PolicyResult ok() {
        return new PolicyResult(true, "");
    }

    public static PolicyResult fail(String message) {
        return new PolicyResult(false, message);
    }
}
