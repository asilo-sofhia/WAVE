"""Place registry packages uploaded by place admins (CONTRACT §4, §7).

The upload check is phase 1's verify_package, run with the server's clock. The store keeps only each place's
latest package, exactly as uploaded. Phones check every package themselves; this check only keeps junk out.
"""
from __future__ import annotations

import datetime as dt
import json
import threading
from dataclasses import dataclass
from typing import Protocol

from signing import wave_sign as ws

MAX_UPLOAD_BYTES = 256 * 1024
#: Highest registry version accepted (fits a Postgres bigint and a JSON number on every client).
MAX_VERSION = 2**53 - 1

STORED = "STORED"
STALE_VERSION = "STALE_VERSION"
TOO_LARGE = "TOO_LARGE"
UPLOADS_OFF = "UPLOADS_OFF"
STORAGE_UNAVAILABLE = "STORAGE_UNAVAILABLE"


class StorageUnavailable(Exception):
    """The place storage can't be reached. The message is safe to log: it never holds the database URL."""


@dataclass(frozen=True)
class PlaceRecord:
    place_id: str
    place_name: str
    version: int
    cert_id: str
    admin_name: str
    #: Exactly the four package fields (ws.PACKAGE_FIELDS), as uploaded.
    package: dict[str, str]
    #: ISO UTC with "Z".
    updated_at: str

    def summary(self) -> dict:
        return {"place_id": self.place_id, "place_name": self.place_name, "version": self.version,
                "updated_at": self.updated_at}


class PlaceStore(Protocol):
    kind: str

    def put(self, record: PlaceRecord) -> bool:
        """True if stored; False if the stored version is the same or higher."""

    def places(self) -> list[PlaceRecord]:
        """Every place's latest record, sorted by place_id."""

    def get(self, place_id: str) -> PlaceRecord | None: ...


class MemoryPlaceStore:
    """For tests and local runs. Everything is lost when the server stops."""

    kind = "memory"

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._records: dict[str, PlaceRecord] = {}

    def put(self, record: PlaceRecord) -> bool:
        with self._lock:
            old = self._records.get(record.place_id)
            if old is not None and old.version >= record.version:
                return False
            self._records[record.place_id] = record
            return True

    def places(self) -> list[PlaceRecord]:
        with self._lock:
            return [self._records[k] for k in sorted(self._records)]

    def get(self, place_id: str) -> PlaceRecord | None:
        with self._lock:
            return self._records.get(place_id)


def check_upload(path_place_id: str, body: bytes, root_keys: dict, now: dt.datetime) -> tuple[str, PlaceRecord | None]:
    """(VALID, record) or (failure result, None). Never raises: uploads come from anyone."""
    try:
        return _check_upload(path_place_id, body, root_keys, now)
    except Exception:  # noqa: BLE001 - a crash must not become a server error
        return ws.MALFORMED, None


def _check_upload(path_place_id: str, body: bytes, root_keys: dict, now: dt.datetime) -> tuple[str, PlaceRecord | None]:
    try:
        raw = json.loads(body.decode("utf-8"))
    except (ValueError, RecursionError):  # UnicodeDecodeError and JSONDecodeError are ValueErrors
        return ws.MALFORMED, None
    if not isinstance(raw, dict):
        return ws.MALFORMED, None
    package = {f: raw.get(f) for f in ws.PACKAGE_FIELDS}  # extra fields are dropped
    result, reg = ws.verify_package(package, root_keys, now=now)
    if result != ws.VALID:
        return result, None
    if package["place_id"] != path_place_id:
        return ws.WRONG_PLACE, None
    if reg["version"] > MAX_VERSION:
        return ws.MALFORMED, None
    _, cert = ws.verify_cert(package["cert"], root_keys, at=now)
    return ws.VALID, PlaceRecord(
        place_id=reg["place_id"], place_name=cert["place_name"], version=reg["version"], cert_id=cert["cert_id"],
        admin_name=cert["admin_name"], package=package,
        updated_at=now.astimezone(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    )
