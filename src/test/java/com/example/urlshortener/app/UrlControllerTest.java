package com.example.urlshortener.app;

import com.example.urlshortener.app.dto.CreateLinkRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UrlControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ShortenerService shortener;

    @Autowired
    private RateLimiter rateLimiter;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void cleanDb() {
        jdbc.execute("DELETE FROM clicks");
        jdbc.execute("DELETE FROM links");
        shortener.redirectCache.clear();
        rateLimiter.reset();
        rateLimiter.setMaxRequests(60);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void createUrlReturnsShortUrl() throws Exception {
        ResponseEntity<String> resp = rest.postForEntity(
                url("/api/urls"), new CreateLinkRequest("https://example.com/page", null, null), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        JsonNode body = mapper.readTree(resp.getBody());
        assertThat(body.get("target_url").asText()).isEqualTo("https://example.com/page");
        assertThat(body.get("short_url").asText()).endsWith(body.get("code").asText());
    }

    @Test
    void createUrlRejectsInvalidUrl() {
        ResponseEntity<String> resp = rest.postForEntity(
                url("/api/urls"), new CreateLinkRequest("not-a-url", null, null), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void createUrlWithCustomAliasConflict() {
        rest.postForEntity(url("/api/urls"), new CreateLinkRequest("https://example.com", "brand", null), String.class);
        ResponseEntity<String> resp = rest.postForEntity(
                url("/api/urls"), new CreateLinkRequest("https://other.com", "brand", null), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void redirectFollowsToTarget() throws Exception {
        ResponseEntity<String> created = rest.postForEntity(
                url("/api/urls"), new CreateLinkRequest("https://example.com/target", null, null), String.class);
        String code = mapper.readTree(created.getBody()).get("code").asText();

        ResponseEntity<Void> resp = rest.exchange(
                RequestEntity.get(URI.create(url("/" + code))).build(), Void.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(302);
        assertThat(resp.getHeaders().getLocation().toString()).isEqualTo("https://example.com/target");
    }

    @Test
    void redirectMissingCodeIs404() {
        ResponseEntity<Void> resp = rest.exchange(
                RequestEntity.get(URI.create(url("/doesnotexist"))).build(), Void.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void deleteUrlThenRedirectGone() throws Exception {
        ResponseEntity<String> created = rest.postForEntity(
                url("/api/urls"), new CreateLinkRequest("https://example.com", null, null), String.class);
        String code = mapper.readTree(created.getBody()).get("code").asText();

        rest.delete(url("/api/urls/" + code));

        ResponseEntity<Void> resp = rest.exchange(
                RequestEntity.get(URI.create(url("/" + code))).build(), Void.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(410);
    }

    @Test
    void analyticsEndpointTracksClicks() throws Exception {
        ResponseEntity<String> created = rest.postForEntity(
                url("/api/urls"), new CreateLinkRequest("https://example.com", null, null), String.class);
        String code = mapper.readTree(created.getBody()).get("code").asText();
        rest.exchange(RequestEntity.get(URI.create(url("/" + code))).build(), Void.class);
        rest.exchange(RequestEntity.get(URI.create(url("/" + code))).build(), Void.class);

        ResponseEntity<String> analytics = rest.getForEntity(url("/api/urls/" + code + "/analytics"), String.class);
        assertThat(mapper.readTree(analytics.getBody()).get("total_clicks").asInt()).isEqualTo(2);
    }

    @Test
    void healthEndpoint() {
        ResponseEntity<String> resp = rest.getForEntity(url("/health"), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void rateLimitReturns429() {
        rateLimiter.setMaxRequests(3);
        rateLimiter.reset();
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> resp = rest.postForEntity(
                    url("/api/urls"), new CreateLinkRequest("https://example.com", null, null), String.class);
            assertThat(resp.getStatusCode().value()).isEqualTo(201);
        }
        ResponseEntity<String> fourth = rest.postForEntity(
                url("/api/urls"), new CreateLinkRequest("https://example.com", null, null), String.class);
        assertThat(fourth.getStatusCode().value()).isEqualTo(429);
        assertThat(fourth.getHeaders().containsKey("Retry-After")).isTrue();
    }
}
