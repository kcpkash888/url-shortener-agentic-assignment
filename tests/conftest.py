import os

os.environ["URLSHORT_DB_PATH"] = ":memory:"

import pytest
from fastapi.testclient import TestClient

from app import config, db, shortener
from app.main import app, limiter


@pytest.fixture(autouse=True)
def _clean_state():
    db.init_db()
    db.reset_db()
    shortener.redirect_cache.clear()
    limiter.reset()
    yield


@pytest.fixture
def client():
    return TestClient(app)
