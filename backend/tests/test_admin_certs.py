"""Place admins: enrolment requests, root-signed certificates and place registry packages (CONTRACT §6-7)."""
import base64
import datetime as dt
import json
import sys
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "signing"))
import wave_sign as ws  # noqa: E402

UTC = dt.timezone.utc
NOW = dt.datetime(2026, 11, 24, 8, 0, 0, tzinfo=UTC)


@pytest.fixture
def root():
    key = Ed25519PrivateKey.generate()
    return "wave-root-test", key, {"wave-root-test": key.public_key()}


@pytest.fixture
def admin_key():
    return ec.generate_private_key(ec.SECP256R1())


def _request(admin_key, place="batstateu", name="Ana Cruz"):
    return ws.make_admin_request(name, place, admin_key.public_key())


def _cert(root, admin_key, place="batstateu", days=365, now=NOW):
    root_id, root_key, _ = root
    req = ws.parse_admin_request(_request(admin_key, place))
    return ws.certify_admin(req, place, "Batangas State University", days, root_id, root_key, now=now)


# ---------------- Task 1: requests and certificates ----------------
def test_request_roundtrip(admin_key):
    req = ws.parse_admin_request(_request(admin_key))
    assert req["admin_name"] == "Ana Cruz"
    assert req["place_id"] == "batstateu"
    assert req["admin_key_alg"] == "ES256"
    assert req["admin_public_key"] == ws.admin_public_key_b64(admin_key.public_key())


def test_request_rejects_bad_key(admin_key):
    text = _request(admin_key)
    body = json.loads(base64.urlsafe_b64decode(text.split(":", 2)[2] + "=="))
    p384 = ec.generate_private_key(ec.SECP384R1()).public_key()
    p384_der = p384.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    for bad in (ws.b64u_encode(b"garbage"), ws.b64u_encode(p384_der)):
        body["admin_public_key"] = bad
        forged = "WAVEREQ:1:" + ws.b64u_encode(json.dumps(body).encode())
        with pytest.raises(ValueError):
            ws.parse_admin_request(forged)
    with pytest.raises((ValueError, TypeError)):
        ws.make_admin_request("Ana", "batstateu", Ed25519PrivateKey.generate().public_key())


def test_request_rejects_bad_place_id(admin_key):
    for place in ("BatStateU", "", "x" * 40, "bad place"):
        with pytest.raises(ValueError):
            ws.make_admin_request("Ana", place, admin_key.public_key())


def test_certify_and_verify(root, admin_key):
    status, cert = ws.verify_cert(_cert(root, admin_key), root[2], at=NOW)
    assert status == ws.VALID
    assert cert["place_id"] == "batstateu"
    assert cert["admin_name"] == "Ana Cruz"
    assert cert["root_key_id"] == "wave-root-test"
    assert len(cert["cert_id"]) == 16
    assert cert["expires_at"] == "2027-11-24T08:00:00Z"


def test_certify_refuses_other_place(root, admin_key):
    root_id, root_key, _ = root
    req = ws.parse_admin_request(_request(admin_key, place="batstateu"))
    with pytest.raises(ValueError, match="batstateu"):
        ws.certify_admin(req, "smmall", "SM Mall", 365, root_id, root_key, now=NOW)


def test_cert_tamper_detected(root, admin_key):
    text = _cert(root, admin_key)
    body, sig = text[len("WAVECERT:1:"):].split(".")
    cert = json.loads(base64.urlsafe_b64decode(body + "=="))
    for field, value in (("place_id", "smmall"), ("expires_at", "2099-01-01T00:00:00Z")):
        changed = dict(cert, **{field: value})
        forged = "WAVECERT:1:" + ws.b64u_encode(json.dumps(changed, separators=(",", ":"), sort_keys=True).encode()) + "." + sig
        assert ws.verify_cert(forged, root[2], at=NOW)[0] == ws.BAD_SIGNATURE


def test_cert_unknown_root(root, admin_key):
    other = {"someone-else": Ed25519PrivateKey.generate().public_key()}
    assert ws.verify_cert(_cert(root, admin_key), other, at=NOW)[0] == ws.UNKNOWN_KEY


def test_cert_expired(root, admin_key):
    assert ws.verify_cert(_cert(root, admin_key, days=1), root[2], at=NOW + dt.timedelta(days=2))[0] == ws.EXPIRED


def test_cert_not_yet_valid(root, admin_key):
    assert ws.verify_cert(_cert(root, admin_key), root[2], at=NOW - dt.timedelta(minutes=1))[0] == ws.NOT_YET_VALID


def test_cert_malformed(root, admin_key):
    good = _cert(root, admin_key)
    body, sig = good[len("WAVECERT:1:"):].split(".")
    cert = json.loads(base64.urlsafe_b64decode(body + "=="))
    missing = dict(cert)
    del missing["admin_public_key"]
    cases = [
        good[len("WAVECERT:1:"):],                        # no prefix
        "WAVECERT:1:!!!." + sig,                          # bad base64
        "WAVECERT:1:" + body,                             # no signature
        "WAVECERT:1:" + ws.b64u_encode(json.dumps(missing, separators=(",", ":"), sort_keys=True).encode()) + "." + sig,
    ]
    for text in cases:
        assert ws.verify_cert(text, root[2], at=NOW)[0] == ws.MALFORMED, text[:40]

# ---------------- Task 2: place registry packages ----------------
def _cert_id(cert_text):
    body = cert_text[len("WAVECERT:1:"):].split(".")[0]
    return json.loads(base64.urlsafe_b64decode(body + "=="))["cert_id"]


def _place_registry(place_id, key_id, issued="2026-11-25T00:00:00Z", expires="2027-05-25T00:00:00Z"):
    reg = json.loads((ROOT / "registry" / "registry.json").read_text())
    reg.update({"place_id": place_id, "key_id": key_id, "issued_at": issued, "expires_at": expires, "version": 2})
    return json.dumps(reg, indent=2).encode()


def _package(root, admin_key, place="batstateu", registry_place=None, signer=None, cert_days=365, issued="2026-11-25T00:00:00Z"):
    cert = _cert(root, admin_key, place=place, days=cert_days)
    reg = _place_registry(registry_place or place, _cert_id(cert), issued=issued)
    return ws.sign_place_registry(reg, signer or admin_key, cert)


def test_package_valid(root, admin_key):
    status, reg = ws.verify_package(_package(root, admin_key), root[2])
    assert status == ws.VALID
    assert reg["place_id"] == "batstateu" and reg["version"] == 2


def test_package_wrong_place(root, admin_key):
    pkg = _package(root, admin_key, place="batstateu", registry_place="smmall")
    assert ws.verify_package(pkg, root[2])[0] == ws.WRONG_PLACE
    # The package's own place label must match too.
    good = _package(root, admin_key)
    assert ws.verify_package(dict(good, place_id="smmall"), root[2])[0] == ws.WRONG_PLACE


def test_package_signed_by_other_admin(root, admin_key):
    other = ec.generate_private_key(ec.SECP256R1())
    assert ws.verify_package(_package(root, admin_key, signer=other), root[2])[0] == ws.BAD_SIGNATURE


def test_package_tampered_registry(root, admin_key):
    pkg = _package(root, admin_key)
    reg = base64.urlsafe_b64decode(pkg["registry_b64url"] + "==").replace(b"BSU-ICT-TESTLAB", b"EVIL-NETWORK-01")
    assert ws.verify_package(dict(pkg, registry_b64url=ws.b64u_encode(reg)), root[2])[0] == ws.BAD_SIGNATURE


def test_package_cert_expired_before_issue(root, admin_key):
    pkg = _package(root, admin_key, cert_days=1, issued="2026-11-30T00:00:00Z")
    assert ws.verify_package(pkg, root[2])[0] == ws.EXPIRED


def test_package_issued_before_cert_is_not_yet_valid(root, admin_key):
    pkg = _package(root, admin_key, issued="2026-11-20T00:00:00Z")
    assert ws.verify_package(pkg, root[2])[0] == ws.NOT_YET_VALID


def test_package_registry_without_place_id_is_malformed(root, admin_key):
    cert = _cert(root, admin_key)
    reg = json.loads(_place_registry("batstateu", _cert_id(cert)))
    del reg["place_id"]
    raw = json.dumps(reg).encode()
    sig = admin_key.sign(ws.REGISTRY_DOMAIN + raw, ws.ec.ECDSA(ws.hashes.SHA256()))
    pkg = {"place_id": "batstateu", "registry_b64url": ws.b64u_encode(raw), "signature_b64url": ws.b64u_encode(sig), "cert": cert}
    assert ws.verify_package(pkg, root[2])[0] == ws.MALFORMED
    assert ws.verify_package({"place_id": "batstateu"}, root[2])[0] == ws.MALFORMED


def test_package_key_id_must_be_the_cert_id(root, admin_key):
    cert = _cert(root, admin_key)
    pkg = ws.sign_place_registry(_place_registry("batstateu", "someone-else"), admin_key, cert)
    assert ws.verify_package(pkg, root[2])[0] == ws.MALFORMED


def test_registry_place_id_optional_for_old_lists():
    _, errors, _ = ws.validate_registry((ROOT / "registry" / "registry.json").read_bytes())
    assert errors == []
    reg = json.loads((ROOT / "registry" / "registry.json").read_text())
    reg["place_id"] = "Bad Place"
    _, errors, _ = ws.validate_registry(json.dumps(reg).encode())
    assert any("place_id" in e for e in errors)

