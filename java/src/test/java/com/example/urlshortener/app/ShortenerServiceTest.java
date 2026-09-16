package com.example.urlshortener.app;

import com.example.urlshortener.app.exceptions.AliasConflictException;
import com.example.urlshortener.app.exceptions.LinkExpiredException;
import com.example.urlshortener.app.exceptions.LinkInactiveException;
import com.example.urlshortener.app.exceptions.LinkNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ShortenerServiceTest {

    @Autowired
    private ShortenerService shortener;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDb() {
        jdbc.execute("DELETE FROM clicks");
        jdbc.execute("DELETE FROM links");
        shortener.redirectCache.clear();
    }

    @Test
    void createAndResolveRoundtrip() {
        Link link = shortener.createLink("https://example.com/some/long/path", null, null);
        assertThat(link.code()).hasSize(7);
        String target = shortener.resolveLink(link.code(), null, null);
        assertThat(target).isEqualTo("https://example.com/some/long/path");
    }

    @Test
    void clickCountIncrements() {
        Link link = shortener.createLink("https://example.com", null, null);
        shortener.resolveLink(link.code(), null, null);
        shortener.resolveLink(link.code(), null, null);
        Link refreshed = shortener.getLink(link.code(), true);
        assertThat(refreshed.clickCount()).isEqualTo(2);
    }

    @Test
    void customAliasUsedVerbatim() {
        Link link = shortener.createLink("https://example.com", "my-brand", null);
        assertThat(link.code()).isEqualTo("my-brand");
        assertThat(link.isCustomAlias()).isTrue();
    }

    @Test
    void customAliasConflictRaises() {
        shortener.createLink("https://example.com", "dup", null);
        assertThatThrownBy(() -> shortener.createLink("https://other.com", "dup", null))
                .isInstanceOf(AliasConflictException.class);
    }

    @Test
    void resolveMissingCodeRaisesNotFound() {
        assertThatThrownBy(() -> shortener.resolveLink("doesnotexist", null, null))
                .isInstanceOf(LinkNotFoundException.class);
    }

    @Test
    void expiredLinkRaisesOnResolve() {
        Link link = shortener.createLink("https://example.com", null, 1);
        jdbc.update("UPDATE links SET expires_at = '2000-01-01T00:00:00Z' WHERE code = ?", link.code());
        shortener.redirectCache.invalidate(link.code());
        assertThatThrownBy(() -> shortener.resolveLink(link.code(), null, null))
                .isInstanceOf(LinkExpiredException.class);
    }

    @Test
    void deactivatedLinkIsRejectedOnResolve() {
        Link link = shortener.createLink("https://example.com", null, null);
        shortener.deactivateLink(link.code());
        assertThatThrownBy(() -> shortener.resolveLink(link.code(), null, null))
                .isInstanceOf(LinkInactiveException.class);
    }

    @Test
    void deactivateMissingCodeRaisesNotFound() {
        assertThatThrownBy(() -> shortener.deactivateLink("nope"))
                .isInstanceOf(LinkNotFoundException.class);
    }

    @Test
    void analyticsReportsRecentClicks() {
        Link link = shortener.createLink("https://example.com", null, null);
        shortener.resolveLink(link.code(), "https://google.com", "test-agent");
        var analytics = shortener.getAnalytics(link.code(), 20);
        assertThat(analytics.totalClicks()).isEqualTo(1);
        assertThat(analytics.recentClicks().get(0).referrer()).isEqualTo("https://google.com");
    }

    @Test
    void cacheIsUsedOnRepeatResolve() {
        Link link = shortener.createLink("https://example.com", null, null);
        shortener.resolveLink(link.code(), null, null);
        long hitsBefore = (long) shortener.redirectCache.stats().get("hits");
        shortener.resolveLink(link.code(), null, null);
        long hitsAfter = (long) shortener.redirectCache.stats().get("hits");
        assertThat(hitsAfter).isEqualTo(hitsBefore + 1);
    }
}
