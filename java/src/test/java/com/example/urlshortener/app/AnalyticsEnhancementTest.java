package com.example.urlshortener.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AnalyticsEnhancementTest {

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
    void analyticsReportsTopReferrers() throws Exception {
        ResponseEntity<String> created = rest.postForEntity(
                url("/api/urls"), new com.example.urlshortener.app.dto.CreateLinkRequest("https://example.com", null, null), String.class);
        String code = mapper.readTree(created.getBody()).get("code").asText();

        rest.exchange(org.springframework.http.RequestEntity.get(URI.create(url("/" + code)))
                .header("referer", "https://google.com").build(), Void.class);
        rest.exchange(org.springframework.http.RequestEntity.get(URI.create(url("/" + code)))
                .header("referer", "https://google.com").build(), Void.class);
        rest.exchange(org.springframework.http.RequestEntity.get(URI.create(url("/" + code)))
                .header("referer", "https://bing.com").build(), Void.class);

        ResponseEntity<String> resp = rest.getForEntity(url("/api/urls/" + code + "/analytics"), String.class);
        JsonNode body = mapper.readTree(resp.getBody());
        assertThat(body.has("top_referrers")).isTrue();
        assertThat(body.get("top_referrers")).hasSize(2);
        assertThat(body.get("unique_referrer_count").asInt()).isGreaterThan(0);
    }
}
