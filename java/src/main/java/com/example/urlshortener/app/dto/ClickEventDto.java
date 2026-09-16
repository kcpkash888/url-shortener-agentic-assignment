package com.example.urlshortener.app.dto;

import com.example.urlshortener.app.ClickRecord;

public record ClickEventDto(String clickedAt, String referrer, String userAgent) {
    public static ClickEventDto from(ClickRecord r) {
        return new ClickEventDto(r.clickedAt(), r.referrer(), r.userAgent());
    }
}
