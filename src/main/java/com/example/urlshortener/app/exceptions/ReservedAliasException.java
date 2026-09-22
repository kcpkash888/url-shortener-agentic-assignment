package com.example.urlshortener.app.exceptions;

public class ReservedAliasException extends RuntimeException {
    public ReservedAliasException(String message) {
        super(message);
    }
}
