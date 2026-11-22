# Permanent Hosting (Render) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The WAVE server runs at one fixed HTTPS address on Render (free tier). The app is built with that address, so nobody has to start a tunnel or type an address. The exit-path check reports the **phone's** real IP and can't be spoofed.

**Architecture:**
- The server gains a `header` client-IP mode for platforms whose proxy can't be identified by a single IP. It believes one named header only when the socket peer is inside trusted CIDR ranges, and can take the N-th entry from the right of a list header.
- A debug endpoint, off by default, shows what Render actually sends, so the header choice is **observed, not guessed**. Render reportedly appends to a client-sent `X-Forwarded-For` instead of clearing it.
- Deployment is a Render Web Service with root `backend/`. The build downloads the IPtoASN data.
- **No registry is served** (user decision 2026-11-23, option 1): `/v1/registry` answers 503 and phones keep their built-in copy. The campus registry is added after the survey.
- The app's default address goes in the project's `gradle.properties`.

**Tech Stack:** Python 3.13 / FastAPI / uvicorn (existing), pytest, Render free Web Service, Gradle property → BuildConfig (existing).

**Spec:**
- `docs/CONTRACT.md` §4: HTTPS only; the server must see the phone's public IP.
- `backend/server/client_ip.py` module doc: forwarding headers are believed only from a trusted proxy, and a trusted proxy that doesn't name the client gives "unknown", never the proxy's own IP.
- User decision: serve no registry for now.

## Global Constraints

- **Branch:** `feature/render-hosting`, created from `feature/registry-download`. Don't push; ask first.
- **Server holds no keys.** No new Python dependencies; `ipaddress` is in the standard library.
- **Safe defaults:**
  - `WAVE_TRUSTED_PROXY` defaults to `none`.
  - The debug endpoint exists only when `WAVE_DEBUG_CLIENT_IP=1`; otherwise it's 404.
  - A setting that's invalid stops the server from starting (`ConfigError`), never a silent fallback.
- **New settings, exact names:**
  - `WAVE_TRUSTED_PROXY=header`
  - `WAVE_CLIENT_IP_HEADER` (header name, case-insensitive)
  - `WAVE_TRUSTED_PROXY_CIDRS` (comma-separated CIDRs, e.g. `10.0.0.0/8`)
  - `WAVE_CLIENT_IP_FROM_RIGHT` (integer ≥ 1, default `1`; which comma-separated entry, counted from the right)
- **The user does the Render dashboard steps.** I prepare exact values and verify with `curl`. Render account and deploy clicks are theirs.
- Run backend tests from `backend/` with `.venv/Scripts/python -m pytest tests -q`. Run app checks with `./gradlew testDebugUnitTest assembleDebug`. Use the Xiaomi phone (`be387ebf`) for device checks, never the emulator.

## Review Focus

1. **Spoofed client IP:** a phone sends `X-Forwarded-For: <campus IP>`, `CF-Connecting-IP` or `True-Client-IP` to look like it's on campus.
   - It must never change the result: only the header Render itself sets is trusted, and only from Render's internal peer range.
   - Cover: Task 1 spoof tests, Task 3 live spoof test.
2. **Peer outside the trusted CIDRs** (e.g. someone reaching the instance directly): headers are ignored and the peer address is used. Cover: Task 1 `header_mode_ignores_untrusted_peer`.
3. **Header missing or garbage from the trusted proxy:** "unknown", with a note; never the proxy's own address. Cover: Task 1.
4. **Cold start:** the free instance sleeps after 15 min of no traffic and takes about 30–60 s to wake, so the first exit-path check may end Unreachable.
   - Mitigation: the app's registry sync at start already hits the server and wakes it; README says to open `/v1/health` before a demo.
   - Cover: Task 4, manual.
5. **The debug endpoint left on in production:** it only echoes the caller's own headers, but it must be off after Task 3. Cover: Task 3, final step checks for 404.

---

### Task 1: `header` client-IP mode

**Files:**
- Modify: `backend/server/config.py` (`PROXY_MODES`, new fields, `from_env`, validation)
- Modify: `backend/server/client_ip.py` (new branch in `resolve`, module doc)
- Test: `backend/tests/test_server.py`, new tests next to the `forwarded` ones (~line 306)

**Interfaces:**
- Produces:
  - `Settings.client_ip_header: str | None`
  - `Settings.trusted_proxy_cidrs: tuple[ipaddress.IPv4Network | ipaddress.IPv6Network, ...]`
  - `Settings.client_ip_from_right: int = 1`
  - `PROXY_MODES = ("none", "cloudflare", "forwarded", "header")`
- Validation (`ConfigError`):
  - `header` mode needs a header name and at least one CIDR;
  - a bad CIDR names the bad entry;
  - `from_right` < 1 or not an integer is an error.
- `resolve` in `header` mode:
  1. If the peer isn't in any trusted CIDR, use the peer (headers ignored).
  2. Otherwise, split all values of the header on commas and take entry `[-from_right]`.
  3. A missing header, too few entries or an invalid IP gives `ClientAddress(None, "The proxy didn't say which address the request came from.")`.

- [ ] **Step 1: Write the failing tests.** Use the existing `exit_path(...)` helper with `trusted_proxy="header"`, `client_ip_header=...`, `trusted_proxy_cidrs=(ipaddress.ip_network("10.0.0.0/8"),)`.
  - `test_header_mode_uses_the_named_header_from_a_trusted_range`: peer `10.1.2.3`, `{"True-Client-IP": DITO_IP}` → asn 139831.
  - `test_header_mode_ignores_untrusted_peer`: peer `GLOBE_IP` with `SPOOF` plus `True-Client-IP: DITO` → asn 4775, `public_ip == GLOBE_IP`.
  - `test_header_mode_ignores_other_forwarding_headers`: header `True-Client-IP`, the request has only `X-Forwarded-For: DITO` and `CF-Connecting-IP: DITO` → asn None, note contains `"proxy didn't say"`.
  - `test_header_mode_counts_from_the_right`: header `X-Forwarded-For`, value `"{GLOBE}, {DITO}, 10.9.9.9"`:
    - `from_right=2` → DITO, because the client-sent GLOBE on the left is ignored;
    - `from_right=1` → `10.9.9.9`, private, so the answer has the "private or local address" note.
  - `test_header_mode_garbage_is_unknown`: `True-Client-IP: not-an-ip` → asn None.
  - `test_header_mode_config_errors`:
    - `Settings.from_env({"WAVE_TRUSTED_PROXY": "header"})` → ConfigError mentioning `WAVE_CLIENT_IP_HEADER`;
    - with the header but no CIDRs → mentions `WAVE_TRUSTED_PROXY_CIDRS`;
    - `WAVE_TRUSTED_PROXY_CIDRS=10.0.0.0/33` → mentions `10.0.0.0/33`;
    - `WAVE_CLIENT_IP_FROM_RIGHT=0` → ConfigError;
    - a valid env → fields set, header lower-cased.
- [ ] **Step 2: Run them and check they fail.** Run `.venv/Scripts/python -m pytest tests/test_server.py -q -k header_mode`. Expected: errors or failures, because the fields and mode don't exist yet.
- [ ] **Step 3: Implement.** Update `config.py` (CIDRs parsed with `ipaddress.ip_network(part, strict=False)`) and `client_ip.py`. Update the module doc to list the fourth mode.
- [ ] **Step 4: Run all backend tests.** Run `.venv/Scripts/python -m pytest tests -q`. Expected: all pass (56 existing + new).
- [ ] **Step 5: Commit.** `git commit -m "feat(server): header client-IP mode with trusted CIDR ranges"`

---

### Task 2: Debug endpoint for observing a platform's headers

**Files:**
- Modify: `backend/server/config.py`: `debug_client_ip: bool = False` from `WAVE_DEBUG_CLIENT_IP` (`"1"`/`"true"` → True).
- Modify: `backend/server/main.py`: `GET /v1/debug/client-ip`, registered **only** when enabled.
- Test: `backend/tests/test_server.py`

**Interfaces:**
- Produces: when enabled, `GET /v1/debug/client-ip` returns
  `{"peer": str, "resolved_ip": str | None, "problem": str | None, "mode": str, "headers": {name: [values]}}`
  - `headers` lists only `x-forwarded-for`, `cf-connecting-ip`, `true-client-ip`, `x-real-ip`, `forwarded`.
  - Response header `Cache-Control: no-store`.
  - Startup logs a warning that the debug endpoint is on.

- [ ] **Step 1: Write the failing tests:**
  - `test_debug_endpoint_is_off_by_default`: 404.
  - `test_debug_endpoint_shows_forwarding_headers`: enabled, peer `10.1.2.3`, sends `X-Forwarded-For: a, b` and `True-Client-IP: DITO` → `headers["x-forwarded-for"] == ["a, b"]`, `headers["true-client-ip"] == [DITO]`, `peer == "10.1.2.3"`, no-store header.
  - `test_debug_endpoint_reports_resolved_ip`: enabled + header mode (as in Task 1) → `resolved_ip == DITO`.
- [ ] **Step 2: Run them and check they fail.** Run `.venv/Scripts/python -m pytest tests/test_server.py -q -k debug_endpoint`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run all backend tests.** Expected: all pass.
- [ ] **Step 5: Commit.** `git commit -m "feat(server): optional /v1/debug/client-ip to observe proxy headers"`

---

### Task 3: Deploy on Render and choose the trusted header from evidence

There are no code changes in this task unless the observation needs one. The user does the dashboard steps; I verify.

- [ ] **Step 1: Push the branch.** Render builds from GitHub. **Ask the user before pushing.**
- [ ] **Step 2: The user creates the service.** Render Dashboard → **New → Web Service → Public Git Repository** `https://github.com/asilo-sofhia/WAVE`, with:

  | Field | Value |
  |---|---|
  | Branch | `feature/render-hosting` (switch to `main` after merge) |
  | Root Directory | `backend` |
  | Runtime | Python 3 |
  | Build Command | `pip install -r requirements-server.txt && python server/update_asn_db.py` |
  | Start Command | `uvicorn server.main:app --host 0.0.0.0 --port $PORT` |
  | Health Check Path | `/v1/health` |
  | Instance type | Free |
  | Env | `PYTHON_VERSION=3.13.14`, `WAVE_TRUSTED_PROXY=none`, `WAVE_DEBUG_CLIENT_IP=1` |

  - Don't set `WAVE_REGISTRY_FILE`. With no registry, `/v1/registry` answers 503, which is the intended behaviour (option 1).
  - Expected: the deploy log shows `Saved … ranges` (ASN data), then `Application startup complete`. `GET <url>/v1/health` → `"asn_db_loaded": true, "registry_loaded": false`.
- [ ] **Step 3: Observe.**
  - From the laptop, get the real public IP with `curl -s https://1.1.1.1/cdn-cgi/trace` (`ip=` line), then:
    ```
    curl -s <url>/v1/debug/client-ip
    curl -s <url>/v1/debug/client-ip -H "X-Forwarded-For: 1.2.3.4" -H "CF-Connecting-IP: 1.2.3.4" -H "True-Client-IP: 1.2.3.4" -H "X-Real-IP: 1.2.3.4"
    ```
  - Open `<url>/v1/debug/client-ip` once on the phone (Chrome), on Wi-Fi.
  - Record in the ledger: the peer addresses, and which headers hold the real IP in both requests.
- [ ] **Step 4: Decide, using the first rule that matches:**
  1. A **single-value** header (`True-Client-IP` or `CF-Connecting-IP`) that equals the real IP **and stays the real IP in the spoofed request** → `WAVE_CLIENT_IP_HEADER=<that>`, `WAVE_CLIENT_IP_FROM_RIGHT=1`.
  2. Otherwise `X-Forwarded-For`, if the real IP sits at a **fixed position from the right** in both requests → `WAVE_CLIENT_IP_HEADER=x-forwarded-for`, `WAVE_CLIENT_IP_FROM_RIGHT=<that position>`.
  3. Otherwise **stop and ask the user**: no header can be trusted, so the plan needs rethinking.

  CIDRs: the smallest standard private range that holds every observed peer (`10.0.0.0/8`, `172.16.0.0/12` or `192.168.0.0/16`, plus `fc00::/7` if any IPv6 peers appear). Record the ruling with the evidence.
- [ ] **Step 5: Lock it down.** The user sets `WAVE_TRUSTED_PROXY=header`, `WAVE_CLIENT_IP_HEADER`, `WAVE_TRUSTED_PROXY_CIDRS`, `WAVE_CLIENT_IP_FROM_RIGHT`, and **deletes** `WAVE_DEBUG_CLIENT_IP`, then redeploys. Expected:
  - `curl <url>/v1/debug/client-ip` → 404.
  - `curl <url>/v1/exit-path` → `public_ip` = the laptop's real IP.
  - The same request with the four spoof headers set to `1.2.3.4` → still the real IP.

---

### Task 4: Build the app with the Render address, plus docs

**Files:**
- Modify: `gradle.properties` (project root). Add `wave.serverUrl=https://<service>.onrender.com`, with a comment: the public WAVE server, overridable in the debug menu.
- Modify: `backend/README.md`, adding a "Deploy on Render" section:
  - the Task 3 table and the final env values (without the debug flag);
  - the spoof check command;
  - "free tier sleeps after 15 min: open `/v1/health` about a minute before a demo";
  - "to serve the campus registry later: add `registry.json` and `registry.json.sig` as Render Secret Files and set `WAVE_REGISTRY_FILE=/etc/secrets/registry.json`; upload the file, don't paste it, because the signature covers the exact bytes and pasting can change line endings".
- Modify: `backend/README.md:136`: replace the stale "Android exit-path client comes next" sentence (deferred minor from the exit-path branch).

- [ ] **Step 1: Check the build picks up the address.** Run `./gradlew assembleDebug`, then grep `app/build/generated/source/buildConfig/debug/com/example/wave/BuildConfig.java` for the Render URL. Expected: `WAVE_SERVER_URL = "https://<service>.onrender.com"`.
- [ ] **Step 2: Phone check** (Xiaomi, `ANDROID_SERIAL=be387ebf ./gradlew installDebug`):
  - In the debug menu, clear the address field and tap **Save**, so the phone forgets the old tunnel address and uses the built-in Render address.
  - Tap **Test server**. Expected: `ASN 9299 · PLDT…` on home PLDT Wi-Fi. The first tap after sleep may take up to about a minute.
  - Tap **Update registry**. Expected: `No answer: HTTP 503`, which is correct for option 1.
  - Networks still says "Using signed registry v1" (built-in).
- [ ] **Step 3: Run app and backend tests.** Run `./gradlew testDebugUnitTest` and `.venv/Scripts/python -m pytest tests -q`. Expected: all pass.
- [ ] **Step 4: Commit.** `git commit -m "chore: build the app with the Render server address; Render deploy guide"`
- [ ] **Step 5: Ask the user before pushing** the final commit or opening PRs.
