import time

from app.cache import TTLCache


def test_get_set_roundtrip():
    cache = TTLCache(max_size=10, ttl_seconds=60)
    cache.set("a", "value")
    assert cache.get("a") == "value"


def test_expired_entry_is_evicted_on_read():
    cache = TTLCache(max_size=10, ttl_seconds=0)
    cache.set("a", "value")
    time.sleep(0.01)
    assert cache.get("a") is None


def test_lru_eviction_when_over_capacity():
    cache = TTLCache(max_size=2, ttl_seconds=60)
    cache.set("a", 1)
    cache.set("b", 2)
    cache.set("c", 3)  # evicts "a"
    assert cache.get("a") is None
    assert cache.get("b") == 2
    assert cache.get("c") == 3


def test_stats_track_hits_and_misses():
    cache = TTLCache(max_size=10, ttl_seconds=60)
    cache.set("a", 1)
    cache.get("a")
    cache.get("missing")
    stats = cache.stats()
    assert stats["hits"] == 1
    assert stats["misses"] == 1
