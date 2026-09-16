"""Thin SQLite persistence layer (stdlib only -- no ORM dependency risk).

A single shared connection per DB path is cached at module scope (rather than
per-thread) so that request handlers running on different worker threads all
see the same data -- this matters in particular for ':memory:' databases,
where per-thread connections would each get an isolated, empty database.
Write access is serialized with a lock; SQLite itself serializes at the file
level, so this trades a little write concurrency for correctness simplicity,
which is the right call for this prototype's scale.
"""
import sqlite3
import threading
from contextlib import contextmanager

from app import config

_connections: dict[str, sqlite3.Connection] = {}
_write_lock = threading.Lock()

SCHEMA = """
CREATE TABLE IF NOT EXISTS links (
    code TEXT PRIMARY KEY,
    target_url TEXT NOT NULL,
    created_at TEXT NOT NULL,
    expires_at TEXT,
    is_custom_alias INTEGER NOT NULL DEFAULT 0,
    click_count INTEGER NOT NULL DEFAULT 0,
    is_active INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS clicks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL,
    clicked_at TEXT NOT NULL,
    referrer TEXT,
    user_agent TEXT,
    FOREIGN KEY (code) REFERENCES links(code)
);

CREATE INDEX IF NOT EXISTS idx_clicks_code ON clicks(code);
"""


def get_connection(db_path: str | None = None) -> sqlite3.Connection:
    path = db_path or config.DB_PATH
    conn = _connections.get(path)
    if conn is None:
        with _write_lock:
            conn = _connections.get(path)
            if conn is None:
                conn = sqlite3.connect(path, check_same_thread=False)
                conn.row_factory = sqlite3.Row
                conn.execute("PRAGMA foreign_keys = ON")
                _connections[path] = conn
    return conn


def init_db(db_path: str | None = None) -> None:
    conn = get_connection(db_path)
    conn.executescript(SCHEMA)
    conn.commit()


@contextmanager
def transaction(db_path: str | None = None):
    conn = get_connection(db_path)
    with _write_lock:
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise


def reset_db(db_path: str | None = None) -> None:
    """Danger: drops and recreates all tables. Used only by tests/demo scenarios."""
    conn = get_connection(db_path)
    with _write_lock:
        conn.executescript("DROP TABLE IF EXISTS clicks; DROP TABLE IF EXISTS links;")
        conn.commit()
    init_db(db_path)
