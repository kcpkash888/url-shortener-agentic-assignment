package com.example.urlshortener.app;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory TTL + LRU cache used to absorb read traffic on the hot redirect
 * path. A short TTL is intentional: it bounds staleness after a link is
 * deactivated or its target is updated, while still removing most repeat-read
 * load from the database.
 */
public class TtlCache {

    private record Entry(String value, Instant expiresAt) {}

    private final int maxSize;
    private final Duration ttl;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<String, Entry> store;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public TtlCache(int maxSize, Duration ttl) {
        this.maxSize = maxSize;
        this.ttl = ttl;
        this.store = new LinkedHashMap<>(16, 0.75f, true);
    }

    public String get(String key) {
        lock.lock();
        try {
            Entry entry = store.get(key);
            if (entry == null) {
                misses.incrementAndGet();
                return null;
            }
            if (entry.expiresAt().isBefore(Instant.now())) {
                store.remove(key);
                misses.incrementAndGet();
                return null;
            }
            hits.incrementAndGet();
            return entry.value();
        } finally {
            lock.unlock();
        }
    }

    public void set(String key, String value) {
        lock.lock();
        try {
            store.put(key, new Entry(value, Instant.now().plus(ttl)));
            while (store.size() > maxSize) {
                var it = store.entrySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void invalidate(String key) {
        lock.lock();
        try {
            store.remove(key);
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            store.clear();
            hits.set(0);
            misses.set(0);
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Object> stats() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        double hitRate = total == 0 ? 0.0 : (double) h / total;
        lock.lock();
        try {
            return Map.of("hits", h, "misses", m, "hit_rate", hitRate, "size", store.size());
        } finally {
            lock.unlock();
        }
    }
}
