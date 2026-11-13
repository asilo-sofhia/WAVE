"""Tests for the WAVE signing tool. Run: python -m pytest -q"""
import datetime as dt
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "signing"))
import wave_sign as ws  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture()
def keypair(tmp_path):
    priv, pub = ws.keygen("wave-2026-a", tmp_path / "keys", passphrase=None)
    return priv, pub


def _registry_copy(tmp_path, key_id="wave-2026-a"):
    data = (ROOT / "registry" / "registry.json").read_text(encoding="utf-8")
    data = data.replace('"key_id": "wave-2026-a"', f'"key_id": "{key_id}"')
    p = tmp_path / "registry.json"
    p.write_text(data, encoding="utf-8")
    return p


# ---------------- keys ----------------
def test_keygen_refuses_overwrite(keypair, tmp_path):
    with pytest.raises(ws.WaveError):
        ws.keygen("wave-2026-a", tmp_path / "keys", passphrase=None)


def test_private_key_file_is_owner_only(keypair):
    priv, _ = keypair
    assert oct(priv.stat().st_mode & 0o777) == "0o600"


def test_passphrase_protected_key_roundtrip(tmp_path, monkeypatch):
    priv, _ = ws.keygen("wave-pw", tmp_path, passphrase="correct horse")
    assert b"ENCRYPTED" in priv.read_bytes()
    monkeypatch.setenv("WAVE_KEY_PASSPHRASE", "correct horse")
    assert ws.load_private_key(priv)[0] == "wave-pw"
    monkeypatch.setenv("WAVE_KEY_PASSPHRASE", "wrong")
    with pytest.raises(ws.WaveError):
        ws.load_private_key(priv)


# ---------------- registry ----------------
def test_sample_registries_are_valid():
    for name in ("registry.json", "registry.home-dev.json"):
        _, errors, _ = ws.validate_registry((ROOT / "registry" / name).read_bytes())
        assert errors == [], (name, errors)


def test_registry_sign_and_verify(keypair, tmp_path):
    priv, pub = keypair
    reg = _registry_copy(tmp_path)
    sig = ws.sign_registry(reg, priv)
    ok, msg = ws.verify_registry_bytes(reg.read_bytes(), json.loads(sig.read_text()), ws.load_public_keys([pub]))
    assert ok, msg


def test_registry_tamper_detected(keypair, tmp_path):
    priv, pub = keypair
    reg = _registry_copy(tmp_path)
    sig = ws.sign_registry(reg, priv)
    reg.write_bytes(reg.read_bytes().replace(b"00:00:5e:00:53:02", b"32:12:e8:5c:38:d8"))
    ok, _ = ws.verify_registry_bytes(reg.read_bytes(), json.loads(sig.read_text()), ws.load_public_keys([pub]))
    assert not ok


def test_registry_whitespace_change_breaks_signature(keypair, tmp_path):
    """Proves why we verify raw bytes: even re-formatting the JSON invalidates it."""
    priv, pub = keypair
    reg = _registry_copy(tmp_path)
    sig = ws.sign_registry(reg, priv)
    reformatted = json.dumps(json.loads(reg.read_bytes())).encode()
    ok, _ = ws.verify_registry_bytes(reformatted, json.loads(sig.read_text()), ws.load_public_keys([pub]))
    assert not ok


def test_registry_key_id_mismatch_refused(keypair, tmp_path):
    priv, _ = keypair
    reg = _registry_copy(tmp_path, key_id="some-other-key")
    with pytest.raises(ws.WaveError, match="key_id"):
        ws.sign_registry(reg, priv)


@pytest.mark.parametrize(
    "old,new,needle",
    [
        ('"00:00:5e:00:53:01"', '"00:00:5E:00:53:01"', "bssid"),
        ('"WPA2-PSK"', '"WPA2"', "security"),
        ('"2.4GHz"', '"2.4"', "band"),
        ('"rssi_min": -85', '"rssi_min": -10', "rssi"),
        ('"00:00:5e:00:53:02"', '"00:00:5e:00:53:01"', "duplicate"),
    ],
)
def test_registry_validation_catches_typos(old, new, needle):
    data = (ROOT / "registry" / "registry.json").read_text().replace(old, new, 1).encode()
    _, errors, _ = ws.validate_registry(data)
    assert any(needle in e.lower() for e in errors), errors


def test_locally_administered_detection():
    assert ws.is_locally_administered("32:12:e8:5c:38:d8")  # the home hotspot twin
    assert not ws.is_locally_administered("d8:42:f7:78:c8:0e")  # the real router


# ---------------- QR ----------------
def _keys(priv_path, pub_path):
    kid, key = ws.load_private_key(priv_path)
    return kid, key, ws.load_public_keys([pub_path])


def test_qr_roundtrip_and_escaping(keypair):
    kid, key, pubs = _keys(*keypair)
    ssid, pw = 'Café;Lab:1, "A"', 'p\\a;s:s,w"d'
    raw = ws.sign_qr_body(ws.build_qr_body(ssid, "WPA", pw, False, kid, dt.date(2026, 12, 10)), key)
    result, fields = ws.verify_qr(raw, pubs, dt.date(2026, 11, 15))
    assert result == ws.VALID
    assert fields["S"] == ssid and fields["P"] == pw


def test_qr_until_date_is_inclusive(keypair):
    kid, key, pubs = _keys(*keypair)
    raw = ws.sign_qr_body(ws.build_qr_body("X", "nopass", None, False, kid, dt.date(2026, 12, 10)), key)
    assert ws.verify_qr(raw, pubs, dt.date(2026, 12, 10))[0] == ws.VALID
    assert ws.verify_qr(raw, pubs, dt.date(2026, 12, 11))[0] == ws.EXPIRED


def test_qr_registry_lookup():
    reg = json.loads((ROOT / "registry" / "registry.json").read_text())
    assert ws.registry_lookup(reg, "BSU-ICT-TESTLAB", "WPA") == "IN_REGISTRY"
    assert ws.registry_lookup(reg, "BSU-ICT-TESTLAB", "nopass") == "SECURITY_MISMATCH"
    assert ws.registry_lookup(reg, "Free_WiFi", "nopass") == "NOT_IN_REGISTRY"


def test_qr_payload_fits_easily(keypair):
    kid, key, _ = _keys(*keypair)
    raw = ws.sign_qr_body(ws.build_qr_body("BSU-ICT-TESTLAB", "WPA", "x" * 63, False, kid, dt.date(2026, 12, 10)), key)
    assert len(raw) < 250  # small QR (version ~8-10 at level M)


def test_qr_png_decodes_back(keypair, tmp_path):
    cv2 = pytest.importorskip("cv2")
    kid, key, pubs = _keys(*keypair)
    raw = ws.sign_qr_body(ws.build_qr_body("BSU-ICT-TESTLAB", "WPA", "testlab-2026", False, kid, dt.date(2026, 12, 10)), key)
    png = tmp_path / "qr.png"
    ws.write_qr_png(raw, png, caption="BSU-ICT-TESTLAB - signed by WAVE")
    decoded, _, _ = cv2.QRCodeDetector().detectAndDecode(cv2.imread(str(png)))
    assert decoded == raw
    assert ws.verify_qr(decoded, pubs, dt.date(2026, 11, 15))[0] == ws.VALID


# ---------------- test vectors ----------------
def test_test_vectors_generate_and_self_check():
    v = ws.make_test_vectors()  # asserts every case internally
    assert len(v["qr_cases"]) >= 15 and len(v["registry_cases"]) >= 5
    assert {c["expect"] for c in v["qr_cases"]} == {
        ws.VALID, ws.UNSIGNED, ws.MALFORMED, ws.UNKNOWN_KEY, ws.BAD_SIGNATURE, ws.EXPIRED
    }
