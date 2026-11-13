"""WAVE server (docs/CONTRACT.md section 4). Read-only: three GET endpoints, no database, no keys, no CORS.

Run from backend/:  uvicorn server.main:app --host 127.0.0.1 --port 8000
"""
from __future__ import annotations

import logging

from fastapi import FastAPI, HTTPException

from .config import Settings
from .registry_store import RegistryStore, RegistryUnavailable, b64url

log = logging.getLogger("wave.server")


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    registry = RegistryStore(settings.registry_file, settings.signature_file)

    # No interactive docs / OpenAPI schema: the only client is the Android app.
    app = FastAPI(title="WAVE server", docs_url=None, redoc_url=None, openapi_url=None)
    app.state.settings = settings

    @app.get("/v1/health")
    def health() -> dict:
        try:
            reg = registry.get()
            registry_loaded, registry_version = True, reg.version
        except RegistryUnavailable:
            registry_loaded, registry_version = False, None
        return {
            "status": "ok",
            "registry_loaded": registry_loaded,
            "registry_version": registry_version,
            "asn_db_loaded": False,
            "asn_db_date": None,
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

    return app


app = create_app()
