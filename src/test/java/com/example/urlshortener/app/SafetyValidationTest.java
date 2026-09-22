package com.example.urlshortener.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SafetyValidationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ShortenerService shortener;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void cleanDb() {
        jdbc.execute("DELETE FROM clicks");
        jdbc.execute("DELETE FROM links");
        shortener.redirectCache.clear();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void blockedDomainIsRejected() {
        ResponseEntity<String> resp = rest.postForEntity(
                url("/api/urls"),
                new com.example.urlshortener.app.dto.CreateLinkRequest("https://malicious-example.test/phish", null, null),
                String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void reservedAliasIsRejected() {
        ResponseEntity<String> resp = rest.postForEntity(
                url("/api/urls"),
                new com.example.urlshortener.app.dto.CreateLinkRequest("https://example.com", "api", null),
                String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void safeUrlAndAliasStillWork() {
        ResponseEntity<String> resp = rest.postForEntity(
                url("/api/urls"),
                new com.example.urlshortener.app.dto.CreateLinkRequest("https://example.com/fine", "my-brand-2", null),
                String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void bulkReportsUnsafeItemAsPerItemErrorNotHardFailure() throws Exception {
        var req = new com.example.urlshortener.app.dto.BulkCreateRequest(java.util.List.of(
                new com.example.urlshortener.app.dto.BulkCreateItem("https://malicious-example.test/x", null, null),
                new com.example.urlshortener.app.dto.BulkCreateItem("https://example.com/y", null, null)
        ));
        ResponseEntity<String> resp = rest.postForEntity(url("/api/urls/bulk"), req, String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        JsonNode body = mapper.readTree(resp.getBody());
        assertThat(body.get("results").get(0).get("success").asBoolean()).isFalse();
        assertThat(body.get("results").get(1).get("success").asBoolean()).isTrue();
    }
}
