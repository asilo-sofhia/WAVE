"""WAVE server (docs/CONTRACT.md section 4). Read-only: three GET endpoints, no database, no keys, no CORS.

Run from backend/:  uvicorn server.main:app --host 127.0.0.1 --port 8000
"""
from __future__ import annotations

import datetime as dt
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request, Response

from .asn_db import AsnDatabase
from .client_ip import resolve
from .config import Settings
from .registry_store import RegistryStore, RegistryUnavailable, b64url

# Uvicorn only prints its own loggers, so the server's messages go through "uvicorn.error" (its main log).
log = logging.getLogger("uvicorn.error")


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    registry = RegistryStore(settings.registry_file, settings.signature_file)

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
        }

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

    return app


app = create_app()
