"""Fixed-window per-client rate limiter (in-memory). Adequate for a single-process
prototype; a multi-instance deployment would move this state to Redis."""
import time
from threading import Lock


class RateLimiter:
    def __init__(self, max_requests: int, window_seconds: int):
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self._buckets: dict[str, tuple[int, float]] = {}
        self._lock = Lock()

    def allow(self, client_key: str) -> tuple[bool, int]:
        """Returns (allowed, retry_after_seconds)."""
        now = time.time()
        with self._lock:
            count, window_start = self._buckets.get(client_key, (0, now))
            if now - window_start >= self.window_seconds:
                count, window_start = 0, now
            if count >= self.max_requests:
                retry_after = int(self.window_seconds - (now - window_start)) + 1
                return False, retry_after
            self._buckets[client_key] = (count + 1, window_start)
            return True, 0

    def reset(self) -> None:
        with self._lock:
            self._buckets.clear()
