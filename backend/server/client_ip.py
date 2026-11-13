"""Which IP address is the phone really coming from? Security-critical for the exit-path check.

A phone (or an attacker) can send any header it likes, so forwarding headers are only believed when the
request came from a proxy we trust (WAVE_TRUSTED_PROXY):

- "none" (default): the socket's peer address only. Every forwarding header is ignored.
- "cloudflare": CF-Connecting-IP, but ONLY when the peer is loopback (cloudflared runs on this machine).
- "forwarded": the right-most X-Forwarded-For entry (the one our proxy added), ONLY when the peer is in
  WAVE_TRUSTED_PROXY_IPS. Left-hand entries are client-supplied and never used.

When a trusted proxy doesn't say who the client is, the answer is "unknown" - never the proxy's own address,
which would report the proxy's provider instead of the phone's.
"""
from __future__ import annotations

import ipaddress
from dataclasses import dataclass

from starlette.requests import Request

from .config import Settings

IP = ipaddress.IPv4Address | ipaddress.IPv6Address


@dataclass(frozen=True)
class ClientAddress:
    ip: IP | None
    #: Why [ip] is None, safe to show to the client.
    problem: str | None = None


def parse_ip(text: str | None) -> IP | None:
    """A bare IP address (no port, no brackets); IPv4-mapped IPv6 is unwrapped to IPv4. None if invalid."""
    if not text:
        return None
    try:
        ip = ipaddress.ip_address(text.strip())
    except ValueError:
        return None
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None:
        return ip.ipv4_mapped
    return ip


def resolve(request: Request, settings: Settings) -> ClientAddress:
    peer = parse_ip(request.client.host if request.client else None)
    if peer is None:
        return ClientAddress(None, "The server couldn't read the connection's address.")

    if settings.trusted_proxy == "cloudflare" and peer.is_loopback:
        forwarded = parse_ip(request.headers.get("cf-connecting-ip"))
        if forwarded is None:
            return ClientAddress(None, "The tunnel didn't say which address the request came from.")
        return ClientAddress(forwarded)

    if settings.trusted_proxy == "forwarded" and str(peer) in settings.trusted_proxy_ips:
        entries = [e for value in request.headers.getlist("x-forwarded-for") for e in value.split(",")]
        forwarded = parse_ip(entries[-1]) if entries else None  # right-most = added by our proxy
        if forwarded is None:
            return ClientAddress(None, "The proxy didn't say which address the request came from.")
        return ClientAddress(forwarded)

    return ClientAddress(peer)  # mode "none", or an untrusted peer: headers are ignored
