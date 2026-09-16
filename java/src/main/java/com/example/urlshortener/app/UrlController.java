package com.example.urlshortener.app;

import com.example.urlshortener.app.dto.AnalyticsResponse;
import com.example.urlshortener.app.dto.BulkCreateRequest;
import com.example.urlshortener.app.dto.BulkCreateResponse;
import com.example.urlshortener.app.dto.BulkResultItem;
import com.example.urlshortener.app.dto.CreateLinkRequest;
import com.example.urlshortener.app.dto.LinkResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class UrlController {

    private final ShortenerService shortener;
    private final String baseHost;

    public UrlController(ShortenerService shortener, @Value("${urlshort.base-host}") String baseHost) {
        this.shortener = shortener;
        this.baseHost = baseHost;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "cache", shortener.redirectCache.stats());
    }

    @PostMapping("/api/urls")
    public ResponseEntity<LinkResponse> createUrl(@Valid @RequestBody CreateLinkRequest req) {
        Link link = shortener.createLink(req.url(), req.customAlias(), req.expiresInDays());
        return ResponseEntity.status(HttpStatus.CREATED).body(LinkResponse.from(link, baseHost));
    }

    @GetMapping("/api/urls/{code}")
    public LinkResponse getUrl(@PathVariable String code) {
        return LinkResponse.from(shortener.getLink(code, true), baseHost);
    }

    @GetMapping("/api/urls")
    public List<LinkResponse> listUrls(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        int clampedLimit = Math.max(1, Math.min(limit, 200));
        return shortener.listLinks(clampedLimit, offset).stream()
                .map(l -> LinkResponse.from(l, baseHost))
                .toList();
    }

    @DeleteMapping("/api/urls/{code}")
    public ResponseEntity<Void> deleteUrl(@PathVariable String code) {
        shortener.deactivateLink(code);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/urls/{code}/analytics")
    public AnalyticsResponse getAnalytics(@PathVariable String code) {
        return AnalyticsResponse.from(shortener.getAnalytics(code, 20));
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        String target = shortener.resolveLink(code, request.getHeader("referer"), request.getHeader("user-agent"));
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, target).build();
    }

    @PostMapping("/api/urls/bulk")
    public ResponseEntity<BulkCreateResponse> createUrlsBulk(@jakarta.validation.Valid @RequestBody BulkCreateRequest req) {
        if (req.urls().size() > 50) {
            throw new IllegalArgumentException("a bulk request may contain at most 50 urls");
        }
        java.util.List<BulkResultItem> results = new java.util.ArrayList<>();
        for (var item : req.urls()) {
            try {
                var link = shortener.createLink(item.url(), item.customAlias(), item.expiresInDays());
                results.add(new BulkResultItem(true, LinkResponse.from(link, baseHost), null, null));
            } catch (com.example.urlshortener.app.exceptions.AliasConflictException
                    | com.example.urlshortener.app.exceptions.UnsafeTargetException
                    | com.example.urlshortener.app.exceptions.ReservedAliasException e) {
                results.add(new BulkResultItem(false, null, e.getMessage(), item.url()));
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(new BulkCreateResponse(results));
    }
}
