package com.example.urlshortener.app.exceptions;

public class UnsafeTargetException extends RuntimeException {
    public UnsafeTargetException(String message) {
        super(message);
    }
}
