"""WAVE server (docs/CONTRACT.md section 4). No private keys, no CORS.
GET /v1/registry, /v1/exit-path and /v1/health, plus place packages: place admins upload signed packages
(checked from the root key down) and phones list and download them. Packages are kept in Supabase Postgres
when DATABASE_URL is set, otherwise in memory.
(/v1/debug/client-ip exists only while WAVE_DEBUG_CLIENT_IP=1, for setting up a new host.)

Run from backend/:  uvicorn server.main:app --host 127.0.0.1 --port 8000
"""
from __future__ import annotations

import datetime as dt
import logging
from contextlib import asynccontextmanager

import anyio.to_thread
from anyio import CapacityLimiter
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool
from starlette.middleware.gzip import GZipMiddleware

from signing import wave_sign as ws

from . import places as pl
from .asn_db import AsnDatabase
from .client_ip import resolve
from .config import Settings
from .registry_store import RegistryStore, RegistryUnavailable, b64url

# Uvicorn only prints its own loggers, so the server's messages go through "uvicorn.error" (its main log).
log = logging.getLogger("uvicorn.error")


#: HTTP status for each upload result (CONTRACT §4).
UPLOAD_STATUS = {
    pl.STORED: 200,
    ws.MALFORMED: 400,
    pl.TOO_LARGE: 413,
    ws.UNKNOWN_KEY: 403, ws.BAD_SIGNATURE: 403, ws.NOT_YET_VALID: 403, ws.EXPIRED: 403, ws.WRONG_PLACE: 403,
    pl.STALE_VERSION: 409,
    pl.UPLOADS_OFF: 503, pl.STORAGE_UNAVAILABLE: 503,
}
NO_STORE = {"Cache-Control": "no-store"}
#: At most this many requests wait on the place database at once. They get their own threads, so a slow or
#: unreachable database can't use up the threads /v1/health and /v1/exit-path run on. It also stays under
#: Supabase's pooler client limit (15 on the free tier).
PLACE_DB_THREADS = 8


def _answer(result: str, **extra) -> JSONResponse:
    return JSONResponse({"result": result, **extra}, status_code=UPLOAD_STATUS.get(result, 400), headers=NO_STORE)


def _place_store(settings: Settings) -> pl.PlaceStore:
    if settings.database_url:
        from .places_pg import PostgresPlaceStore  # needs psycopg, only when a database is configured
        return PostgresPlaceStore(settings.database_url)
    log.warning("DATABASE_URL is not set: place packages are kept in memory only and lost on restart.")
    return pl.MemoryPlaceStore()


def create_app(settings: Settings | None = None, place_store: pl.PlaceStore | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    registry = RegistryStore(settings.registry_file, settings.signature_file)
    store = place_store if place_store is not None else _place_store(settings)
    root_keys = {kid: Ed25519PublicKey.from_public_bytes(raw) for kid, raw in settings.root_public_keys}
    if not root_keys:
        log.warning("WAVE_ROOT_PUBLIC_KEYS is not set: place uploads are off (%s).", pl.UPLOADS_OFF)
    limiter: list[CapacityLimiter] = []  # made inside the event loop, on first use

    async def in_db_thread(fn, *args):
        if not limiter:
            limiter.append(CapacityLimiter(PLACE_DB_THREADS))
        return await anyio.to_thread.run_sync(fn, *args, limiter=limiter[0])

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        # Load the ASN dataset once. Without it the server still runs; /v1/exit-path answers 503.
        try:
            app.state.asn_db = AsnDatabase.load(settings.asn_db)
        except (OSError, EOFError, ValueError) as exc:
            app.state.asn_db = None
            log.warning("ASN database not loaded (%s): run server/update_asn_db.py. /v1/exit-path will return 503.", exc)
        yield

    # No interactive docs / OpenAPI schema: the only client is the Android app.
    app = FastAPI(title="WAVE server", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.state.settings = settings
    # Place lists can be large; answers over 1 KiB are gzip-compressed when the client asks (Android always does).
    app.add_middleware(GZipMiddleware, minimum_size=1024)
    app.state.asn_db = None

    @app.get("/v1/health")
    def health() -> dict:
        try:
            reg = registry.get()
            registry_loaded, registry_version = True, reg.version
        except RegistryUnavailable:
            registry_loaded, registry_version = False, None
        asn_db: AsnDatabase | None = app.state.asn_db
        return {
            "status": "ok",
            "registry_loaded": registry_loaded,
            "registry_version": registry_version,
            "asn_db_loaded": asn_db is not None,
            "asn_db_date": asn_db.date if asn_db else None,
            "places_storage": store.kind,
        }

    @app.post("/v1/places/{place_id}/registry")
    async def upload_place_registry(place_id: str, request: Request) -> JSONResponse:
        if not root_keys:
            return _answer(pl.UPLOADS_OFF)
        declared = request.headers.get("content-length", "")
        if declared.isdigit() and int(declared) > pl.MAX_UPLOAD_BYTES:
            return _answer(pl.TOO_LARGE)
        body = bytearray()
        async for chunk in request.stream():  # stop reading as soon as it is too big
            body += chunk
            if len(body) > pl.MAX_UPLOAD_BYTES:
                return _answer(pl.TOO_LARGE)
        now = dt.datetime.now(dt.timezone.utc)
        result, record = await run_in_threadpool(pl.check_upload, place_id, bytes(body), root_keys, now)
        if record is None:
            return _answer(result)
        try:
            stored = await in_db_thread(store.put, record)
        except pl.StorageUnavailable as exc:
            log.warning("place storage: %s", exc)
            return _answer(pl.STORAGE_UNAVAILABLE)
        if not stored:
            return _answer(pl.STALE_VERSION)
        log.info("Stored place %s version %d (cert %s)", record.place_id, record.version, record.cert_id)
        return _answer(pl.STORED, place_id=record.place_id, version=record.version)

    @app.get("/v1/places")
    async def list_places() -> JSONResponse:
        try:
            records = await in_db_thread(store.places)
        except pl.StorageUnavailable as exc:
            log.warning("place storage: %s", exc)
            return _answer(pl.STORAGE_UNAVAILABLE)
        return JSONResponse({"places": [r.summary() for r in records]}, headers=NO_STORE)

    @app.get("/v1/places/{place_id}/registry")
    async def get_place_registry(place_id: str) -> JSONResponse:
        try:
            record = await in_db_thread(store.get, place_id)
        except pl.StorageUnavailable as exc:
            log.warning("place storage: %s", exc)
            return _answer(pl.STORAGE_UNAVAILABLE)
        if record is None:
            return JSONResponse({"result": "NOT_FOUND"}, status_code=404, headers=NO_STORE)
        return JSONResponse(record.package, headers=NO_STORE)

    @app.get("/v1/registry")
    def get_registry() -> dict:
        try:
            reg = registry.get()
        except RegistryUnavailable as exc:
            raise HTTPException(status_code=503, detail=f"Registry not available: {exc}") from None
        return {
            "key_id": reg.key_id,
            "registry_b64url": b64url(reg.registry_bytes),
            "signature_b64url": b64url(reg.signature),
        }

    @app.get("/v1/exit-path")
    def exit_path(request: Request, response: Response) -> dict:
        # Every answer is about this one connection at this moment: never cache it anywhere.
        response.headers["Cache-Control"] = "no-store"
        db: AsnDatabase | None = app.state.asn_db
        if db is None:
            raise HTTPException(status_code=503, detail="ASN database not loaded", headers={"Cache-Control": "no-store"})

        checked_at = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
        unknown = {"public_ip": None, "asn": None, "as_name": None, "checked_at": checked_at}

        client = resolve(request, settings)
        if client.ip is None:
            return {**unknown, "note": client.problem}
        if not client.ip.is_global:  # 127.x, 10.x, 172.16-31.x, 192.168.x, 100.64/10, fc00::/7, fe80::/10, ...
            return {
                **unknown,
                "note": "The server sees a private or local address, not your public one, so it can't tell "
                "which provider your traffic exits through. Reach the server over the internet (e.g. a tunnel).",
            }
        info = db.lookup(client.ip)
        if info is None:
            return {**unknown, "public_ip": str(client.ip), "note": "No ASN found for this address."}
        return {"public_ip": str(client.ip), "asn": info.asn, "as_name": info.as_name, "checked_at": checked_at}

    if settings.debug_client_ip:
        log.warning("WAVE_DEBUG_CLIENT_IP is on: /v1/debug/client-ip shows forwarding headers. Turn it off when done.")

        @app.get("/v1/debug/client-ip")
        def debug_client_ip(request: Request, response: Response) -> dict:
            # Only for choosing WAVE_TRUSTED_PROXY settings on a new host; it echoes the caller's own headers.
            response.headers["Cache-Control"] = "no-store"
            client = resolve(request, settings)
            return {
                "peer": request.client.host if request.client else None,
                "resolved_ip": str(client.ip) if client.ip else None,
                "problem": client.problem,
                "mode": settings.trusted_proxy,
                "headers": {name: request.headers.getlist(name) for name in DEBUG_HEADERS},
            }

    return app


#: Forwarding headers shown by /v1/debug/client-ip.
DEBUG_HEADERS = ("x-forwarded-for", "cf-connecting-ip", "true-client-ip", "x-real-ip", "forwarded")

app = create_app()
