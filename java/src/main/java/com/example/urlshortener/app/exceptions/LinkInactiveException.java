package com.example.urlshortener.app.exceptions;

public class LinkInactiveException extends RuntimeException {
    public LinkInactiveException(String message) {
        super(message);
    }
}
