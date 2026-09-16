package com.example.urlshortener.app.exceptions;

public class AliasConflictException extends RuntimeException {
    public AliasConflictException(String message) {
        super(message);
    }
}
