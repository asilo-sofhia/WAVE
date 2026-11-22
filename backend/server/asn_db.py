"""Offline IP-to-ASN lookup from the IPtoASN dataset (ip2asn-combined.tsv.gz, PDDL).

Loaded once at startup into sorted, non-overlapping ranges per IP family; each lookup is a binary search
(bisect). No network calls per request. Rows with ASN 0 ("Not routed") are skipped, so an address there
simply has no answer - the server never guesses.

Columns: range_start, range_end, AS_number, country_code, AS_description (tab-separated).
"""
from __future__ import annotations

import bisect
import datetime as dt
import gzip
import ipaddress
import logging
import socket
import time
from array import array
from dataclasses import dataclass
from pathlib import Path

# Uvicorn only prints its own loggers, so the server's messages go through "uvicorn.error" (its main log).
log = logging.getLogger("uvicorn.error")


@dataclass(frozen=True)
class AsnInfo:
    asn: int
    as_name: str


class _Family:
    """Sorted ranges for one IP family. IPv4 uses compact 32-bit arrays; IPv6 needs Python ints."""

    def __init__(self, ipv4: bool) -> None:
        self._rows: list[tuple[int, int, int, int]] = []  # (start, end, asn, name index) while loading
        self.ipv4 = ipv4
        self.starts: array | list[int] = []
        self.ends: array | list[int] = []
        self.asns = array("I")
        self.names = array("I")

    def add(self, start: int, end: int, asn: int, name_idx: int) -> None:
        self._rows.append((start, end, asn, name_idx))

    def freeze(self) -> None:
        self._rows.sort()
        if self.ipv4:
            self.starts, self.ends = array("I"), array("I")
        for start, end, asn, name_idx in self._rows:
            self.starts.append(start)
            self.ends.append(end)
            self.asns.append(asn)
            self.names.append(name_idx)
        self._rows = []

    def find(self, ip: int) -> tuple[int, int] | None:
        i = bisect.bisect_right(self.starts, ip) - 1
        if i >= 0 and ip <= self.ends[i]:
            return self.asns[i], self.names[i]
        return None

    def __len__(self) -> int:
        return len(self.asns)


class AsnDatabase:
    def __init__(self) -> None:
        self._v4 = _Family(ipv4=True)
        self._v6 = _Family(ipv4=False)
        self._names: list[str] = []
        #: Date of the dataset file (when it was downloaded), for /v1/health.
        self.date: str | None = None

    @property
    def ranges(self) -> int:
        return len(self._v4) + len(self._v6)

    @classmethod
    def load(cls, path: Path) -> "AsnDatabase":
        """Raises OSError if the file is missing or unreadable, ValueError if it has no usable rows."""
        started = time.monotonic()
        db = cls()
        name_index: dict[str, int] = {}
        skipped = 0
        with gzip.open(path, "rt", encoding="utf-8", errors="replace") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) < 5:
                    skipped += 1
                    continue
                start_s, end_s, asn_s, _country, name = parts[:5]
                if asn_s == "0":
                    continue  # "Not routed"
                try:
                    asn = int(asn_s)
                    family = socket.AF_INET6 if ":" in start_s else socket.AF_INET
                    start = int.from_bytes(socket.inet_pton(family, start_s), "big")
                    end = int.from_bytes(socket.inet_pton(family, end_s), "big")
                except (ValueError, OSError):  # inet_pton raises OSError on a bad address
                    skipped += 1
                    continue
                if end < start:
                    skipped += 1
                    continue
                idx = name_index.setdefault(name, len(db._names))
                if idx == len(db._names):
                    db._names.append(name)
                (db._v4 if family == socket.AF_INET else db._v6).add(start, end, asn, idx)
        db._v4.freeze()
        db._v6.freeze()
        if db.ranges == 0:
            raise ValueError(f"no usable ranges in {path.name}")
        db.date = dt.datetime.fromtimestamp(path.stat().st_mtime, tz=dt.timezone.utc).date().isoformat()
        log.info(
            "ASN database loaded: %d IPv4 + %d IPv6 ranges (%d bad rows skipped) in %.1fs",
            len(db._v4), len(db._v6), skipped, time.monotonic() - started,
        )
        return db

    def lookup(self, ip: ipaddress.IPv4Address | ipaddress.IPv6Address) -> AsnInfo | None:
        found = (self._v4 if ip.version == 4 else self._v6).find(int(ip))
        if found is None:
            return None
        asn, name_idx = found
        return AsnInfo(asn=asn, as_name=self._names[name_idx])
