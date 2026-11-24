# Phase 2: Server Storage + Place Endpoints Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The server accepts a place admin's signed package, checks it from the root down, stores it in Supabase Postgres, and serves every place's latest package.

**Architecture:** A new `server/places.py` holds the upload check (phase 1's `verify_package` with `now`) and a `PlaceStore` interface with an in-memory store (tests, local runs). `server/places_pg.py` is the Postgres store, used when `DATABASE_URL` is set. `main.py` adds three endpoints. The existing `/v1/registry` stays unchanged.

**Tech Stack:** Python 3.13, FastAPI, `cryptography`, new `psycopg[binary]>=3.2` (agreed in the spec), Supabase Postgres, pytest.

**Spec:** `docs/superpowers/specs/2026-11-24-multi-place-admins.md` (phase 2 row; package rules 1–5 and 7; CONTRACT §6–7)

## Global Constraints

- The server holds **no private keys**: only root **public** keys, from `WAVE_ROOT_PUBLIC_KEYS`.
- `DATABASE_URL` lives only in Render's environment. It never appears in Git, logs, error responses or `repr(Settings)`.
- No accounts or logins: the certificate chain is the only proof.
- Packages are stored and served **exactly as uploaded**: the four string fields, never re-encoded.
- Work from `backend/`. Run tests with `python -m pytest -q`; all existing tests keep passing (99 passed, 2 skipped at the start).
- Each upload result is a single word, shown by the app in phase 4: `STORED`, `MALFORMED`, `TOO_LARGE`, `UNKNOWN_KEY`, `BAD_SIGNATURE`, `NOT_YET_VALID`, `EXPIRED`, `WRONG_PLACE`, `STALE_VERSION`, `UPLOADS_OFF`, `STORAGE_UNAVAILABLE`.
- Branch: `feature/server-places`, on top of `feature/admin-certs`.

## Review Focus

1. **A huge or endless upload body** must get 413 `TOO_LARGE` after at most `MAX_UPLOAD_BYTES` have been read, never fill memory. → Task 2 test `test_upload_too_large_is_413`.
2. **Junk bodies** (not JSON, a JSON list, missing or non-string fields, a 5000-digit version) must get 400 `MALFORMED`, never 500. → Task 1 `test_check_upload_junk_is_malformed`, Task 2 `test_upload_junk_is_400`.
3. **The same or a lower version again** must get 409 `STALE_VERSION`, and the stored package stays as it was. With Postgres this is atomic (one `INSERT … ON CONFLICT … WHERE`). → Task 1 store contract tests, which also run on Postgres in Task 3.
4. **Supabase's public API** must not be able to read or write the table: row-level security on, with no policies. → Task 3 `test_pg_table_has_row_level_security`.
5. **When the database is down,** the server must still start. `/v1/health` still answers, uploads and downloads give 503 `STORAGE_UNAVAILABLE`, and the response never contains the connection string. → Task 2 `test_storage_down_is_503_without_secrets`.

---

### Task 1: Upload check and in-memory store

**Files:**
- Create: `backend/signing/__init__.py` (empty: makes `from signing import wave_sign` work when uvicorn runs from `backend/`)
- Create: `backend/server/places.py`
- Test: `backend/tests/test_places.py`

**Interfaces:**
- Consumes (phase 1, `signing/wave_sign.py`):
  - `verify_package(package, root_keys, now) -> (result, registry | None)`
  - `verify_cert(text, root_keys, at) -> (result, cert)`
  - result constants `VALID`, `MALFORMED`, `WRONG_PLACE`, …
  - `PACKAGE_FIELDS`
  - `PLACE_ID_RE`
- Produces, in `server/places.py`:
  - **Constants:**
    - `MAX_UPLOAD_BYTES = 256 * 1024`
    - `MAX_VERSION = 2**53 - 1`
    - `STORED`, `STALE_VERSION`, `TOO_LARGE`, `UPLOADS_OFF`, `STORAGE_UNAVAILABLE`: strings, each equal to its own name.
  - **`PlaceRecord`**, a frozen dataclass:
    - fields: `place_id: str`, `place_name: str`, `version: int`, `cert_id: str`, `admin_name: str`, `package: dict[str, str]` (exactly the four `PACKAGE_FIELDS`), `updated_at: str` (ISO UTC with `Z`);
    - `summary() -> dict`: returns `{"place_id", "place_name", "version", "updated_at"}`.
  - **`StorageUnavailable(Exception)`**: its message is safe to log, never the URL.
  - **`PlaceStore` (Protocol):**
    - `put(record) -> bool`: True if stored; False if the stored version is ≥ `record.version`.
    - `places() -> list[PlaceRecord]`: sorted by `place_id`.
    - `get(place_id) -> PlaceRecord | None`
    - `kind: str`: `"memory"` or `"postgres"`.
    - Any method may raise `StorageUnavailable`.
  - **`MemoryPlaceStore()`**: implements `PlaceStore` with a dict and a `threading.Lock`.
  - **`check_upload(path_place_id: str, body: bytes, root_keys: dict, now: datetime) -> tuple[str, PlaceRecord | None]`:** never raises. Returns `(VALID, record)` or `(failure, None)`.

- [ ] **Step 1: Write the failing tests** in `tests/test_places.py`.

  Use a helper `make_package(place="testplace", version=2, cert_days=365, cert_start=None, issued=None, expires_days=180)` built on phase 1 functions:
  - a root key from `Ed25519PrivateKey.generate()`;
  - an admin key from `ec.generate_private_key(SECP256R1())`;
  - `certify_admin(parse_admin_request(make_admin_request(...)), place, "Test Place", cert_days, "wave-root-test", root, now=cert_start)`;
  - a registry like `_admin_vectors`' `place_registry`, with `key_id` = the cert's `cert_id`, `issued_at` = `issued` or now minus 1 minute;
  - `sign_place_registry`.

  It returns `(package, root_keys)`.

  Tests:
  - **`test_check_upload_valid`:** `(VALID, rec)` with:
    - `rec.place_id == "testplace"`, `rec.place_name == "Test Place"`, `rec.version == 2`;
    - `rec.cert_id` = the cert's id, `rec.admin_name == "Test Admin"`;
    - `rec.package == package` (same four strings).
  - **`test_check_upload_path_must_match_package`:** a valid package posted to `"otherplace"` gives `WRONG_PLACE`.
  - **`test_check_upload_cert_expired_now`:** `cert_start` = now − 10 days, `cert_days=5`, registry issued 9 days ago, `expires_days=1` gives `EXPIRED`. This is phase 1's `now` check.
  - **`test_check_upload_unknown_root`:** `root_keys={}` gives `UNKNOWN_KEY`.
  - **`test_check_upload_junk_is_malformed`:** each of these gives `(MALFORMED, None)` and never raises:
    - `b"not json"`, `b"[]"`, `b"{}"`, `b'{"place_id": 1}'`, `b"\xff\xfe"`;
    - the registry `version` set to `MAX_VERSION + 1` (re-signed).
  - **`test_check_upload_drops_extra_fields`:** a valid package plus `"x": "y"` gives `VALID`, and `rec.package` holds only the four fields.
  - **`test_memory_store_versions`** (the store contract, written as `def check_store_contract(store)` so Task 3 reuses it on Postgres):
    1. Put v2: True. `get` returns it. `places()` lists it.
    2. Put v2 again: False.
    3. Put v1: False. The stored record is still v2.
    4. Put v3: True. `get` returns v3.
    5. Put another place `aaa`: `places()` is sorted, giving `["aaa", "testplace"]`.
    6. `get("nope")` is None.

  Use unique place ids from `f"t{uuid4().hex[:8]}"` inside `check_store_contract` so it is safe on a shared database.
- [ ] **Step 2: Run the tests and check they fail.** Run `python -m pytest tests/test_places.py -q`. Expected: FAIL, `ModuleNotFoundError: server.places`.
- [ ] **Step 3: Implement `server/places.py`.**
  - `check_upload` decodes the body with `json.loads(body.decode("utf-8"))`, catching `ValueError` and `RecursionError` (the `UnicodeDecodeError` from `decode` is a `ValueError` too).
  - It keeps only `PACKAGE_FIELDS`, calls `verify_package(..., now=now)`, then checks `path_place_id == package["place_id"]`, else `WRONG_PLACE`.
  - It rejects `version > MAX_VERSION` as `MALFORMED`.
  - It reads `place_name`, `cert_id` and `admin_name` from `verify_cert(package["cert"], root_keys, at=now)`.
  - It sets `updated_at` from `now`.
  - Wrap the body in `try/except Exception` giving `MALFORMED`, as `verify_package` does.
- [ ] **Step 4: Run the tests and check they pass.** Run `python -m pytest -q`. Expected: all pass, with the old 99 still passing.
- [ ] **Step 5: Commit.**
  ```bash
  git add signing/__init__.py server/places.py tests/test_places.py
  git commit -m "feat(server): place upload check and in-memory place store"
  ```

### Task 2: Endpoints, settings and CONTRACT §4

**Files:**
- Modify: `backend/server/config.py`: settings `database_url`, `root_public_keys`
- Modify: `backend/server/main.py`: `create_app(settings, place_store=None)` and three endpoints; health gets `places_storage`
- Modify: `docs/CONTRACT.md` and `backend/CONTRACT.md` §4 (keep both identical)
- Test: `backend/tests/test_places_api.py`
- Modify: `backend/tests/test_server.py`: `test_health_without_registry_or_asn_data` compares the whole health JSON, so add `"places_storage": "memory"` to it

**Interfaces:**
- Consumes (Task 1):
  - `check_upload`, `PlaceRecord`, `PlaceStore`, `MemoryPlaceStore`, `StorageUnavailable`;
  - the result constants and `MAX_UPLOAD_BYTES`.
- Produces:
  - **`Settings` gains two fields:**
    - `database_url: str | None = field(default=None, repr=False)`, from env `DATABASE_URL`;
    - `root_public_keys: tuple[tuple[str, bytes], ...] = ()`, from env `WAVE_ROOT_PUBLIC_KEYS`.
  - **`WAVE_ROOT_PUBLIC_KEYS` format:** `wave-root-2026:<public_key_b64url>[,id2:key2]`, where each value is copied from `keys/<id>.public.json`.
    - Each id must match `^[a-z0-9][a-z0-9-]{0,31}$`.
    - Each key must decode to 32 bytes.
    - Otherwise `ConfigError` naming the id, never the key.
  - **`create_app(settings=None, place_store: PlaceStore | None = None)`:** uses `place_store` if given. Otherwise it uses `PostgresPlaceStore(settings.database_url)` if a URL is set (Task 3, imported inside the branch), else `MemoryPlaceStore()` with a startup warning: "places are kept in memory only".
  - **`POST /v1/places/{place_id}/registry`:**
    - The body is the package JSON.
    - The response is JSON `{"result": CODE}`, plus `"place_id"` and `"version"` when the result is `STORED`.
    - Status codes:

      | Result | Status |
      |---|---|
      | `STORED` | 200 |
      | `MALFORMED` | 400 |
      | `TOO_LARGE` | 413 |
      | `UNKNOWN_KEY`, `BAD_SIGNATURE`, `NOT_YET_VALID`, `EXPIRED`, `WRONG_PLACE` | 403 |
      | `STALE_VERSION` | 409 |
      | `UPLOADS_OFF` (no root keys set) | 503 |
      | `STORAGE_UNAVAILABLE` | 503 |
  - **`GET /v1/places`:** returns `{"places": [record.summary(), ...]}`.
  - **`GET /v1/places/{place_id}/registry`:** returns `record.package` exactly, or 404 `{"result": "NOT_FOUND"}`.
  - All three send `Cache-Control: no-store`.
  - **`/v1/health`:** adds `"places_storage": store.kind`.

- [ ] **Step 1: Write the failing tests** in `tests/test_places_api.py`.

  Reuse `make_package` from `tests/test_places.py`: import it, with `tests/` on `sys.path`, as `test_server.py` does for `backend/`. Use `TestClient(create_app(settings, place_store=MemoryPlaceStore()))`, with settings carrying the test root key.

  Tests:
  - **`test_upload_then_download`:**
    - The POST gives 200 `{"result": "STORED", "place_id": "testplace", "version": 2}`.
    - `GET /v1/places` gives one entry with `place_name == "Test Place"`.
    - `GET /v1/places/testplace/registry` gives JSON equal to the package.
    - `/v1/health` has `places_storage == "memory"`.
  - **`test_upload_same_version_is_409`:** a second POST gives 409 `STALE_VERSION`.
  - **`test_upload_wrong_path_is_403`:** a POST to `/v1/places/otherplace/registry` gives 403 `WRONG_PLACE`.
  - **`test_upload_too_large_is_413`:** a body of `MAX_UPLOAD_BYTES + 1` bytes gives 413 `TOO_LARGE`, and nothing is stored.
  - **`test_upload_junk_is_400`:** `b"nope"` and `b"[]"` give 400 `MALFORMED`.
  - **`test_uploads_off_without_root_keys`:** settings without root keys give 503 `UPLOADS_OFF`. `GET /v1/places` still gives 200 `{"places": []}`.
  - **`test_download_unknown_place_is_404`**
  - **`test_storage_down_is_503_without_secrets`:**
    - Use a store whose methods raise `StorageUnavailable("database unreachable")`, with settings `database_url="postgresql://u:SECRETPW@h/db"`.
    - POST, `GET /v1/places` and the GET download each give 503 `STORAGE_UNAVAILABLE`.
    - `/v1/health` gives 200.
    - `"SECRETPW"` appears in no response body and not in `repr(settings)`.
  - **`test_root_keys_env`:**
    - `Settings.from_env({"WAVE_ROOT_PUBLIC_KEYS": f"wave-root-test:{b64}"})` parses one key.
    - `"bad id!:x"`, a key of the wrong length, and a value with no `:` each raise `ConfigError`.
- [ ] **Step 2: Run the tests and check they fail.** Run `python -m pytest tests/test_places_api.py -q`. Expected: FAIL, with `create_app()` getting an unexpected keyword `place_store`.
- [ ] **Step 3: Implement the settings and endpoints.**
  - **The POST handler is `async`.**
    - Reject a `Content-Length` over the limit at once.
    - Read `request.stream()` chunk by chunk and stop with `TOO_LARGE` as soon as the total passes `MAX_UPLOAD_BYTES`.
    - Run `check_upload` and `store.put` with `starlette.concurrency.run_in_threadpool`, because the Postgres calls block.
  - **The GET handlers** are plain `def`.
  - **Errors:** catch `StorageUnavailable` and answer 503. Log `log.warning("place storage: %s", exc)`; the message is URL-free by contract.
  - **Root keys:** build the key dict once with `Ed25519PublicKey.from_public_bytes`.
- [ ] **Step 4: Update CONTRACT §4** in both copies.
  - Document the three endpoints: the body, the status/result table above, the `GET /v1/places` shape and the 256 KiB limit.
  - Add: "Phones check every package themselves (§7). The server's check only keeps junk out."
  - Change `GET /v1/health` to list its real fields, including `places_storage`.
  - Check the two copies match: `cmp ../docs/CONTRACT.md CONTRACT.md`.
- [ ] **Step 5: Run the tests and check they pass.** Run `python -m pytest -q`. Expected: all pass.
- [ ] **Step 6: Commit.**
  ```bash
  git add server/config.py server/main.py tests/test_places_api.py tests/test_server.py CONTRACT.md ../docs/CONTRACT.md
  git commit -m "feat(server): upload and download place packages (CONTRACT §4)"
  ```

### Task 3: Postgres store (Supabase), dependency and deploy docs

**Files:**
- Create: `backend/server/places_pg.py`
- Modify: `backend/requirements-server.txt`: add `psycopg[binary]>=3.2` with its license (LGPL-3.0, used unmodified as a library) in the header comment
- Modify: `backend/README.md`: "Deploy on Render" gets two new env rows; "Place admins" gets step 4, "Upload and download"
- Test: `backend/tests/test_places_pg.py`. It is skipped unless `WAVE_TEST_DATABASE_URL` is set: a throwaway Supabase project or a local Postgres, **never** the production URL.

**Interfaces:**
- Consumes (Task 1):
  - `PlaceRecord`, `StorageUnavailable`;
  - `check_store_contract(store)` from `tests/test_places.py`.
- Produces: **`PostgresPlaceStore(url: str)`**, with `kind = "postgres"`, implementing `PlaceStore`.
  - **Import:** `import psycopg` sits inside the class, so memory mode runs without psycopg installed.
  - **Connections:** each call opens one connection with `psycopg.connect(url, connect_timeout=10, autocommit=True, prepare_threshold=None)`. Supabase's pooler can't use prepared statements.
  - **Schema:** created on first use, under a lock, and retried on the next call if it failed:
    ```sql
    create table if not exists wave_places (
      place_id text primary key, place_name text not null, version bigint not null,
      cert_id text not null, admin_name text not null,
      registry_b64url text not null, signature_b64url text not null, cert text not null,
      updated_at timestamptz not null);
    alter table wave_places enable row level security;  -- no policies: Supabase's public API sees nothing
    ```
  - **`put`:** one atomic statement, so racing uploads can't store a lower version:
    ```sql
    insert into wave_places (...) values (...)
    on conflict (place_id) do update set <every column> = excluded.<column>
      where wave_places.version < excluded.version
    returning place_id
    ```
    A returned row means True; none means False.
  - **Errors:** every `psycopg.Error` becomes `StorageUnavailable(type(exc).__name__)`. Never pass `str(exc)` on, because it can contain the host and user.

- [ ] **Step 1: Write the failing tests** in `tests/test_places_pg.py`. The module is skipped without `WAVE_TEST_DATABASE_URL` and uses `pytest.importorskip("psycopg")`.
  - **`test_pg_store_contract`:** `check_store_contract(PostgresPlaceStore(url))`, then delete the rows it made.
  - **`test_pg_table_has_row_level_security`:** `select relrowsecurity from pg_class where relname = 'wave_places'` is true.
  - **`test_pg_bad_url_is_storage_unavailable`** runs even without the env var, but only if psycopg is installed:
    - `PostgresPlaceStore("postgresql://u:SECRETPW@127.0.0.1:1/x").places()` raises `StorageUnavailable`;
    - `"SECRETPW"` is not in `str(exc)`.
- [ ] **Step 2: Install psycopg and run the tests to check they fail.** Run `pip install "psycopg[binary]>=3.2"`, then `python -m pytest tests/test_places_pg.py -q`. Expected: the bad-URL test fails (`ModuleNotFoundError: server.places_pg`); the others are skipped.
- [ ] **Step 3: Implement `server/places_pg.py`** and add the requirement line.
- [ ] **Step 4: Run the full tests.** Run `python -m pytest -q`. Expected: all pass; the pg contract tests are skipped unless the env var is set.
- [ ] **Step 5: Do a laptop rehearsal (memory store)** and confirm each result.
  1. Run `uvicorn server.main:app --port 8000` with `WAVE_ROOT_PUBLIC_KEYS` set from a throwaway root key in `out/` (made with `keygen --key-id wave-root-test --out-dir out/rehearsal`).
  2. Make a package with `admin-request`, then `certify-admin`, then `sign-place-registry`.
  3. Run `curl -X POST --data-binary @out/package.json -H "Content-Type: application/json" http://127.0.0.1:8000/v1/places/<id>/registry` and confirm `STORED`.
  4. Repeat it and confirm `STALE_VERSION`.
  5. Run `curl http://127.0.0.1:8000/v1/places` and confirm the place is listed.
- [ ] **Step 6: Update the README.**
  - **Render env rows:**
    - `DATABASE_URL`: Supabase → Connect → **Session pooler** URI (Render's free tier has no IPv6, so the direct connection won't work). Secret; set it only in Render.
    - `WAVE_ROOT_PUBLIC_KEYS`: `wave-root-2026:<public_key_b64url from keys/wave-root-2026.public.json>`. Public, not secret.
  - **Place admins, step 4:** the curl upload and download commands, and how to read `/v1/health` → `places_storage` (it should say `postgres` on Render).
- [ ] **Step 7: Commit.**
  ```bash
  git add server/places_pg.py tests/test_places_pg.py requirements-server.txt README.md
  git commit -m "feat(server): Supabase Postgres place store"
  ```

---

## After the plan (the user's steps, not code)

1. **Make the real root key** on the laptop: `python signing/wave_sign.py keygen --key-id wave-root-2026`. Use a new passphrase, typed only in the terminal, and keep a USB backup.
2. **Create the Supabase project** (free tier). Copy its Session pooler URI.
3. **Set both env values in Render** after this branch reaches `main`.
4. **Upload a test place package from the laptop.** `/v1/health` should show `places_storage: postgres`.
