"""The signed registry, served exactly as signed (CONTRACT section 2, Rule #1).

The server never parses and re-serializes registry.json for serving: the signature covers the exact file bytes.
Both files are re-read whenever their modification time or size changes, so re-signing needs no restart.
The server holds no keys and doesn't check the signature itself; the app does (with its built-in public keys).
"""
from __future__ import annotations

import base64
import binascii
import json
import os
import threading
from dataclasses import dataclass
from pathlib import Path

SIGNATURE_LEN = 64  # Ed25519


class RegistryUnavailable(Exception):
    """registry.json or its .sig is missing or unusable. The message is safe to show to clients."""


@dataclass(frozen=True)
class SignedRegistry:
    key_id: str
    registry_bytes: bytes
    signature: bytes
    #: "version" from inside the file, for /v1/health only. None if it can't be read.
    version: int | None


def b64url(data: bytes) -> str:
    """base64url without padding (CONTRACT section 1)."""
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _b64url_decode(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


class RegistryStore:
    def __init__(self, registry_file: Path, signature_file: Path) -> None:
        self.registry_file = registry_file
        self.signature_file = signature_file
        self._lock = threading.Lock()
        self._stamp: tuple | None = None
        self._cached: SignedRegistry | None = None

    def _stat(self) -> tuple:
        try:
            r, s = os.stat(self.registry_file), os.stat(self.signature_file)
        except FileNotFoundError as exc:
            which = "registry file" if Path(exc.filename) == self.registry_file else "signature file"
            raise RegistryUnavailable(f"{which} not found: {Path(exc.filename).name}") from None
        return (r.st_mtime_ns, r.st_size, s.st_mtime_ns, s.st_size)

    def get(self) -> SignedRegistry:
        """The current signed registry, reloaded if either file changed. Raises RegistryUnavailable."""
        with self._lock:
            stamp = self._stat()
            if self._cached is not None and stamp == self._stamp:
                return self._cached
            # Read, then check nothing changed while reading (a re-sign in progress); retry once if it did.
            for _ in range(2):
                loaded = self._load()
                after = self._stat()
                if after == stamp:
                    break
                stamp = after
            self._stamp, self._cached = stamp, loaded
            return loaded

    def _load(self) -> SignedRegistry:
        try:
            registry_bytes = self.registry_file.read_bytes()
            sig_text = self.signature_file.read_text(encoding="utf-8")
        except FileNotFoundError as exc:
            raise RegistryUnavailable(f"file not found: {Path(exc.filename).name}") from None

        try:
            sig = json.loads(sig_text)
            key_id, alg, sig_b64 = sig["key_id"], sig["alg"], sig["signature"]
        except (ValueError, KeyError, TypeError):
            raise RegistryUnavailable("signature file is not valid JSON with key_id, alg and signature") from None
        if alg != "Ed25519":
            raise RegistryUnavailable(f"signature file has unsupported alg {alg!r}")
        if not isinstance(key_id, str) or not key_id:
            raise RegistryUnavailable("signature file has no key_id")
        try:
            signature = _b64url_decode(sig_b64)
        except (binascii.Error, ValueError, TypeError):
            raise RegistryUnavailable("signature is not base64url") from None
        if len(signature) != SIGNATURE_LEN:
            raise RegistryUnavailable(f"signature must be {SIGNATURE_LEN} bytes, got {len(signature)}")

        return SignedRegistry(key_id, registry_bytes, signature, _version_of(registry_bytes))


def _version_of(registry_bytes: bytes) -> int | None:
    """Display-only peek at "version". Serving always uses the untouched bytes."""
    try:
        v = json.loads(registry_bytes).get("version")
        return v if isinstance(v, int) else None
    except (ValueError, AttributeError):
        return None
