"""Tests for the place upload check and the in-memory place store (server/places.py). Run from backend/."""
import datetime as dt
import json
import sys
import uuid
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND / "signing"))
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey  # noqa: E402

import wave_sign as ws  # noqa: E402
from server.places import (  # noqa: E402
    MAX_VERSION, MemoryPlaceStore, PlaceRecord, check_upload,
)

ROOT_ID = "wave-root-test"


def _now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0)


def make_package(place="testplace", version=2, cert_days=365, cert_start=None, issued=None, expires_days=180,
                 root=None):
    """A package signed by a fresh test admin, certified by a test root. Returns (package, root_keys)."""
    root = root or Ed25519PrivateKey.generate()
    admin = ec.generate_private_key(ec.SECP256R1())
    req = ws.parse_admin_request(ws.make_admin_request("Test Admin", place, admin.public_key()))
    cert_start = cert_start or (_now() - dt.timedelta(hours=1))  # the cert must already be valid when the list is issued
    cert = ws.certify_admin(req, place, "Test Place", cert_days, ROOT_ID, root, now=cert_start)
    cert_id = json.loads(ws.b64u_decode(cert[len(ws.CERT_PREFIX):].split(".")[0]))["cert_id"]
    issued = issued or (_now() - dt.timedelta(minutes=1))
    reg = {
        "schema": "wave-registry/1", "version": version, "key_id": cert_id, "place_id": place,
        "issued_at": ws._fmt(issued), "expires_at": ws._fmt(issued + dt.timedelta(days=expires_days)),
        "campus_asns": [{"asn": 64500, "name": "Example campus ISP (documentation ASN)"}],
        "networks": [{"ssid": "TEST-PLACE-WIFI", "security": "WPA2-PSK", "aps": [
            {"bssid": "00:00:5e:00:53:10", "band": "5GHz", "channel": 36, "building": "Main",
             "rssi_min": -85, "rssi_max": -30}]}],
        "blocked": [],
    }
    package = ws.sign_place_registry(json.dumps(reg, indent=2).encode("utf-8"), admin, cert)
    return package, {ROOT_ID: root.public_key()}


def body(package) -> bytes:
    return json.dumps(package).encode("utf-8")


def test_check_upload_valid():
    package, keys = make_package()
    result, rec = check_upload("testplace", body(package), keys, _now())
    assert result == ws.VALID
    cert = json.loads(ws.b64u_decode(package["cert"][len(ws.CERT_PREFIX):].split(".")[0]))
    assert (rec.place_id, rec.place_name, rec.version) == ("testplace", "Test Place", 2)
    assert (rec.cert_id, rec.admin_name) == (cert["cert_id"], "Test Admin")
    assert rec.package == package


def test_check_upload_path_must_match_package():
    package, keys = make_package()
    assert check_upload("otherplace", body(package), keys, _now()) == (ws.WRONG_PLACE, None)


def test_check_upload_cert_expired_now():
    now = _now()
    package, keys = make_package(cert_start=now - dt.timedelta(days=10), cert_days=5,
                                 issued=now - dt.timedelta(days=9), expires_days=1)
    assert check_upload("testplace", body(package), keys, now) == (ws.EXPIRED, None)


def test_check_upload_unknown_root():
    package, _ = make_package()
    assert check_upload("testplace", body(package), {}, _now()) == (ws.UNKNOWN_KEY, None)


def test_check_upload_junk_is_malformed():
    _, keys = make_package()
    for junk in (b"not json", b"[]", b"{}", b'{"place_id": 1}', b"\xff\xfe", b"[" * 100000):
        assert check_upload("testplace", junk, keys, _now()) == (ws.MALFORMED, None), junk[:20]
    root = Ed25519PrivateKey.generate()
    huge, keys = make_package(version=MAX_VERSION + 1, root=root)
    assert check_upload("testplace", body(huge), keys, _now()) == (ws.MALFORMED, None)


def test_check_upload_drops_extra_fields():
    package, keys = make_package()
    result, rec = check_upload("testplace", body(dict(package, x="y")), keys, _now())
    assert result == ws.VALID
    assert rec.package == package


def _record(place: str, version: int) -> PlaceRecord:
    return PlaceRecord(place_id=place, place_name="Test Place", version=version, cert_id="0123456789abcdef",
                       admin_name="Test Admin",
                       package={"place_id": place, "registry_b64url": f"r{version}", "signature_b64url": "s",
                                "cert": "c"},
                       updated_at="2026-11-24T00:00:00Z")


def check_store_contract(store) -> list[str]:
    """The behaviour every PlaceStore must have. Returns the place ids it made (for cleanup)."""
    tag = uuid.uuid4().hex[:8]
    a, b = f"t{tag}-b", f"t{tag}-a"
    assert store.put(_record(a, 2)) is True
    assert store.get(a) == _record(a, 2)
    assert [p.place_id for p in store.places() if p.place_id == a] == [a]
    assert store.put(_record(a, 2)) is False
    assert store.put(_record(a, 1)) is False
    assert store.get(a).version == 2
    assert store.put(_record(a, 3)) is True
    assert store.get(a) == _record(a, 3)
    assert store.put(_record(b, 1)) is True
    mine = [p.place_id for p in store.places() if p.place_id in (a, b)]
    assert mine == [b, a]
    assert store.get(f"t{tag}-nope") is None
    return [a, b]


def test_memory_store_versions():
    store = MemoryPlaceStore()
    assert store.kind == "memory"
    check_store_contract(store)


def test_record_summary():
    assert _record("x", 4).summary() == {"place_id": "x", "place_name": "Test Place", "version": 4,
                                         "updated_at": "2026-11-24T00:00:00Z"}
