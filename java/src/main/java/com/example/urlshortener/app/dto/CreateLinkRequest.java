package com.example.urlshortener.app.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateLinkRequest(
        @NotBlank
        @Pattern(regexp = "^https?://.+", message = "url must start with http:// or https://")
        @Size(max = 2048, message = "url exceeds maximum length of 2048 characters")
        String url,

        @Pattern(regexp = "^[A-Za-z0-9_-]{1,32}$", message = "custom_alias may only contain letters, digits, '-', '_' and be 1-32 characters")
        String customAlias,

        @Min(1) @Max(3650)
        Integer expiresInDays
) {}
