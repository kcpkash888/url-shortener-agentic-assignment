package com.example.urlshortener.app.dto;

public record BulkResultItem(boolean success, LinkResponse link, String error, String url) {}
