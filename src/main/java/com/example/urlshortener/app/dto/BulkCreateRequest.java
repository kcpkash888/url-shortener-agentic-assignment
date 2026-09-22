package com.example.urlshortener.app.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BulkCreateRequest(
        @NotEmpty
        @Valid
        List<BulkCreateItem> urls
) {}
