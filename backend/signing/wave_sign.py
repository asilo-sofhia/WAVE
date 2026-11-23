#!/usr/bin/env python3
"""
WAVE signing tool (offline signing station).

Generates the Ed25519 key, signs registry.json, makes signed Wi-Fi QR codes,
verifies both, and writes test vectors for the Android (Kotlin) verifier.

Run this ONLY on the offline signing laptop. The private key never goes to
Git or to the server. Formats follow CONTRACT.md (v1).

Commands (run with -h for details):
  keygen           create a new Ed25519 key pair
  sign-registry    sign registry.json  -> registry.json.sig
  verify-registry  check registry.json against its .sig
  sign-qr          make a signed WIFI: QR string (+ optional PNG)
  verify-qr        check a QR string (reference behaviour for the app)
  test-vectors     write test_vectors.json for the Kotlin unit tests
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import getpass
import json
import os
import re
import secrets
import sys
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)

# ---------------------------------------------------------------------------
# Constants from CONTRACT.md
# ---------------------------------------------------------------------------
REGISTRY_DOMAIN = b"WAVE-REGISTRY-v1\n"
QR_DOMAIN = b"WAVE-QR-v1\n"
SIG_MARKER = ";WAVE-SIG:"
SIG_LEN_B64 = 86  # 64 bytes -> 86 base64url chars, no padding

KEY_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,31}$")
BSSID_RE = re.compile(r"^([0-9a-f]{2}:){5}[0-9a-f]{2}$")
B64URL_RE = re.compile(r"^[A-Za-z0-9_-]*$")

SECURITY_VALUES = {"OPEN", "WPA2-PSK", "WPA3-SAE", "WPA2-WPA3"}
BAND_VALUES = {"2.4GHz", "5GHz", "6GHz"}
QR_TYPES = {"WPA", "SAE", "nopass"}
# Which registry security types a QR "T:" value is compatible with.
QR_TYPE_TO_SECURITY = {
    "WPA": {"WPA2-PSK", "WPA2-WPA3"},
    "SAE": {"WPA3-SAE", "WPA2-WPA3"},
    "nopass": {"OPEN"},
}
QR_ESCAPE_CHARS = '\\;,:"'

# verify-qr results (Kotlin must return the same names)
VALID = "VALID"
UNSIGNED = "UNSIGNED"
MALFORMED = "MALFORMED"
UNKNOWN_KEY = "UNKNOWN_KEY"
BAD_SIGNATURE = "BAD_SIGNATURE"
EXPIRED = "EXPIRED"


class WaveError(Exception):
    """A user-facing error (bad input, bad file)."""


# ---------------------------------------------------------------------------
# Encoding helpers
# ---------------------------------------------------------------------------
def b64u_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def b64u_decode(text: str) -> bytes:
    if not B64URL_RE.match(text):
        raise ValueError("not base64url")
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def is_locally_administered(bssid: str) -> bool:
    """Bit 1 of the first octet set -> 2nd hex digit is 2, 6, a or e."""
    return bool(int(bssid[0:2], 16) & 0x02)


# ---------------------------------------------------------------------------
# Keys
# ---------------------------------------------------------------------------
def _write_private_file(path: Path, data: bytes) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(data)


def keygen(key_id: str, out_dir: Path, passphrase: str | None) -> tuple[Path, Path]:
    if not KEY_ID_RE.match(key_id):
        raise WaveError("key id must be lowercase letters, digits and '-', max 32 chars")
    out_dir.mkdir(parents=True, exist_ok=True)
    priv_path = out_dir / f"{key_id}.private.pem"
    pub_path = out_dir / f"{key_id}.public.json"
    if priv_path.exists() or pub_path.exists():
        raise WaveError(f"refusing to overwrite existing key files for '{key_id}'")

    private_key = Ed25519PrivateKey.generate()
    encryption = (
        serialization.BestAvailableEncryption(passphrase.encode("utf-8"))
        if passphrase
        else serialization.NoEncryption()
    )
    pem = private_key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, encryption
    )
    _write_private_file(priv_path, pem)

    raw_pub = private_key.public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )
    pub_path.write_text(
        json.dumps(
            {
                "key_id": key_id,
                "alg": "Ed25519",
                "public_key_b64url": b64u_encode(raw_pub),
                "public_key_hex": raw_pub.hex(),
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    return priv_path, pub_path


def key_id_from_private_path(path: Path) -> str:
    name = path.name
    if not name.endswith(".private.pem"):
        raise WaveError("private key file must be named <key-id>.private.pem")
    key_id = name[: -len(".private.pem")]
    if not KEY_ID_RE.match(key_id):
        raise WaveError(f"bad key id in file name: {key_id!r}")
    return key_id


def load_private_key(path: Path) -> tuple[str, Ed25519PrivateKey]:
    key_id = key_id_from_private_path(path)
    pem = path.read_bytes()
    password = None
    if b"ENCRYPTED" in pem:
        env = os.environ.get("WAVE_KEY_PASSPHRASE")
        password = (env if env is not None else getpass.getpass(f"Passphrase for {key_id}: ")).encode()
    try:
        key = serialization.load_pem_private_key(pem, password=password)
    except (ValueError, TypeError) as exc:
        raise WaveError(f"could not load private key ({exc})") from exc
    if not isinstance(key, Ed25519PrivateKey):
        raise WaveError("private key is not Ed25519")
    return key_id, key


def load_public_keys(paths: list[Path]) -> dict[str, Ed25519PublicKey]:
    keys: dict[str, Ed25519PublicKey] = {}
    for p in paths:
        data = json.loads(p.read_text(encoding="utf-8"))
        key_id = data["key_id"]
        raw = b64u_decode(data["public_key_b64url"])
        if len(raw) != 32:
            raise WaveError(f"{p}: public key must be 32 bytes")
        keys[key_id] = Ed25519PublicKey.from_public_bytes(raw)
    return keys


def public_key_b64(key: Ed25519PrivateKey | Ed25519PublicKey) -> str:
    pub = key.public_key() if isinstance(key, Ed25519PrivateKey) else key
    return b64u_encode(pub.public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw))


def verify_raw(pub: Ed25519PublicKey, signature: bytes, message: bytes) -> bool:
    if len(signature) != 64:
        return False
    try:
        pub.verify(signature, message)
        return True
    except InvalidSignature:
        return False


# ---------------------------------------------------------------------------
# Registry
# ---------------------------------------------------------------------------
def _parse_utc(value: object, field: str, errors: list[str]) -> dt.datetime | None:
    if not isinstance(value, str) or not value.endswith("Z"):
        errors.append(f"{field}: must be an ISO-8601 UTC time ending in 'Z'")
        return None
    try:
        return dt.datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError:
        errors.append(f"{field}: not a valid time")
        return None


def validate_registry(data: bytes) -> tuple[dict, list[str], list[str]]:
    """Return (parsed, errors, warnings). Errors block signing."""
    errors: list[str] = []
    warnings: list[str] = []
    try:
        reg = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        return {}, [f"not valid UTF-8 JSON: {exc}"], []
    if not isinstance(reg, dict):
        return {}, ["top level must be a JSON object"], []

    if reg.get("schema") != "wave-registry/1":
        errors.append("schema: must be 'wave-registry/1'")
    version = reg.get("version")
    if not isinstance(version, int) or isinstance(version, bool) or version < 1:
        errors.append("version: must be an integer >= 1")
    key_id = reg.get("key_id")
    if not isinstance(key_id, str) or not KEY_ID_RE.match(key_id):
        errors.append("key_id: missing or invalid")
    if "place_id" in reg:  # optional: only place registries (CONTRACT §7) have it
        place = reg["place_id"]
        if not isinstance(place, str) or not PLACE_ID_RE.match(place):
            errors.append("place_id: lowercase letters, digits and '-', max 32 chars")
    issued = _parse_utc(reg.get("issued_at"), "issued_at", errors)
    expires = _parse_utc(reg.get("expires_at"), "expires_at", errors)
    if issued and expires and expires <= issued:
        errors.append("expires_at must be after issued_at")

    asns = reg.get("campus_asns")
    if not isinstance(asns, list):
        errors.append("campus_asns: must be a list")
    else:
        for i, a in enumerate(asns):
            if not isinstance(a, dict) or not isinstance(a.get("asn"), int) or a["asn"] < 0:
                errors.append(f"campus_asns[{i}]: needs an integer 'asn' >= 0")
            elif a["asn"] == 0:
                warnings.append(f"campus_asns[{i}]: ASN is 0 (placeholder) - measure it on campus")

    networks = reg.get("networks")
    seen_bssids: dict[str, str] = {}
    if not isinstance(networks, list) or not networks:
        errors.append("networks: must be a non-empty list")
        networks = []
    for n_i, net in enumerate(networks):
        where = f"networks[{n_i}]"
        if not isinstance(net, dict):
            errors.append(f"{where}: must be an object")
            continue
        ssid = net.get("ssid")
        if not isinstance(ssid, str) or not ssid or len(ssid.encode("utf-8")) > 32:
            errors.append(f"{where}.ssid: 1-32 bytes required")
        if net.get("security") not in SECURITY_VALUES:
            errors.append(f"{where}.security: must be one of {sorted(SECURITY_VALUES)}")
        aps = net.get("aps")
        if not isinstance(aps, list) or not aps:
            errors.append(f"{where}.aps: must be a non-empty list")
            continue
        for a_i, ap in enumerate(aps):
            aw = f"{where}.aps[{a_i}]"
            if not isinstance(ap, dict):
                errors.append(f"{aw}: must be an object")
                continue
            bssid = ap.get("bssid")
            if not isinstance(bssid, str) or not BSSID_RE.match(bssid):
                errors.append(f"{aw}.bssid: must be lowercase xx:xx:xx:xx:xx:xx")
            else:
                if bssid in seen_bssids:
                    errors.append(f"{aw}.bssid: duplicate of {seen_bssids[bssid]}")
                seen_bssids[bssid] = aw
                if is_locally_administered(bssid):
                    warnings.append(
                        f"{aw}.bssid {bssid} is locally administered - OK only if the real AP really uses it"
                    )
            if ap.get("band") not in BAND_VALUES:
                errors.append(f"{aw}.band: must be one of {sorted(BAND_VALUES)}")
            ch = ap.get("channel")
            if not isinstance(ch, int) or ch <= 0:
                errors.append(f"{aw}.channel: positive integer required")
            lo, hi = ap.get("rssi_min"), ap.get("rssi_max")
            if not isinstance(lo, int) or not isinstance(hi, int) or not (-120 <= lo <= hi <= 0):
                errors.append(f"{aw}: need integers -120 <= rssi_min <= rssi_max <= 0")
            if not isinstance(ap.get("building"), str):
                errors.append(f"{aw}.building: string required")

    # Optional: access points the campus admin marked as not theirs. Phones show them as Warning.
    blocked = reg.get("blocked", [])
    if not isinstance(blocked, list):
        errors.append("blocked: must be a list (or left out)")
        blocked = []
    seen_blocked: dict[str, str] = {}
    for b_i, entry in enumerate(blocked):
        bw = f"blocked[{b_i}]"
        if not isinstance(entry, dict):
            errors.append(f"{bw}: must be an object")
            continue
        bssid = entry.get("bssid")
        if not isinstance(bssid, str) or not BSSID_RE.match(bssid):
            errors.append(f"{bw}.bssid: must be lowercase xx:xx:xx:xx:xx:xx")
        else:
            if bssid in seen_blocked:
                errors.append(f"{bw}.bssid: duplicate of {seen_blocked[bssid]}")
            seen_blocked[bssid] = bw
            if bssid in seen_bssids:
                errors.append(f"{bw}.bssid {bssid} is also approved in {seen_bssids[bssid]}: it can't be both")
        if not isinstance(entry.get("ssid"), str):
            errors.append(f"{bw}.ssid: string required (may be empty for hidden networks)")
        reason = entry.get("reason")
        if not isinstance(reason, str) or not reason.strip():
            errors.append(f"{bw}.reason: say why it is blocked")
    return reg, errors, warnings


def sign_registry(registry_path: Path, key_path: Path) -> Path:
    key_id, key = load_private_key(key_path)
    data = registry_path.read_bytes()
    reg, errors, warnings = validate_registry(data)
    for w in warnings:
        print(f"warning: {w}", file=sys.stderr)
    if errors:
        raise WaveError("registry is invalid, not signing:\n  - " + "\n  - ".join(errors))
    if reg["key_id"] != key_id:
        raise WaveError(f"registry says key_id '{reg['key_id']}' but you are signing with '{key_id}'")
    signature = key.sign(REGISTRY_DOMAIN + data)
    sig_path = registry_path.with_name(registry_path.name + ".sig")
    sig_path.write_text(
        json.dumps({"key_id": key_id, "alg": "Ed25519", "signature": b64u_encode(signature)}, indent=2)
        + "\n",
        encoding="utf-8",
    )
    return sig_path


def verify_registry_bytes(
    data: bytes, sig_doc: dict, keys: dict[str, Ed25519PublicKey]
) -> tuple[bool, str]:
    key_id = sig_doc.get("key_id")
    if key_id not in keys:
        return False, f"unknown key id {key_id!r}"
    try:
        signature = b64u_decode(sig_doc.get("signature", ""))
    except ValueError:
        return False, "signature is not base64url"
    if not verify_raw(keys[key_id], signature, REGISTRY_DOMAIN + data):
        return False, "BAD SIGNATURE - file was changed or signed by another key"
    reg, errors, _ = validate_registry(data)
    if errors:
        return False, "signature OK but content invalid: " + "; ".join(errors)
    if reg["key_id"] != key_id:
        return False, "key_id inside the file does not match the signature's key_id"
    expires = dt.datetime.fromisoformat(reg["expires_at"][:-1] + "+00:00")
    if expires < dt.datetime.now(dt.timezone.utc):
        return True, f"VALID but EXPIRED on {reg['expires_at']} (version {reg['version']})"
    return True, f"VALID (version {reg['version']}, expires {reg['expires_at']})"


# ---------------------------------------------------------------------------
# Wi-Fi QR
# ---------------------------------------------------------------------------
def qr_escape(value: str) -> str:
    return "".join("\\" + c if c in QR_ESCAPE_CHARS else c for c in value)


def parse_qr_fields(body: str) -> list[tuple[str, str]]:
    """Split 'NAME:value;NAME:value;' (escape-aware). Raises ValueError."""
    fields: list[tuple[str, str]] = []
    cur: list[str] = []
    i = 0
    while i < len(body):
        c = body[i]
        if c == "\\":
            if i + 1 >= len(body):
                raise ValueError("dangling backslash")
            cur.append("\\" + body[i + 1])  # keep escaped pair for now
            i += 2
            continue
        if c == ";":
            fields.append(_split_field("".join(cur)))
            cur = []
        else:
            cur.append(c)
        i += 1
    if cur:
        raise ValueError("field not terminated by ';'")
    return fields


def _split_field(raw: str) -> tuple[str, str]:
    # The name never contains escapes; split at the first unescaped ':'.
    i = 0
    while i < len(raw):
        if raw[i] == "\\":
            i += 2
            continue
        if raw[i] == ":":
            name, value = raw[:i], raw[i + 1 :]
            if not name or not re.match(r"^[A-Z0-9-]+$", name):
                raise ValueError(f"bad field name {name!r}")
            return name, _unescape(value)
        i += 1
    raise ValueError(f"field without ':' ({raw!r})")


def _unescape(value: str) -> str:
    out, i = [], 0
    while i < len(value):
        if value[i] == "\\":
            out.append(value[i + 1])
            i += 2
        elif value[i] == ":" or value[i] == ";":
            raise ValueError("unescaped special character in value")
        else:
            out.append(value[i])
            i += 1
    return "".join(out)


def build_qr_body(ssid: str, qr_type: str, password: str | None, hidden: bool, key_id: str, until: dt.date) -> str:
    if qr_type not in QR_TYPES:
        raise WaveError(f"type must be one of {sorted(QR_TYPES)}")
    if qr_type == "nopass" and password:
        raise WaveError("an open (nopass) network has no password")
    if qr_type != "nopass" and not password:
        raise WaveError("a WPA/SAE network needs a password")
    if not ssid or len(ssid.encode("utf-8")) > 32:
        raise WaveError("SSID must be 1-32 bytes")
    parts = [f"WIFI:T:{qr_type};", f"S:{qr_escape(ssid)};"]
    if password:
        parts.append(f"P:{qr_escape(password)};")
    if hidden:
        parts.append("H:true;")
    parts.append(f"WAVE-KID:{key_id};")
    parts.append(f"WAVE-UNTIL:{until.strftime('%Y%m%d')};")
    return "".join(parts)


def sign_qr_body(body: str, key: Ed25519PrivateKey) -> str:
    if not body.endswith(";"):
        raise WaveError("body must end with ';'")
    signature = key.sign(QR_DOMAIN + body.encode("utf-8"))
    return f"{body}WAVE-SIG:{b64u_encode(signature)};;"


def verify_qr(raw: str, keys: dict[str, Ed25519PublicKey], today: dt.date | None = None) -> tuple[str, dict]:
    """Reference implementation of CONTRACT.md section 3. Returns (result, fields)."""
    today = today or dt.date.today()
    if not raw.startswith("WIFI:") or not raw.endswith(";;"):
        return MALFORMED, {"why": "not a WIFI: ... ;; string"}
    count = raw.count(SIG_MARKER)
    if count == 0:
        return UNSIGNED, {}
    if count > 1:
        return MALFORMED, {"why": "more than one signature field"}

    idx = raw.index(SIG_MARKER)
    tail = raw[idx + len(SIG_MARKER) :]
    if not re.fullmatch(r"[A-Za-z0-9_-]{%d};;" % SIG_LEN_B64, tail):
        return MALFORMED, {"why": "signature must be the last field (86 base64url chars then ';;')"}
    signed_text = raw[: idx + 1]

    try:
        field_list = parse_qr_fields(signed_text[len("WIFI:") :])
    except ValueError as exc:
        return MALFORMED, {"why": str(exc)}
    names = [n for n, _ in field_list]
    if len(names) != len(set(names)):
        return MALFORMED, {"why": "a field appears twice"}
    fields = dict(field_list)
    for required in ("T", "S", "WAVE-KID", "WAVE-UNTIL"):
        if required not in fields:
            return MALFORMED, {"why": f"missing {required}"}
    if fields["T"] not in QR_TYPES:
        return MALFORMED, {"why": "unknown T value"}
    if not re.fullmatch(r"\d{8}", fields["WAVE-UNTIL"]):
        return MALFORMED, {"why": "WAVE-UNTIL must be YYYYMMDD"}
    try:
        until = dt.datetime.strptime(fields["WAVE-UNTIL"], "%Y%m%d").date()
    except ValueError:
        return MALFORMED, {"why": "WAVE-UNTIL is not a real date"}

    key_id = fields["WAVE-KID"]
    if key_id not in keys:
        return UNKNOWN_KEY, fields
    signature = b64u_decode(tail[:SIG_LEN_B64])
    if not verify_raw(keys[key_id], signature, QR_DOMAIN + signed_text.encode("utf-8")):
        return BAD_SIGNATURE, fields
    if today > until:
        return EXPIRED, fields
    return VALID, fields


def registry_lookup(registry: dict, ssid: str, qr_type: str) -> str:
    for net in registry.get("networks", []):
        if net.get("ssid") == ssid:
            if net.get("security") in QR_TYPE_TO_SECURITY[qr_type]:
                return "IN_REGISTRY"
            return "SECURITY_MISMATCH"
    return "NOT_IN_REGISTRY"


def write_qr_png(raw: str, out: Path, caption: str | None) -> None:
    import qrcode  # imported here so the rest of the tool works without it
    from PIL import Image, ImageDraw

    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=10, border=4)
    qr.add_data(raw)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white").convert("RGB")
    if caption:
        pad = 50
        canvas = Image.new("RGB", (img.width, img.height + pad), "white")
        canvas.paste(img, (0, 0))
        draw = ImageDraw.Draw(canvas)
        text_w = draw.textlength(caption)
        draw.text(((img.width - text_w) / 2, img.height + 15), caption, fill="black")
        img = canvas
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)


# ---------------------------------------------------------------------------
# Place admins (CONTRACT §6-7): enrolment requests, root-signed certificates, place registry packages
# ---------------------------------------------------------------------------
CERT_DOMAIN = b"WAVE-CERT-v1\n"
CERT_SCHEMA = "wave-admin-cert/1"
REQUEST_PREFIX = "WAVEREQ:1:"
CERT_PREFIX = "WAVECERT:1:"
ADMIN_KEY_ALG = "ES256"
PLACE_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,31}$")
CERT_ID_RE = re.compile(r"^[0-9a-f]{16}$")
TIME_FMT = "%Y-%m-%dT%H:%M:%SZ"
NOT_YET_VALID = "NOT_YET_VALID"
WRONG_PLACE = "WRONG_PLACE"
CERT_FIELDS = ("schema", "cert_id", "place_id", "place_name", "admin_name", "admin_key_alg",
               "admin_public_key", "issued_at", "expires_at", "root_key_id")


def _check_place_id(place_id: object) -> str:
    if not isinstance(place_id, str) or not PLACE_ID_RE.match(place_id):
        raise ValueError(f"place id must be lowercase letters, digits and '-', max 32 chars (got {place_id!r})")
    return place_id


def _check_name(value: object, what: str) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > 64:
        raise ValueError(f"{what} must be 1-64 characters")
    return value


def admin_public_key_b64(pub: ec.EllipticCurvePublicKey) -> str:
    """base64url of the P-256 SubjectPublicKeyInfo DER: the form Android Keystore exports."""
    if not isinstance(pub, ec.EllipticCurvePublicKey) or not isinstance(pub.curve, ec.SECP256R1):
        raise TypeError("admin keys must be P-256 (ES256)")
    return b64u_encode(pub.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo))


def load_admin_public_key(text: str) -> ec.EllipticCurvePublicKey:
    try:
        key = serialization.load_der_public_key(b64u_decode(text))
    except (ValueError, TypeError) as exc:
        raise ValueError(f"admin public key is not a valid key ({exc})") from exc
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError("admin public key must be P-256 (ES256)")
    return key


def _fmt(t: dt.datetime) -> str:
    return t.astimezone(dt.timezone.utc).strftime(TIME_FMT)


def _parse_time(value: object) -> dt.datetime:
    if not isinstance(value, str):
        raise ValueError("time must be a string")
    return dt.datetime.strptime(value, TIME_FMT).replace(tzinfo=dt.timezone.utc)


def _compact(obj: dict) -> bytes:
    return json.dumps(obj, separators=(",", ":"), sort_keys=True, ensure_ascii=False).encode("utf-8")


def make_admin_request(admin_name: str, place_id: str, pub: ec.EllipticCurvePublicKey) -> str:
    """What an admin phone shares to ask for a certificate: its name, the place, and its public key."""
    body = {
        "admin_name": _check_name(admin_name, "admin name"),
        "place_id": _check_place_id(place_id),
        "admin_key_alg": ADMIN_KEY_ALG,
        "admin_public_key": admin_public_key_b64(pub),
    }
    return REQUEST_PREFIX + b64u_encode(_compact(body))


def parse_admin_request(text: str) -> dict:
    """The request's fields, checked. Raises ValueError."""
    text = text.strip()
    if not text.startswith(REQUEST_PREFIX):
        raise ValueError(f"not a WAVE admin request (must start with {REQUEST_PREFIX})")
    try:
        body = json.loads(b64u_decode(text[len(REQUEST_PREFIX):]).decode("utf-8"))
    except (ValueError, UnicodeDecodeError) as exc:
        raise ValueError(f"request is not readable ({exc})") from exc
    if not isinstance(body, dict):
        raise ValueError("request must be an object")
    _check_name(body.get("admin_name"), "admin name")
    _check_place_id(body.get("place_id"))
    if body.get("admin_key_alg") != ADMIN_KEY_ALG:
        raise ValueError(f"admin key algorithm must be {ADMIN_KEY_ALG}")
    key = body.get("admin_public_key")
    load_admin_public_key(key if isinstance(key, str) else "")
    return {k: body[k] for k in ("admin_name", "place_id", "admin_key_alg", "admin_public_key")}


def certify_admin(request: dict, place_id: str, place_name: str, days: int, root_key_id: str,
                  root_key: Ed25519PrivateKey, now: dt.datetime | None = None) -> str:
    """Root-sign a certificate: this phone key may sign registries for [place_id] for [days] days."""
    if request["place_id"] != place_id:
        raise ValueError(f"the request asks for place {request['place_id']!r}, not {place_id!r}")
    _check_name(place_name, "place name")
    if not isinstance(days, int) or days < 1:
        raise ValueError("days must be 1 or more")
    issued = (now or dt.datetime.now(dt.timezone.utc)).replace(microsecond=0)
    cert = {
        "schema": CERT_SCHEMA,
        "cert_id": secrets.token_hex(8),
        "place_id": place_id,
        "place_name": place_name,
        "admin_name": request["admin_name"],
        "admin_key_alg": ADMIN_KEY_ALG,
        "admin_public_key": request["admin_public_key"],
        "issued_at": _fmt(issued),
        "expires_at": _fmt(issued + dt.timedelta(days=days)),
        "root_key_id": root_key_id,
    }
    cert_bytes = _compact(cert)
    return CERT_PREFIX + b64u_encode(cert_bytes) + "." + b64u_encode(root_key.sign(CERT_DOMAIN + cert_bytes))


def verify_cert(text: str, root_keys: dict[str, Ed25519PublicKey], at: dt.datetime) -> tuple[str, dict]:
    """(result, certificate). Order: format -> root key known -> root signature -> valid at [at]."""
    text = text.strip()
    try:
        if not text.startswith(CERT_PREFIX):
            raise ValueError("no WAVECERT prefix")
        body_b64, sig_b64 = text[len(CERT_PREFIX):].split(".")
        cert_bytes = b64u_decode(body_b64)
        signature = b64u_decode(sig_b64)
        cert = json.loads(cert_bytes.decode("utf-8"))
        if not isinstance(cert, dict) or any(f not in cert for f in CERT_FIELDS):
            raise ValueError("missing fields")
        if cert["schema"] != CERT_SCHEMA or cert["admin_key_alg"] != ADMIN_KEY_ALG:
            raise ValueError("wrong schema or algorithm")
        if not isinstance(cert["cert_id"], str) or not CERT_ID_RE.match(cert["cert_id"]):
            raise ValueError("bad cert_id")
        if not isinstance(cert["root_key_id"], str):
            raise ValueError("bad root_key_id")
        _check_place_id(cert["place_id"])
        _check_name(cert["place_name"], "place name")
        _check_name(cert["admin_name"], "admin name")
        load_admin_public_key(cert["admin_public_key"])
        issued, expires = _parse_time(cert["issued_at"]), _parse_time(cert["expires_at"])
        if expires <= issued:
            raise ValueError("expires_at must be after issued_at")
    except (ValueError, TypeError, UnicodeDecodeError) as exc:
        return MALFORMED, {"why": str(exc)}
    root = root_keys.get(cert["root_key_id"])
    if root is None:
        return UNKNOWN_KEY, cert
    if not verify_raw(root, signature, CERT_DOMAIN + cert_bytes):
        return BAD_SIGNATURE, cert
    if at < issued:
        return NOT_YET_VALID, cert
    if at > expires:
        return EXPIRED, cert
    return VALID, cert


PACKAGE_FIELDS = ("place_id", "registry_b64url", "signature_b64url", "cert")


def sign_place_registry(registry_bytes: bytes, admin_key: ec.EllipticCurvePrivateKey, cert_text: str) -> dict:
    """Sign a place registry with an admin's P-256 key and bundle it with the certificate (CONTRACT §7).
    On a real admin this happens inside the phone's secure hardware; this is the laptop version for tests."""
    reg = json.loads(registry_bytes.decode("utf-8"))
    signature = admin_key.sign(REGISTRY_DOMAIN + registry_bytes, ec.ECDSA(hashes.SHA256()))
    return {
        "place_id": reg.get("place_id"),
        "registry_b64url": b64u_encode(registry_bytes),
        "signature_b64url": b64u_encode(signature),
        "cert": cert_text.strip(),
    }


def verify_package(package: dict, root_keys: dict[str, Ed25519PublicKey]) -> tuple[str, dict | None]:
    """(result, registry). Checked from the root down: shape and registry format -> certificate (valid when the
    registry was issued) -> places agree -> the registry names its certificate -> the admin's signature."""
    try:
        if not isinstance(package, dict) or any(not isinstance(package.get(f), str) for f in PACKAGE_FIELDS):
            raise ValueError("package needs place_id, registry_b64url, signature_b64url and cert")
        registry_bytes = b64u_decode(package["registry_b64url"])
        signature = b64u_decode(package["signature_b64url"])
    except ValueError:
        return MALFORMED, None
    reg, errors, _ = validate_registry(registry_bytes)
    if errors or "place_id" not in reg:
        return MALFORMED, None
    status, cert = verify_cert(package["cert"], root_keys, at=_parse_time_lenient(reg["issued_at"]))
    if status != VALID:
        return status, None
    if not (cert["place_id"] == package["place_id"] == reg["place_id"]):
        return WRONG_PLACE, None
    if reg["key_id"] != cert["cert_id"]:
        return MALFORMED, None
    try:
        load_admin_public_key(cert["admin_public_key"]).verify(signature, REGISTRY_DOMAIN + registry_bytes, ec.ECDSA(hashes.SHA256()))
    except InvalidSignature:
        return BAD_SIGNATURE, None
    return VALID, reg


def _parse_time_lenient(value: str) -> dt.datetime:
    """A registry time (ISO 8601 UTC ending in Z) as an aware datetime."""
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(dt.timezone.utc)


# ---------------------------------------------------------------------------
# Test vectors for the Kotlin verifier
# ---------------------------------------------------------------------------
RFC8032_TEST1 = {
    "secret_hex": "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
    "public_hex": "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
    "message_hex": "",
    "signature_hex": "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
}


def make_test_vectors() -> dict:
    # Sanity-check our crypto library against the RFC 8032 example first.
    rfc_key = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(RFC8032_TEST1["secret_hex"]))
    assert public_key_b64(rfc_key) == b64u_encode(bytes.fromhex(RFC8032_TEST1["public_hex"]))
    assert rfc_key.sign(b"").hex() == RFC8032_TEST1["signature_hex"]

    kid = "wave-test"
    key = Ed25519PrivateKey.generate()
    impostor = Ed25519PrivateKey.generate()  # same key id, different key
    keys = {kid: key.public_key()}
    today = dt.date(2026, 11, 15)
    until = dt.date(2026, 12, 10)

    # --- registry cases ---
    registry = {
        "schema": "wave-registry/1",
        "version": 1,
        "key_id": kid,
        "issued_at": "2026-11-10T00:00:00Z",
        "expires_at": "2026-12-10T00:00:00Z",
        "campus_asns": [{"asn": 64500, "name": "Example campus ISP (documentation ASN)"}],
        "networks": [
            {
                "ssid": "BSU-ICT-TESTLAB",
                "security": "WPA2-PSK",
                "aps": [
                    {"bssid": "00:00:5e:00:53:01", "band": "2.4GHz", "channel": 6,
                     "building": "CICS Lab", "rssi_min": -85, "rssi_max": -25},
                    {"bssid": "00:00:5e:00:53:02", "band": "5GHz", "channel": 36,
                     "building": "CICS Lab", "rssi_min": -85, "rssi_max": -25},
                ],
            }
        ],
    }
    reg_bytes = (json.dumps(registry, indent=2) + "\n").encode("utf-8")
    good_sig = key.sign(REGISTRY_DOMAIN + reg_bytes)
    tampered = reg_bytes.replace(b"00:00:5e:00:53:02", b"00:00:5e:00:53:99")
    registry_cases = [
        {"name": "valid", "registry": reg_bytes, "sig": good_sig, "expect_valid": True},
        {"name": "tampered_bssid", "registry": tampered, "sig": good_sig, "expect_valid": False},
        {"name": "signed_with_qr_domain", "registry": reg_bytes,
         "sig": key.sign(QR_DOMAIN + reg_bytes), "expect_valid": False},
        {"name": "signed_without_domain", "registry": reg_bytes,
         "sig": key.sign(reg_bytes), "expect_valid": False},
        {"name": "impostor_key", "registry": reg_bytes,
         "sig": impostor.sign(REGISTRY_DOMAIN + reg_bytes), "expect_valid": False},
        {"name": "truncated_signature", "registry": reg_bytes, "sig": good_sig[:63], "expect_valid": False},
    ]
    for c in registry_cases:  # self-check before writing
        assert verify_raw(keys[kid], c["sig"], REGISTRY_DOMAIN + c["registry"]) == c["expect_valid"], c["name"]

    # --- QR cases ---
    def signed(ssid, t, pw, until_=until, k=key, kid_=kid, hidden=False):
        return sign_qr_body(build_qr_body(ssid, t, pw, hidden, kid_, until_), k)

    valid = signed("BSU-ICT-TESTLAB", "WPA", "testlab-2026")
    sig_part = valid[valid.index(SIG_MARKER):]
    qr_cases = [
        ("valid_wpa", valid, VALID),
        ("valid_open_network", signed("BSU-ICT-OPEN", "nopass", None), VALID),
        ("valid_special_chars", signed('Café;Lab:1, "A"', "WPA", "p\\a;s:s,w\"d"), VALID),
        ("valid_hidden", signed("BSU-ICT-TESTLAB", "WPA", "testlab-2026", hidden=True), VALID),
        ("unsigned_standard", "WIFI:T:WPA;S:BSU-ICT-TESTLAB;P:testlab-2026;;", UNSIGNED),
        ("altered_password", valid.replace("P:testlab-2026;", "P:evil-pass;"), BAD_SIGNATURE),
        ("altered_ssid", valid.replace("S:BSU-ICT-TESTLAB;", "S:BSU-ICT-TESTLAB2;"), BAD_SIGNATURE),
        ("extended_until", valid.replace("WAVE-UNTIL:20261210;", "WAVE-UNTIL:20301210;"), BAD_SIGNATURE),
        ("impostor_key", signed("BSU-ICT-TESTLAB", "WPA", "testlab-2026", k=impostor), BAD_SIGNATURE),
        ("unknown_key_id", signed("BSU-ICT-TESTLAB", "WPA", "testlab-2026", kid_="someone-else"), UNKNOWN_KEY),
        ("expired", signed("BSU-ICT-TESTLAB", "WPA", "testlab-2026", until_=dt.date(2026, 11, 10)), EXPIRED),
        ("field_after_signature", valid[:-1] + "P:evil;;", MALFORMED),
        ("two_signatures", valid[:-1] + sig_part, MALFORMED),
        ("signature_too_short", valid.replace(sig_part, sig_part[:20] + ";;"), MALFORMED),
        ("not_a_wifi_qr", "https://example.com/free-wifi", MALFORMED),
        # Validly signed, but contains S twice: different parsers might pick different SSIDs.
        ("duplicate_ssid_field_signed",
         sign_qr_body("WIFI:T:WPA;S:BSU-ICT-TESTLAB;S:EVIL;P:x12345678;WAVE-KID:wave-test;WAVE-UNTIL:20261210;", key),
         MALFORMED),
    ]
    out_qr = []
    for name, raw, expect in qr_cases:
        got, _ = verify_qr(raw, keys, today)
        assert got == expect, f"{name}: expected {expect}, got {got}"
        out_qr.append({"name": name, "raw": raw, "expect": expect})

    cert_cases, package_cases = _admin_vectors(kid, key, keys)

    return {
        "about": "Generated by wave_sign.py test-vectors. Uses a throwaway TEST key - never the real key. "
                 "Copy to app/src/test/resources/ and check that the Kotlin verifier gives the same results.",
        "contract_version": 1,
        "domains": {"registry": REGISTRY_DOMAIN.decode(), "qr": QR_DOMAIN.decode(), "cert": CERT_DOMAIN.decode()},
        "public_keys": {kid: public_key_b64(key)},
        "qr_today": today.strftime("%Y%m%d"),
        "rfc8032_test1": {
            "public_key_b64url": b64u_encode(bytes.fromhex(RFC8032_TEST1["public_hex"])),
            "message_b64url": "",
            "signature_b64url": b64u_encode(bytes.fromhex(RFC8032_TEST1["signature_hex"])),
            "expect_valid": True,
        },
        "registry_cases": [
            {"name": c["name"], "key_id": kid, "registry_b64url": b64u_encode(c["registry"]),
             "signature_b64url": b64u_encode(c["sig"]), "expect_valid": c["expect_valid"]}
            for c in registry_cases
        ],
        "qr_cases": out_qr,
        "cert_cases": cert_cases,
        "package_cases": package_cases,
    }


def _admin_vectors(kid: str, root_key: Ed25519PrivateKey, root_keys: dict) -> tuple[list, list]:
    """Certificate and place-package cases (CONTRACT §6-7), signed with the throwaway test root."""
    t0 = dt.datetime(2026, 11, 24, 0, 0, 0, tzinfo=dt.timezone.utc)
    admin = ec.generate_private_key(ec.SECP256R1())
    other = ec.generate_private_key(ec.SECP256R1())
    req = parse_admin_request(make_admin_request("Test Admin", "testplace", admin.public_key()))
    cert = certify_admin(req, "testplace", "Test Place", 365, kid, root_key, now=t0)
    short = certify_admin(req, "testplace", "Test Place", 1, kid, root_key, now=t0)
    stranger = Ed25519PrivateKey.generate()
    unknown_root = certify_admin(req, "testplace", "Test Place", 365, "someone-else", stranger, now=t0)
    body, sig = cert[len(CERT_PREFIX):].split(".")
    tampered = json.loads(b64u_decode(body))
    tampered["place_id"] = "otherplace"
    tampered_text = CERT_PREFIX + b64u_encode(_compact(tampered)) + "." + sig

    def at(days: float) -> str:
        return _fmt(t0 + dt.timedelta(days=days))

    certs = [
        ("valid", cert, at(1), VALID),
        ("tampered_place", tampered_text, at(1), BAD_SIGNATURE),
        ("unknown_root", unknown_root, at(1), UNKNOWN_KEY),
        ("expired", short, at(2), EXPIRED),
        ("not_yet_valid", cert, _fmt(t0 - dt.timedelta(minutes=1)), NOT_YET_VALID),
        ("malformed", "WAVECERT:1:not-a-cert", at(1), MALFORMED),
    ]
    out_certs = []
    for name, text, when, expect in certs:
        got, _ = verify_cert(text, root_keys, at=_parse_time(when))
        assert got == expect, f"cert {name}: expected {expect}, got {got}"
        out_certs.append({"name": name, "cert": text, "at": when, "expect": expect})

    def place_registry(place: str, key_id: str, issued_days: float) -> bytes:
        reg = {
            "schema": "wave-registry/1", "version": 2, "key_id": key_id, "place_id": place,
            "issued_at": at(issued_days), "expires_at": at(200),
            "campus_asns": [{"asn": 64500, "name": "Example campus ISP (documentation ASN)"}],
            "networks": [{"ssid": "TEST-PLACE-WIFI", "security": "WPA2-PSK", "aps": [
                {"bssid": "00:00:5e:00:53:10", "band": "5GHz", "channel": 36, "building": "Main",
                 "rssi_min": -85, "rssi_max": -30}]}],
            "blocked": [],
        }
        return json.dumps(reg, indent=2).encode("utf-8")

    cert_id = json.loads(b64u_decode(body))["cert_id"]
    good = sign_place_registry(place_registry("testplace", cert_id, 1), admin, cert)
    tampered_reg = b64u_decode(good["registry_b64url"]).replace(b"TEST-PLACE-WIFI", b"EVIL-PLACE-WIFI")
    packages = [
        ("valid", good, VALID),
        ("wrong_place", sign_place_registry(place_registry("otherplace", cert_id, 1), admin, cert), WRONG_PLACE),
        ("signed_by_other_admin", sign_place_registry(place_registry("testplace", cert_id, 1), other, cert), BAD_SIGNATURE),
        ("tampered_registry", dict(good, registry_b64url=b64u_encode(tampered_reg)), BAD_SIGNATURE),
        ("cert_expired_before_issue", sign_place_registry(place_registry("testplace", cert_id, 3), admin, short), EXPIRED),
        ("key_id_not_cert_id", sign_place_registry(place_registry("testplace", "someone-else", 1), admin, cert), MALFORMED),
    ]
    out_packages = []
    for name, pkg, expect in packages:
        got, _ = verify_package(pkg, root_keys)
        assert got == expect, f"package {name}: expected {expect}, got {got}"
        # As a JSON string, so tools that scan the file for registry cases don't mistake it for one.
        out_packages.append({"name": name, "package_json": json.dumps(pkg, sort_keys=True), "expect": expect})
    return out_certs, out_packages


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def _cmd_keygen(a):
    passphrase = None
    if not a.no_passphrase:
        passphrase = os.environ.get("WAVE_KEY_PASSPHRASE")
        if passphrase is None:
            passphrase = getpass.getpass("New passphrase for the private key: ")
            if passphrase != getpass.getpass("Repeat passphrase: "):
                raise WaveError("passphrases do not match")
        if len(passphrase) < 8:
            raise WaveError("use a passphrase of at least 8 characters (or --no-passphrase for test keys)")
    priv, pub = keygen(a.key_id, Path(a.out), passphrase)
    pub_b64 = json.loads(pub.read_text())["public_key_b64url"]
    print(f"Private key: {priv}   <- keep offline, never commit")
    print(f"Public key:  {pub}")
    print("\nPaste into the Android app (e.g. WaveKeys.kt):")
    print(f'    val WAVE_PUBLIC_KEYS = mapOf("{a.key_id}" to "{pub_b64}")')


def _cmd_sign_registry(a):
    sig = sign_registry(Path(a.registry), Path(a.key))
    print(f"Signed. Wrote {sig}")


def _cmd_verify_registry(a):
    reg = Path(a.registry)
    sig = Path(a.sig) if a.sig else reg.with_name(reg.name + ".sig")
    ok, msg = verify_registry_bytes(
        reg.read_bytes(), json.loads(sig.read_text(encoding="utf-8")), load_public_keys([Path(p) for p in a.pub])
    )
    print(msg)
    sys.exit(0 if ok else 1)


def _cmd_sign_qr(a):
    key_id, key = load_private_key(Path(a.key))
    until = dt.date.fromisoformat(a.until)
    if until < dt.date.today():
        raise WaveError("--until is in the past")
    password = a.password
    if a.type != "nopass" and password is None:
        password = getpass.getpass(f"Wi-Fi password for {a.ssid}: ")
    if a.registry:
        reg, errors, _ = validate_registry(Path(a.registry).read_bytes())
        if errors:
            raise WaveError("registry file is invalid: " + "; ".join(errors))
        where = registry_lookup(reg, a.ssid, a.type)
        if where != "IN_REGISTRY" and not a.allow_unregistered:
            raise WaveError(f"{a.ssid} with T:{a.type} -> {where}. Fix the registry or use --allow-unregistered.")
    raw = sign_qr_body(build_qr_body(a.ssid, a.type, password, a.hidden, key_id, until), key)
    # Self-check with the public half before handing it out.
    assert verify_qr(raw, {key_id: key.public_key()})[0] == VALID
    if a.png:
        caption = None if a.no_caption else f"{a.ssid} - signed by WAVE - valid until {until.isoformat()}"
        write_qr_png(raw, Path(a.png), caption)
        print(f"QR image: {a.png}", file=sys.stderr)
    if a.show:
        print(raw)  # stdout = only the QR string, so it can be saved with > qr.txt
    else:
        print("QR string created (contains the Wi-Fi password; use --show to print it).", file=sys.stderr)


def _cmd_verify_qr(a):
    raw = Path(a.qr[1:]).read_text(encoding="utf-8").strip() if a.qr.startswith("@") else a.qr
    today = dt.datetime.strptime(a.today, "%Y%m%d").date() if a.today else None
    result, fields = verify_qr(raw, load_public_keys([Path(p) for p in a.pub]), today)
    print(f"Result: {result}")
    if "why" in fields:
        print(f"Why: {fields['why']}")
    if result == VALID and a.registry:
        reg, _, _ = validate_registry(Path(a.registry).read_bytes())
        print(f"Registry: {registry_lookup(reg, fields['S'], fields['T'])}")
    if result == VALID:
        print(f"SSID: {fields['S']}  type: {fields['T']}  valid until: {fields['WAVE-UNTIL']}")
        print("Reminder: still check the BSSIDs actually seen before joining.")
    sys.exit(0 if result == VALID else 1)


def _read_text_arg(value: str) -> str:
    """A value given on the command line, or @file to read it from a file."""
    return Path(value[1:]).read_text(encoding="utf-8").strip() if value.startswith("@") else value.strip()


def _cmd_admin_request(a):
    """TEST HELPER: on a real admin this key is made in the phone's secure hardware (phase 3)."""
    out = Path(a.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    n = 1
    while (out / f"admin-{a.place}-{n}.private.pem").exists():
        n += 1
    path = out / f"admin-{a.place}-{n}.private.pem"
    key = ec.generate_private_key(ec.SECP256R1())
    try:
        request = make_admin_request(a.name, a.place, key.public_key())
    except (ValueError, TypeError) as exc:
        raise WaveError(str(exc)) from exc
    _write_private_file(path, key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                                serialization.NoEncryption()))
    print(f"Test admin key: {path}   <- test only; real admins keep theirs in the phone", file=sys.stderr)
    print(request)


def _cmd_certify_admin(a):
    try:
        request = parse_admin_request(_read_text_arg(a.request))
    except ValueError as exc:
        raise WaveError(str(exc)) from exc
    root_id, root_key = load_private_key(Path(a.key))
    try:
        cert = certify_admin(request, a.place, a.place_name, a.days, root_id, root_key)
    except ValueError as exc:
        raise WaveError(str(exc)) from exc
    _, info = verify_cert(cert, {root_id: root_key.public_key()}, at=dt.datetime.now(dt.timezone.utc))
    print(f"Certified {info['admin_name']} for {info['place_name']} ({info['place_id']}) until {info['expires_at']}",
          file=sys.stderr)
    if a.png:
        write_qr_png(cert, Path(a.png), f"WAVE admin: {info['admin_name']} - {info['place_name']}")
        print(f"Certificate QR: {a.png}  (the admin scans this in WAVE)", file=sys.stderr)
    print(cert)


def _cmd_verify_cert(a):
    at = dt.datetime.now(dt.timezone.utc)
    result, cert = verify_cert(_read_text_arg(a.cert), load_public_keys([Path(p) for p in a.pub]), at=at)
    print(f"Result: {result}")
    if "why" in cert:
        print(f"Why: {cert['why']}")
    elif cert:
        print(f"Admin: {cert['admin_name']}  place: {cert['place_name']} ({cert['place_id']})  "
              f"valid {cert['issued_at']} to {cert['expires_at']}  cert_id: {cert['cert_id']}")
    sys.exit(0 if result == VALID else 1)


def _load_admin_private_key(path: Path) -> ec.EllipticCurvePrivateKey:
    try:
        key = serialization.load_pem_private_key(path.read_bytes(), password=None)
    except (ValueError, TypeError) as exc:
        raise WaveError(f"could not load admin key ({exc})") from exc
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(key.curve, ec.SECP256R1):
        raise WaveError("admin key must be P-256")
    return key


def _cmd_sign_place_registry(a):
    """TEST HELPER: on a real admin the phone signs and uploads (phase 4)."""
    data = Path(a.registry).read_bytes()
    reg, errors, warnings = validate_registry(data)
    for w in warnings:
        print(f"warning: {w}", file=sys.stderr)
    if errors:
        raise WaveError("registry is invalid: " + "; ".join(errors))
    if "place_id" not in reg:
        raise WaveError("a place registry needs \"place_id\"")
    package = sign_place_registry(data, _load_admin_private_key(Path(a.admin_key)), _read_text_arg(a.cert))
    Path(a.out).write_text(json.dumps(package, indent=2) + "\n", encoding="utf-8")
    print(f"Package: {a.out}")


def _cmd_verify_package(a):
    package = json.loads(Path(a.package).read_text(encoding="utf-8"))
    result, reg = verify_package(package, load_public_keys([Path(p) for p in a.pub]))
    print(f"Result: {result}")
    if reg:
        print(f"Place: {reg['place_id']}  version: {reg['version']}  networks: {len(reg['networks'])}  "
              f"blocked: {len(reg.get('blocked', []))}")
    sys.exit(0 if result == VALID else 1)


def _cmd_test_vectors(a):
    vectors = make_test_vectors()
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(vectors, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Wrote {out}: {len(vectors['registry_cases'])} registry cases, {len(vectors['qr_cases'])} QR cases, "
          f"{len(vectors['cert_cases'])} certificate cases, {len(vectors['package_cases'])} package cases")


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description="WAVE offline signing tool", formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("keygen", help="create a new Ed25519 key pair")
    s.add_argument("--key-id", required=True, help="e.g. wave-2026-a")
    s.add_argument("--out", default="keys")
    s.add_argument("--no-passphrase", action="store_true", help="only for throwaway test keys")
    s.set_defaults(func=_cmd_keygen)

    s = sub.add_parser("sign-registry", help="validate and sign registry.json")
    s.add_argument("registry")
    s.add_argument("--key", required=True, help="keys/<key-id>.private.pem")
    s.set_defaults(func=_cmd_sign_registry)

    s = sub.add_parser("verify-registry", help="verify registry.json and its .sig")
    s.add_argument("registry")
    s.add_argument("--sig", help="default: <registry>.sig")
    s.add_argument("--pub", action="append", required=True, help="keys/<key-id>.public.json (repeatable)")
    s.set_defaults(func=_cmd_verify_registry)

    s = sub.add_parser("sign-qr", help="make a signed Wi-Fi QR code")
    s.add_argument("--key", required=True)
    s.add_argument("--ssid", required=True)
    s.add_argument("--type", required=True, choices=sorted(QR_TYPES))
    s.add_argument("--password", help="omit to be prompted (keeps it out of shell history)")
    s.add_argument("--hidden", action="store_true")
    s.add_argument("--until", required=True, help="last valid date, YYYY-MM-DD")
    s.add_argument("--registry", help="check the SSID/security against this registry first")
    s.add_argument("--allow-unregistered", action="store_true")
    s.add_argument("--png", help="write a QR image here")
    s.add_argument("--no-caption", action="store_true")
    s.add_argument("--show", action="store_true", help="print the QR string (includes the password)")
    s.set_defaults(func=_cmd_sign_qr)

    s = sub.add_parser("verify-qr", help="verify a QR string (or @file)")
    s.add_argument("qr")
    s.add_argument("--pub", action="append", required=True)
    s.add_argument("--registry")
    s.add_argument("--today", help="YYYYMMDD, for testing")
    s.set_defaults(func=_cmd_verify_qr)

    s = sub.add_parser("admin-request", help="TEST HELPER: make a test admin key and its request text")
    s.add_argument("--name", required=True, help="admin's name, e.g. 'Ana Cruz'")
    s.add_argument("--place", required=True, help="place id, e.g. batstateu")
    s.add_argument("--out-dir", default="keys")
    s.set_defaults(func=_cmd_admin_request)

    s = sub.add_parser("certify-admin", help="root-sign an admin's request: they may publish for one place")
    s.add_argument("--request", required=True, help="WAVEREQ:1:... text, or @file")
    s.add_argument("--place", required=True, help="place id; must match the request")
    s.add_argument("--place-name", required=True, help="e.g. 'Batangas State University'")
    s.add_argument("--days", type=int, default=365)
    s.add_argument("--key", required=True, help="keys/<root-key-id>.private.pem")
    s.add_argument("--png", help="write the certificate as a QR image here")
    s.set_defaults(func=_cmd_certify_admin)

    s = sub.add_parser("verify-cert", help="check an admin certificate (WAVECERT:1:... or @file)")
    s.add_argument("cert")
    s.add_argument("--pub", action="append", required=True, help="keys/<root-key-id>.public.json")
    s.set_defaults(func=_cmd_verify_cert)

    s = sub.add_parser("sign-place-registry", help="TEST HELPER: sign a place registry with a test admin key")
    s.add_argument("registry")
    s.add_argument("--admin-key", required=True)
    s.add_argument("--cert", required=True, help="WAVECERT:1:... text, or @file")
    s.add_argument("--out", required=True, help="package JSON file to write")
    s.set_defaults(func=_cmd_sign_place_registry)

    s = sub.add_parser("verify-package", help="check a place registry package from the root down")
    s.add_argument("package")
    s.add_argument("--pub", action="append", required=True, help="keys/<root-key-id>.public.json")
    s.set_defaults(func=_cmd_verify_package)

    s = sub.add_parser("test-vectors", help="write test vectors for the Kotlin verifier")
    s.add_argument("--out", default="test-vectors/test_vectors.json")
    s.set_defaults(func=_cmd_test_vectors)

    args = p.parse_args(argv)
    try:
        args.func(args)
    except WaveError as exc:
        print(f"error: {exc}", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
