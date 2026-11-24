"""Tests for the Supabase Postgres place store (server/places_pg.py). Run from backend/.

The database tests run only when WAVE_TEST_DATABASE_URL points at a THROWAWAY database (a test Supabase
project or a local Postgres), never the real one: they write and delete rows in wave_places.
"""
import os
import sys
from pathlib import Path

import pytest

psycopg = pytest.importorskip("psycopg", reason="Postgres tests need psycopg (requirements-server.txt)")

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from server.places import StorageUnavailable  # noqa: E402
from server.places_pg import PostgresPlaceStore  # noqa: E402
from test_places import check_store_contract  # noqa: E402

TEST_URL = os.environ.get("WAVE_TEST_DATABASE_URL")
needs_db = pytest.mark.skipif(not TEST_URL, reason="set WAVE_TEST_DATABASE_URL to a throwaway database")


@needs_db
def test_pg_store_contract():
    store = PostgresPlaceStore(TEST_URL)
    assert store.kind == "postgres"
    made = check_store_contract(store)
    with psycopg.connect(TEST_URL, autocommit=True, prepare_threshold=None) as conn:
        conn.execute("delete from wave_places where place_id = any(%s)", (made,))


@needs_db
def test_pg_table_has_row_level_security():
    PostgresPlaceStore(TEST_URL).places()  # creates the table
    with psycopg.connect(TEST_URL, autocommit=True, prepare_threshold=None) as conn:
        row = conn.execute("select relrowsecurity from pg_class where relname = 'wave_places'").fetchone()
    assert row == (True,)


def test_pg_bad_url_is_storage_unavailable():
    store = PostgresPlaceStore("postgresql://u:SECRETPW@127.0.0.1:1/x?connect_timeout=2")
    with pytest.raises(StorageUnavailable) as info:
        store.places()
    assert "SECRETPW" not in str(info.value)
    assert "127.0.0.1" not in str(info.value)
