"""Server settings, read from environment variables. Defaults work when started from backend/."""
from __future__ import annotations

import ipaddress
import os
from dataclasses import dataclass, field
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]

PROXY_MODES = ("none", "cloudflare", "forwarded")


class ConfigError(ValueError):
    """A setting is invalid; the server refuses to start rather than guess."""


@dataclass(frozen=True)
class Settings:
    #: registry.json to serve; its signature is the same path + ".sig".
    registry_file: Path = BACKEND_DIR / "registry" / "registry.json"
    #: IPtoASN ip2asn-combined.tsv.gz.
    asn_db: Path = BACKEND_DIR / "server" / "data" / "ip2asn-combined.tsv.gz"
    #: How the client IP is found: "none" (socket peer only), "cloudflare", "forwarded".
    trusted_proxy: str = "none"
    #: Peers allowed to set X-Forwarded-For (mode "forwarded" only).
    trusted_proxy_ips: frozenset[str] = field(default_factory=frozenset)

    def __post_init__(self) -> None:
        if self.trusted_proxy not in PROXY_MODES:
            raise ConfigError(f"WAVE_TRUSTED_PROXY must be one of {', '.join(PROXY_MODES)} (got {self.trusted_proxy!r})")
        if self.trusted_proxy == "forwarded" and not self.trusted_proxy_ips:
            raise ConfigError("WAVE_TRUSTED_PROXY=forwarded needs WAVE_TRUSTED_PROXY_IPS (comma-separated proxy addresses)")

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
