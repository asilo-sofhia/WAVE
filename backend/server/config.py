"""Server settings, read from environment variables. Defaults work when started from backend/."""
from __future__ import annotations

import base64
import binascii
import ipaddress
import os
import re
from dataclasses import dataclass, field
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]

PROXY_MODES = ("none", "cloudflare", "forwarded", "header")
KEY_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,31}$")


class ConfigError(ValueError):
    """A setting is invalid; the server refuses to start rather than guess."""


@dataclass(frozen=True)
class Settings:
    #: registry.json to serve; its signature is the same path + ".sig".
    registry_file: Path = BACKEND_DIR / "registry" / "registry.json"
    #: IPtoASN ip2asn-combined.tsv.gz.
    asn_db: Path = BACKEND_DIR / "server" / "data" / "ip2asn-combined.tsv.gz"
    #: How the client IP is found: "none" (socket peer only), "cloudflare", "forwarded", "header".
    trusted_proxy: str = "none"
    #: Peers allowed to set X-Forwarded-For (mode "forwarded" only).
    trusted_proxy_ips: frozenset[str] = field(default_factory=frozenset)
    #: Mode "header": the one header that names the client (lower case), e.g. "true-client-ip".
    client_ip_header: str | None = None
    #: Mode "header": the header is believed only when the socket peer is inside one of these ranges.
    trusted_proxy_cidrs: tuple[ipaddress.IPv4Network | ipaddress.IPv6Network, ...] = ()
    #: Mode "header": which comma-separated entry to use, counted from the right (1 = right-most).
    client_ip_from_right: int = 1
    #: Adds GET /v1/debug/client-ip, which shows the forwarding headers a host sends. Only while setting up a host.
    debug_client_ip: bool = False
    #: Supabase Postgres connection string for place packages. Secret: never shown in repr, logs or answers.
    #: Unset: places are kept in memory only (lost on restart).
    database_url: str | None = field(default=None, repr=False)
    #: Root public keys that may certify place admins (CONTRACT §6), as (key_id, 32 raw Ed25519 bytes).
    #: Empty: uploads are off.
    root_public_keys: tuple[tuple[str, bytes], ...] = ()

    def __post_init__(self) -> None:
        if self.trusted_proxy not in PROXY_MODES:
            raise ConfigError(f"WAVE_TRUSTED_PROXY must be one of {', '.join(PROXY_MODES)} (got {self.trusted_proxy!r})")
        if self.trusted_proxy == "forwarded" and not self.trusted_proxy_ips:
            raise ConfigError("WAVE_TRUSTED_PROXY=forwarded needs WAVE_TRUSTED_PROXY_IPS (comma-separated proxy addresses)")
        if self.trusted_proxy == "header":
            if not self.client_ip_header:
                raise ConfigError("WAVE_TRUSTED_PROXY=header needs WAVE_CLIENT_IP_HEADER (e.g. True-Client-IP)")
            if not self.trusted_proxy_cidrs:
                raise ConfigError("WAVE_TRUSTED_PROXY=header needs WAVE_TRUSTED_PROXY_CIDRS (the proxy's address ranges)")
        if self.client_ip_from_right < 1:
            raise ConfigError("WAVE_CLIENT_IP_FROM_RIGHT must be 1 or more")

    @property
    def signature_file(self) -> Path:
        return self.registry_file.with_name(self.registry_file.name + ".sig")

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> "Settings":
        env = dict(os.environ if env is None else env)
        kwargs: dict = {}
        if env.get("WAVE_REGISTRY_FILE"):
            kwargs["registry_file"] = _path(env["WAVE_REGISTRY_FILE"])
        if env.get("WAVE_ASN_DB"):
            kwargs["asn_db"] = _path(env["WAVE_ASN_DB"])
        if env.get("WAVE_TRUSTED_PROXY"):
            kwargs["trusted_proxy"] = env["WAVE_TRUSTED_PROXY"].strip().lower()
        if env.get("WAVE_TRUSTED_PROXY_IPS"):
            kwargs["trusted_proxy_ips"] = _ip_set(env["WAVE_TRUSTED_PROXY_IPS"])
        if env.get("WAVE_DEBUG_CLIENT_IP"):
            kwargs["debug_client_ip"] = env["WAVE_DEBUG_CLIENT_IP"].strip().lower() in ("1", "true", "yes")
        if env.get("WAVE_CLIENT_IP_HEADER"):
            kwargs["client_ip_header"] = env["WAVE_CLIENT_IP_HEADER"].strip().lower()
        if env.get("WAVE_TRUSTED_PROXY_CIDRS"):
            kwargs["trusted_proxy_cidrs"] = _cidrs(env["WAVE_TRUSTED_PROXY_CIDRS"])
        if env.get("WAVE_CLIENT_IP_FROM_RIGHT"):
            try:
                kwargs["client_ip_from_right"] = int(env["WAVE_CLIENT_IP_FROM_RIGHT"].strip())
            except ValueError:
                raise ConfigError("WAVE_CLIENT_IP_FROM_RIGHT must be a whole number (1 = right-most)") from None
        if env.get("DATABASE_URL", "").strip():
            kwargs["database_url"] = env["DATABASE_URL"].strip()
        if env.get("WAVE_ROOT_PUBLIC_KEYS", "").strip():
            kwargs["root_public_keys"] = _root_keys(env["WAVE_ROOT_PUBLIC_KEYS"])
        return cls(**kwargs)


def _path(value: str) -> Path:
    """Relative paths are taken from backend/, so the defaults and overrides behave the same everywhere."""
    p = Path(value).expanduser()
    return p if p.is_absolute() else (BACKEND_DIR / p).resolve()


def _ip_set(value: str) -> frozenset[str]:
    out = set()
    for part in value.split(","):
        part = part.strip()
        if not part:
            continue
        try:
            out.add(str(ipaddress.ip_address(part)))  # normalised form, e.g. "::1"
        except ValueError as exc:
            raise ConfigError(f"WAVE_TRUSTED_PROXY_IPS: {part!r} is not an IP address") from exc
    return frozenset(out)


def _cidrs(value: str) -> tuple[ipaddress.IPv4Network | ipaddress.IPv6Network, ...]:
    out = []
    for part in value.split(","):
        part = part.strip()
        if not part:
            continue
        try:
            out.append(ipaddress.ip_network(part, strict=False))
        except ValueError as exc:
            raise ConfigError(f"WAVE_TRUSTED_PROXY_CIDRS: {part!r} is not an address range like 10.0.0.0/8") from exc
    return tuple(out)


def _root_keys(value: str) -> tuple[tuple[str, bytes], ...]:
    """WAVE_ROOT_PUBLIC_KEYS: "key_id:public_key_b64url[,key_id:public_key_b64url]" (from keys/<id>.public.json)."""
    out = []
    for part in value.split(","):
        part = part.strip()
        if not part:
            continue
        key_id, sep, key = part.partition(":")
        key_id, key = key_id.strip(), key.strip()
        if not sep or not KEY_ID_RE.match(key_id):
            raise ConfigError("WAVE_ROOT_PUBLIC_KEYS: each entry must be key_id:public_key_b64url")
        try:
            raw = base64.urlsafe_b64decode(key + "=" * (-len(key) % 4))
        except (binascii.Error, ValueError):
            raw = b""
        if len(raw) != 32:
            raise ConfigError(f"WAVE_ROOT_PUBLIC_KEYS: the key for {key_id!r} is not a 32-byte Ed25519 public key")
        out.append((key_id, raw))
    return tuple(out)
