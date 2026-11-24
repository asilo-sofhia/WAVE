"""Place packages in Supabase Postgres (used when DATABASE_URL is set).

One row per place: its latest package, exactly as uploaded. Row-level security is on with no policies, so
Supabase's public API (anon key) can't read or write the table; the server's own connection still can.
Each call opens one short connection: uploads are rare and phones sync a few times a day.
"""
from __future__ import annotations

import threading

from .places import PlaceRecord, StorageUnavailable

SCHEMA = """
create table if not exists wave_places (
  place_id text primary key,
  place_name text not null,
  version bigint not null,
  cert_id text not null,
  admin_name text not null,
  registry_b64url text not null,
  signature_b64url text not null,
  cert text not null,
  updated_at timestamptz not null
);
alter table wave_places enable row level security;
"""

COLUMNS = ("place_id", "place_name", "version", "cert_id", "admin_name",
           "registry_b64url", "signature_b64url", "cert", "updated_at")

# One statement, so two uploads at once can never store a lower version over a higher one.
PUT = f"""
insert into wave_places ({", ".join(COLUMNS)}) values ({", ".join(["%s"] * len(COLUMNS))})
on conflict (place_id) do update set {", ".join(f"{c} = excluded.{c}" for c in COLUMNS[1:])}
  where wave_places.version < excluded.version
returning place_id
"""

SELECT = f"""select {", ".join(COLUMNS[:-1])}, to_char(updated_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
from wave_places"""


class PostgresPlaceStore:
    kind = "postgres"

    def __init__(self, url: str) -> None:
        import psycopg  # only needed when a database is configured

        self._psycopg = psycopg
        self._url = url
        self._schema_lock = threading.Lock()
        self._schema_ready = False

    def _connect(self):
        # prepare_threshold=None: Supabase's pooler can't use prepared statements.
        return self._psycopg.connect(self._url, connect_timeout=10, autocommit=True, prepare_threshold=None)

    def _run(self, work):
        """Run work(conn) on a fresh connection. Any database error becomes StorageUnavailable without the URL."""
        try:
            with self._connect() as conn:
                if not self._schema_ready:
                    with self._schema_lock:
                        if not self._schema_ready:
                            conn.execute(SCHEMA)
                            self._schema_ready = True
                return work(conn)
        except self._psycopg.Error as exc:
            # str(exc) can name the host and user, so only the error type goes out.
            raise StorageUnavailable(f"database error ({type(exc).__name__})") from None

    def put(self, record: PlaceRecord) -> bool:
        p = record.package
        values = (record.place_id, record.place_name, record.version, record.cert_id, record.admin_name,
                  p["registry_b64url"], p["signature_b64url"], p["cert"], record.updated_at)
        return self._run(lambda conn: conn.execute(PUT, values).fetchone() is not None)

    def places(self) -> list[PlaceRecord]:
        return self._run(lambda conn: [_record(r) for r in conn.execute(SELECT + " order by place_id").fetchall()])

    def get(self, place_id: str) -> PlaceRecord | None:
        row = self._run(lambda conn: conn.execute(SELECT + " where place_id = %s", (place_id,)).fetchone())
        return _record(row) if row else None


def _record(row) -> PlaceRecord:
    place_id, place_name, version, cert_id, admin_name, registry, signature, cert, updated_at = row
    return PlaceRecord(
        place_id=place_id, place_name=place_name, version=version, cert_id=cert_id, admin_name=admin_name,
        package={"place_id": place_id, "registry_b64url": registry, "signature_b64url": signature, "cert": cert},
        updated_at=updated_at,
    )
