"""Tests for the WAVE FastAPI server (backend/server). Run from backend/: python -m pytest -q"""
import base64
import json
import os
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND / "signing"))
import wave_sign as ws  # noqa: E402
from server.config import ConfigError, Settings  # noqa: E402
from server.main import create_app  # noqa: E402

KEY_ID = "wave-srv-test"


def b64d(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


@pytest.fixture()
def keypair(tmp_path):
    return ws.keygen(KEY_ID, tmp_path / "keys", passphrase=None)


@pytest.fixture()
def signed(tmp_path, keypair):
    """A real registry.json (from backend/registry) re-keyed and signed with a throwaway key."""
    priv, _ = keypair
    text = (BACKEND / "registry" / "registry.json").read_text(encoding="utf-8")
    reg = tmp_path / "reg" / "registry.json"
    reg.parent.mkdir()
    reg.write_bytes(text.replace('"key_id": "wave-2026-a"', f'"key_id": "{KEY_ID}"').encode("utf-8"))
    ws.sign_registry(reg, priv)
    return reg


def client(settings: Settings, peer=("203.0.113.7", 50000)) -> TestClient:
    return TestClient(create_app(settings), client=peer)


def settings_for(tmp_path, registry=None, **kw) -> Settings:
    return Settings(
        registry_file=registry or (tmp_path / "missing" / "registry.json"),
        asn_db=kw.pop("asn_db", tmp_path / "missing" / "ip2asn.tsv.gz"),
        **kw,
    )


# ---------------- /v1/health ----------------
def test_health_without_registry_or_asn_data(tmp_path):
    with client(settings_for(tmp_path)) as c:
        r = c.get("/v1/health")
    assert r.status_code == 200
    assert r.json() == {
        "status": "ok",
        "registry_loaded": False,
        "registry_version": None,
        "asn_db_loaded": False,
        "asn_db_date": None,
    }


def test_health_with_registry(tmp_path, signed):
    with client(settings_for(tmp_path, signed)) as c:
        body = c.get("/v1/health").json()
    assert body["registry_loaded"] is True
    assert body["registry_version"] == json.loads(signed.read_bytes())["version"]


# ---------------- /v1/registry ----------------
def test_registry_serves_the_exact_file_bytes_and_they_verify(tmp_path, signed, keypair):
    _, pub = keypair
    with client(settings_for(tmp_path, signed)) as c:
        r = c.get("/v1/registry")
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"key_id", "registry_b64url", "signature_b64url"}
    assert body["key_id"] == KEY_ID
    assert "=" not in body["registry_b64url"] + body["signature_b64url"]  # base64url without padding

    served = b64d(body["registry_b64url"])
    assert served == signed.read_bytes()  # byte for byte, never re-serialized
    sig_doc = {"key_id": body["key_id"], "alg": "Ed25519", "signature": body["signature_b64url"]}
    ok, msg = ws.verify_registry_bytes(served, sig_doc, ws.load_public_keys([pub]))
    assert ok, msg


def test_registry_whitespace_and_key_order_are_preserved(tmp_path, keypair):
    priv, _ = keypair
    odd = (BACKEND / "registry" / "registry.json").read_text(encoding="utf-8")
    odd = odd.replace('"key_id": "wave-2026-a"', f'"key_id": "{KEY_ID}"').replace("\n", "\r\n") + "\r\n"
    reg = tmp_path / "registry.json"
    reg.write_bytes(odd.encode("utf-8"))
    ws.sign_registry(reg, priv)
    with client(settings_for(tmp_path, reg)) as c:
        assert b64d(c.get("/v1/registry").json()["registry_b64url"]) == odd.encode("utf-8")


def test_registry_503_when_signature_missing(tmp_path, signed):
    signed.with_name("registry.json.sig").unlink()
    with client(settings_for(tmp_path, signed)) as c:
        r = c.get("/v1/registry")
        health = c.get("/v1/health").json()
    assert r.status_code == 503
    assert "signature file not found" in r.json()["detail"]
    assert health["registry_loaded"] is False


def test_registry_503_when_registry_missing(tmp_path):
    with client(settings_for(tmp_path)) as c:
        r = c.get("/v1/registry")
    assert r.status_code == 503
    assert "registry file not found" in r.json()["detail"]


@pytest.mark.parametrize(
    "sig_text, reason",
    [
        ("not json", "not valid JSON"),
        ('{"key_id": "k", "alg": "RSA", "signature": "AA"}', "unsupported alg"),
        ('{"key_id": "k", "alg": "Ed25519", "signature": "AAAA"}', "must be 64 bytes"),
    ],
)
def test_registry_503_when_signature_file_is_broken(tmp_path, signed, sig_text, reason):
    signed.with_name("registry.json.sig").write_text(sig_text, encoding="utf-8")
    with client(settings_for(tmp_path, signed)) as c:
        r = c.get("/v1/registry")
    assert r.status_code == 503
    assert reason in r.json()["detail"]


def test_re_signed_registry_is_picked_up_without_restart(tmp_path, signed, keypair):
    priv, pub = keypair
    with client(settings_for(tmp_path, signed)) as c:
        first = b64d(c.get("/v1/registry").json()["registry_b64url"])
        assert json.loads(first)["version"] == 1

        # Edit + re-sign on disk while the server keeps running.
        signed.write_bytes(signed.read_bytes().replace(b'"version": 1', b'"version": 2'))
        ws.sign_registry(signed, priv)
        later = os.stat(signed).st_mtime_ns + 1_000_000_000  # make sure the mtime visibly moves on any filesystem
        os.utime(signed, ns=(later, later))

        body = c.get("/v1/registry").json()
        assert json.loads(b64d(body["registry_b64url"]))["version"] == 2
        assert c.get("/v1/health").json()["registry_version"] == 2
        sig_doc = {"key_id": body["key_id"], "alg": "Ed25519", "signature": body["signature_b64url"]}
        ok, msg = ws.verify_registry_bytes(b64d(body["registry_b64url"]), sig_doc, ws.load_public_keys([pub]))
        assert ok, msg


def test_server_holds_no_private_key_material():
    for path in (BACKEND / "server").rglob("*.py"):
        text = path.read_text(encoding="utf-8")
        assert "PRIVATE KEY" not in text and "private.pem" not in text, path


# ---------------- ASN dataset ----------------
FIXTURE_ROWS = [
    "1.0.0.0\t1.0.0.255\t13335\tUS\tCLOUDFLARENET - Cloudflare, Inc.",
    "1.0.1.0\t1.0.3.255\t0\tNone\tNot routed",
    "203.177.0.0\t203.177.255.255\t4775\tPH\tGLOBE-TELECOM-AS - Globe Telecoms",
    "203.178.0.0\t203.178.0.255\t9299\tPH\tIPG-AS-AP - Philippine Long Distance Telephone Company",
    "131.226.96.0\t131.226.127.255\t139831\tPH\tDTC-AS-AP - DITO TELECOMMUNITY CORP.",
    "this line is broken",
    "2001:4860::\t2001:4860:ffff:ffff:ffff:ffff:ffff:ffff\t15169\tUS\tGOOGLE - Google LLC",
    "2001:db8::\t2001:db8::ffff\t0\tNone\tNot routed",
]


@pytest.fixture()
def asn_file(tmp_path):
    import gzip

    path = tmp_path / "ip2asn-combined.tsv.gz"
    with gzip.open(path, "wt", encoding="utf-8") as f:
        f.write("\n".join(FIXTURE_ROWS) + "\n")
    return path


def test_asn_lookup_ipv4_ipv6_boundaries_and_gaps(asn_file):
    import ipaddress

    from server.asn_db import AsnDatabase

    db = AsnDatabase.load(asn_file)
    assert db.ranges == 5  # 2 "Not routed" rows and 1 broken row skipped

    def asn(ip):
        found = db.lookup(ipaddress.ip_address(ip))
        return found.asn if found else None

    assert asn("203.177.0.0") == 4775          # first address of a range
    assert asn("203.177.255.255") == 4775      # last address of a range
    assert asn("203.178.0.0") == 9299          # the very next address is the next range
    assert asn("203.176.255.255") is None      # just before the range: a gap
    assert asn("100.0.0.1") is None            # nowhere near any range
    assert asn("1.0.2.7") is None              # "Not routed" (ASN 0) never answers
    assert asn("131.226.100.1") == 139831
    assert asn("2001:4860:4860::8888") == 15169
    assert asn("2001:db8::1") is None          # IPv6 "Not routed"
    assert db.lookup(ipaddress.ip_address("1.0.0.1")).as_name == "CLOUDFLARENET - Cloudflare, Inc."


def test_health_reports_the_asn_dataset(tmp_path, asn_file):
    with client(settings_for(tmp_path, asn_db=asn_file)) as c:
        body = c.get("/v1/health").json()
    assert body["asn_db_loaded"] is True
    assert len(body["asn_db_date"]) == 10  # YYYY-MM-DD


def test_server_starts_with_a_corrupt_asn_file(tmp_path):
    bad = tmp_path / "bad.tsv.gz"
    bad.write_bytes(b"not gzip at all")
    with client(settings_for(tmp_path, asn_db=bad)) as c:
        assert c.get("/v1/health").json()["asn_db_loaded"] is False


# ---------------- config ----------------
def test_invalid_proxy_settings_refuse_to_start():
    with pytest.raises(ConfigError):
        Settings.from_env({"WAVE_TRUSTED_PROXY": "trust-everyone"})
    with pytest.raises(ConfigError):
        Settings.from_env({"WAVE_TRUSTED_PROXY": "forwarded"})  # no proxy IPs given
    with pytest.raises(ConfigError):
        Settings.from_env({"WAVE_TRUSTED_PROXY": "forwarded", "WAVE_TRUSTED_PROXY_IPS": "not-an-ip"})
    s = Settings.from_env({"WAVE_TRUSTED_PROXY": "Forwarded", "WAVE_TRUSTED_PROXY_IPS": "10.0.0.2, ::1"})
    assert s.trusted_proxy == "forwarded" and s.trusted_proxy_ips == {"10.0.0.2", "::1"}


def test_relative_paths_are_taken_from_backend_dir():
    s = Settings.from_env({"WAVE_REGISTRY_FILE": "registry/registry.home-dev.json"})
    assert s.registry_file == (BACKEND / "registry" / "registry.home-dev.json").resolve()
    assert s.signature_file.name == "registry.home-dev.json.sig"
