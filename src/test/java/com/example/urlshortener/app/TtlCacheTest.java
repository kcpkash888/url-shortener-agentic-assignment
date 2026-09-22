package com.example.urlshortener.app;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TtlCacheTest {

    @Test
    void getSetRoundtrip() {
        TtlCache cache = new TtlCache(10, Duration.ofSeconds(60));
        cache.set("a", "value");
        assertThat(cache.get("a")).isEqualTo("value");
    }

    @Test
    void expiredEntryIsEvictedOnRead() throws InterruptedException {
        TtlCache cache = new TtlCache(10, Duration.ofMillis(1));
        cache.set("a", "value");
        Thread.sleep(20);
        assertThat(cache.get("a")).isNull();
    }

    @Test
    void lruEvictionWhenOverCapacity() {
        TtlCache cache = new TtlCache(2, Duration.ofSeconds(60));
        cache.set("a", "1");
        cache.set("b", "2");
        cache.set("c", "3"); // evicts "a"
        assertThat(cache.get("a")).isNull();
        assertThat(cache.get("b")).isEqualTo("2");
        assertThat(cache.get("c")).isEqualTo("3");
    }

    @Test
    void statsTrackHitsAndMisses() {
        TtlCache cache = new TtlCache(10, Duration.ofSeconds(60));
        cache.set("a", "1");
        cache.get("a");
        cache.get("missing");
        var stats = cache.stats();
        assertThat(stats.get("hits")).isEqualTo(1L);
        assertThat(stats.get("misses")).isEqualTo(1L);
    }
}
