"""Tests for the place endpoints (CONTRACT §4): upload, list, download. Run from backend/."""
import json
import sys
from pathlib import Path

import pytest

pytest.importorskip("fastapi", reason="server tests need requirements-server.txt")
from cryptography.hazmat.primitives import serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from server.config import ConfigError, Settings  # noqa: E402
from server.main import create_app  # noqa: E402
from server.places import MAX_UPLOAD_BYTES, MemoryPlaceStore, StorageUnavailable  # noqa: E402
from test_places import ROOT_ID, make_package  # noqa: E402

SECRET_URL = "postgresql://u:SECRETPW@db.example.invalid/db"


def raw_public(key: Ed25519PrivateKey) -> bytes:
    return key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)


def settings_for(tmp_path, root=None, **kw) -> Settings:
    return Settings(
        registry_file=tmp_path / "missing" / "registry.json",
        asn_db=tmp_path / "missing" / "ip2asn.tsv.gz",
        root_public_keys=((ROOT_ID, raw_public(root)),) if root else (),
        **kw,
    )


@pytest.fixture()
def root():
    return Ed25519PrivateKey.generate()


def client(settings, store=None) -> TestClient:
    return TestClient(create_app(settings, place_store=store or MemoryPlaceStore()))


def post(c, place, data: bytes):
    return c.post(f"/v1/places/{place}/registry", content=data, headers={"Content-Type": "application/json"})


def test_upload_then_download(tmp_path, root):
    package, _ = make_package(root=root)
    with client(settings_for(tmp_path, root)) as c:
        r = post(c, "testplace", json.dumps(package).encode())
        assert r.status_code == 200
        assert r.json() == {"result": "STORED", "place_id": "testplace", "version": 2}
        assert r.headers["cache-control"] == "no-store"
        places = c.get("/v1/places").json()["places"]
        assert [(p["place_id"], p["place_name"], p["version"]) for p in places] == [("testplace", "Test Place", 2)]
        got = c.get("/v1/places/testplace/registry")
        assert got.status_code == 200 and got.json() == package
        assert c.get("/v1/health").json()["places_storage"] == "memory"


def test_upload_same_version_is_409(tmp_path, root):
    package, _ = make_package(root=root)
    with client(settings_for(tmp_path, root)) as c:
        assert post(c, "testplace", json.dumps(package).encode()).status_code == 200
        r = post(c, "testplace", json.dumps(package).encode())
    assert (r.status_code, r.json()) == (409, {"result": "STALE_VERSION"})


def test_upload_wrong_path_is_403(tmp_path, root):
    package, _ = make_package(root=root)
    with client(settings_for(tmp_path, root)) as c:
        r = post(c, "otherplace", json.dumps(package).encode())
    assert (r.status_code, r.json()) == (403, {"result": "WRONG_PLACE"})


def test_upload_too_large_is_413(tmp_path, root):
    store = MemoryPlaceStore()
    with client(settings_for(tmp_path, root), store) as c:
        r = post(c, "testplace", b"x" * (MAX_UPLOAD_BYTES + 1))
    assert (r.status_code, r.json()) == (413, {"result": "TOO_LARGE"})
    assert store.places() == []


def test_upload_too_large_chunked_is_413(tmp_path, root):
    """No Content-Length (chunked): the server stops reading once the limit is passed."""
    store = MemoryPlaceStore()

    def chunks():
        for _ in range(MAX_UPLOAD_BYTES // 1024 + 2):
            yield b"x" * 1024

    with client(settings_for(tmp_path, root), store) as c:
        r = c.post("/v1/places/testplace/registry", content=chunks())
    assert (r.status_code, r.json()) == (413, {"result": "TOO_LARGE"})
    assert store.places() == []


def test_upload_junk_is_400(tmp_path, root):
    with client(settings_for(tmp_path, root)) as c:
        for junk in (b"nope", b"[]"):
            r = post(c, "testplace", junk)
            assert (r.status_code, r.json()) == (400, {"result": "MALFORMED"})


def test_uploads_off_without_root_keys(tmp_path, root):
    package, _ = make_package(root=root)
    with client(settings_for(tmp_path)) as c:
        r = post(c, "testplace", json.dumps(package).encode())
        assert (r.status_code, r.json()) == (503, {"result": "UPLOADS_OFF"})
        listed = c.get("/v1/places")
        assert (listed.status_code, listed.json()) == (200, {"places": []})


def test_download_unknown_place_is_404(tmp_path, root):
    with client(settings_for(tmp_path, root)) as c:
        r = c.get("/v1/places/nowhere/registry")
    assert (r.status_code, r.json()) == (404, {"result": "NOT_FOUND"})


class DownStore:
    kind = "postgres"

    def put(self, record):
        raise StorageUnavailable("database unreachable")

    def places(self):
        raise StorageUnavailable("database unreachable")

    def get(self, place_id):
        raise StorageUnavailable("database unreachable")


def test_storage_down_is_503_without_secrets(tmp_path, root):
    settings = settings_for(tmp_path, root, database_url=SECRET_URL)
    package, _ = make_package(root=root)
    with client(settings, DownStore()) as c:
        answers = [post(c, "testplace", json.dumps(package).encode()), c.get("/v1/places"),
                   c.get("/v1/places/testplace/registry")]
        health = c.get("/v1/health")
    for r in answers:
        assert (r.status_code, r.json()) == (503, {"result": "STORAGE_UNAVAILABLE"})
        assert "SECRETPW" not in r.text
    assert health.status_code == 200 and "SECRETPW" not in health.text
    assert "SECRETPW" not in repr(settings)


def test_root_keys_env(root):
    import base64
    b64 = base64.urlsafe_b64encode(raw_public(root)).rstrip(b"=").decode()
    s = Settings.from_env({"WAVE_ROOT_PUBLIC_KEYS": f"wave-root-test:{b64}", "DATABASE_URL": SECRET_URL})
    assert s.root_public_keys == ((ROOT_ID, raw_public(root)),)
    assert s.database_url == SECRET_URL
    for bad in ("bad id!:" + b64, "wave-root-test:AAAA", "no-colon-here"):
        with pytest.raises(ConfigError):
            Settings.from_env({"WAVE_ROOT_PUBLIC_KEYS": bad})
