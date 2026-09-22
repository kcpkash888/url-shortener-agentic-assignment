package com.example.urlshortener.app;

import com.example.urlshortener.app.dto.BulkCreateItem;
import com.example.urlshortener.app.dto.BulkCreateRequest;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BulkControllerTest {

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
    void bulkCreateReturnsLinkPerItem() throws Exception {
        var req = new BulkCreateRequest(List.of(
                new BulkCreateItem("https://example.com/a", null, null),
                new BulkCreateItem("https://example.com/b", null, null)
        ));
        ResponseEntity<String> resp = rest.postForEntity(url("/api/urls/bulk"), req, String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        JsonNode body = mapper.readTree(resp.getBody());
        assertThat(body.get("results")).hasSize(2);
        for (JsonNode item : body.get("results")) {
            assertThat(item.get("success").asBoolean()).isTrue();
        }
    }

    @Test
    void bulkCreateReportsPartialFailureOnAliasConflict() throws Exception {
        rest.postForEntity(url("/api/urls"), new com.example.urlshortener.app.dto.CreateLinkRequest("https://example.com", "dup", null), String.class);
        var req = new BulkCreateRequest(List.of(
                new BulkCreateItem("https://example.com/x", "dup", null),
                new BulkCreateItem("https://example.com/y", null, null)
        ));
        ResponseEntity<String> resp = rest.postForEntity(url("/api/urls/bulk"), req, String.class);
        JsonNode body = mapper.readTree(resp.getBody());
        assertThat(body.get("results").get(0).get("success").asBoolean()).isFalse();
        assertThat(body.get("results").get(1).get("success").asBoolean()).isTrue();
    }

    @Test
    void bulkCreateRejectsOver50Items() {
        List<BulkCreateItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) items.add(new BulkCreateItem("https://example.com", null, null));
        ResponseEntity<String> resp = rest.postForEntity(url("/api/urls/bulk"), new BulkCreateRequest(items), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(422);
    }
}
