package com.example.urlshortener.app.dto;

import java.util.List;

public record BulkCreateResponse(List<BulkResultItem> results) {}
