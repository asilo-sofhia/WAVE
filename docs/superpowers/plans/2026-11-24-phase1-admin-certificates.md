# Phase 1: Admin Certificates (backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The signing tool can certify a place admin's phone key with the WAVE root key and check a place registry package (root → certificate → admin signature). This is the foundation the server (phase 2) and the app (phases 3–5) build on.

**Architecture:**
- New pure functions in `backend/signing/wave_sign.py` cover enrolment requests, certificates, place registry packages and their checks.
- CLI commands wrap them: `certify-admin`, `verify-cert`, plus two laptop test helpers, `admin-request` and `sign-place-registry`, until the phone can do those parts (phases 3–4). Then `verify-package`.
- Test vectors are extended so the Kotlin side can be checked against the same cases later.

**Tech Stack:** Python 3.13, `cryptography` (Ed25519 for the root, ECDSA P-256/SHA-256 for admin keys; already a dependency), pytest. **No new dependencies.**

**Spec:** `docs/superpowers/specs/2026-11-24-multi-place-admins.md` (formats and validity rules 1–5).

## Global Constraints
- **Branch:** `feature/admin-certs` from `main` (5e6a605). Ask before pushing.
- **Domains:**
  - certificates: `CERT_DOMAIN = b"WAVE-CERT-v1\n"`;
  - place registries: the existing `REGISTRY_DOMAIN`.
- **Certificate signature:** Ed25519 by the root over `CERT_DOMAIN + cert_bytes`.
  - `cert_bytes` = `json.dumps(cert, separators=(",", ":"), sort_keys=True).encode()`, built once and never re-serialized when verifying (the same rule as CONTRACT Rule #1).
- **Admin keys:** `admin_key_alg` must be `"ES256"`.
  - `admin_public_key` = base64url (no padding) of the P-256 SubjectPublicKeyInfo DER. Any other curve or algorithm is MALFORMED.
- **Registry signature:** ECDSA P-256 + SHA-256, DER-encoded, base64url, over `REGISTRY_DOMAIN + registry_bytes`.
- **Field rules:**
  - `place_id` matches `^[a-z0-9][a-z0-9-]{0,31}$`;
  - `place_name` and `admin_name` are 1–64 characters;
  - `cert_id` is 16 lowercase hex characters, random;
  - times are ISO UTC with `Z`, seconds only;
  - `expires_at > issued_at`.
- **Results** (strings, like the QR results):
  - `VALID`, `MALFORMED`, `UNKNOWN_KEY`, `BAD_SIGNATURE`, `EXPIRED`, `NOT_YET_VALID`, `WRONG_PLACE`.
  - The precedence, as in the spec: format → root key known → root signature → validity time → place match → admin signature.
- **Validity time:** a certificate is checked at the registry's `issued_at` (spec rule 5), or at "now" when checked alone.
- **Private key handling:** the root key is passphrase-protected (existing `keygen`). Test admin keys made by `admin-request` are written like the other private keys (owner-only, `*.private.pem`, ignored by Git).
- Run tests from `backend/` with `.venv/Scripts/python -m pytest tests -q`.

## Review Focus
1. **A certificate for one place used for another** (Ana's BatStateU cert on a "smmall" package) → `WRONG_PLACE`. Cover: Task 2 `test_package_wrong_place`.
2. **A tampered certificate**, such as a changed `place_id` or a longer `expires_at` → `BAD_SIGNATURE`. Cover: Task 1 `test_cert_tamper_detected`.
3. **A registry signed by a different phone** than the certified one → `BAD_SIGNATURE`. Cover: Task 2 `test_package_signed_by_other_admin`.
4. **A certificate that expired before the list was issued** → `EXPIRED`; issued before the certificate started → `NOT_YET_VALID`. Cover: Task 2.
5. **A non-P-256 or garbage admin key in a request** → refused, `MALFORMED`. Cover: Task 1 `test_request_rejects_bad_key`.

---

### Task 1: Enrolment requests and admin certificates

**Files:**
- Modify: `backend/signing/wave_sign.py`, adding the functions and constants below.
- Test: `backend/tests/test_admin_certs.py`

**Interfaces (produced):**
```python
CERT_DOMAIN = b"WAVE-CERT-v1\n"
NOT_YET_VALID = "NOT_YET_VALID"; WRONG_PLACE = "WRONG_PLACE"
def admin_public_key_b64(pub: ec.EllipticCurvePublicKey) -> str
def make_admin_request(admin_name: str, place_id: str, pub: ec.EllipticCurvePublicKey) -> str            # "WAVEREQ:1:..."
def parse_admin_request(text: str) -> dict                                                              # raises ValueError
def certify_admin(request: dict, place_id: str, place_name: str, days: int, root_key_id: str,
                  root_key: Ed25519PrivateKey, now: dt.datetime | None = None) -> str                 # "WAVECERT:1:<c>.<s>"
def verify_cert(text: str, root_keys: dict[str, Ed25519PublicKey], at: dt.datetime) -> tuple[str, dict]
```
`certify_admin` uses the request's key and name. The `place_id` argument must equal the requested one, otherwise `ValueError` ("request asks for X"): the owner decides, and a mismatch is refused.

- [ ] **Step 1: Write the failing tests:**
  - `test_request_roundtrip`
  - `test_request_rejects_bad_key` (an Ed25519 key, a P-384 key and garbage → `ValueError`)
  - `test_request_rejects_bad_place_id`
  - `test_certify_and_verify` → `VALID`, the dict has the place and admin name
  - `test_certify_refuses_other_place`
  - `test_cert_tamper_detected`: re-encode with a changed `place_id`, keeping the old signature → `BAD_SIGNATURE`
  - `test_cert_unknown_root` → `UNKNOWN_KEY`
  - `test_cert_expired` and `test_cert_not_yet_valid` (using `at`)
  - `test_cert_malformed` (no prefix, bad base64, missing field)
- [ ] **Step 2: Run them and check they fail.** Run `.venv/Scripts/python -m pytest tests/test_admin_certs.py -q`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run all backend tests.** Expected: all pass.
- [ ] **Step 5: Commit.** `git commit -m "feat(signing): admin enrolment requests and root-signed certificates"`

---

### Task 2: Place registry packages and their check

**Files:**
- Modify: `backend/signing/wave_sign.py`. `validate_registry` accepts an optional `place_id` (pattern as above) and keeps working without it.
- Test: `backend/tests/test_admin_certs.py`

**Interfaces (produced):**
```python
def sign_place_registry(registry_bytes: bytes, admin_key: ec.EllipticCurvePrivateKey, cert_text: str) -> dict
    # -> {"place_id", "registry_b64url", "signature_b64url", "cert"}; place_id read from the registry
def verify_package(package: dict, root_keys: dict[str, Ed25519PublicKey]) -> tuple[str, dict | None]
    # checks in order: shape, registry validates, cert (verify_cert at registry issued_at),
    # cert place == package place == registry place, admin ECDSA signature
```

- [ ] **Step 1: Write the failing tests:**
  - `test_package_valid`
  - `test_package_wrong_place` (registry and package `smmall`, cert `batstateu`)
  - `test_package_signed_by_other_admin`
  - `test_package_tampered_registry`
  - `test_package_cert_expired_before_issue`
  - `test_package_registry_without_place_id_is_malformed`
  - `test_registry_place_id_optional_for_old_lists` (existing `registry.json` still validates)
- [ ] **Step 2: Run them and check they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run all backend tests.** Expected: all pass.
- [ ] **Step 5: Commit.** `git commit -m "feat(signing): place registry packages checked from the root down"`

---

### Task 3: CLI commands, test vectors, docs

**Files:**
- Modify: `backend/signing/wave_sign.py` (`main`):
  - `admin-request --name --place --out-dir keys` (**test helper**): makes a P-256 key `keys/admin-<place>-<n>.private.pem` and prints the request text.
  - `certify-admin --request <text|@file> --place --place-name --days 365 --key <root.pem> [--png out/x.png]`: prints the certificate text and optionally writes a QR PNG with the existing `write_qr_png`.
  - `verify-cert <text|@file> --pub <root.public.json>`
  - `sign-place-registry <registry.json> --admin-key <pem> --cert <text|@file> --out package.json` (**test helper**)
  - `verify-package <package.json> --pub <root.public.json>`
- Modify: `make_test_vectors()`: add `"cert_cases"` and `"package_cases"`, each with `"expect"`, using the deterministic test root plus a fixed P-256 test key. Regenerate `backend/test-vectors/test_vectors.json` and copy it byte-for-byte to `app/src/test/resources/test_vectors.json`. Existing registry and QR cases must stay identical: check with `git diff` that only new keys were added.
- Modify: `docs/CONTRACT.md` and `backend/CONTRACT.md` (identical): new **§6 Admin certificates** and **§7 Place registry packages**, from the spec. Renumber the old §5 references if needed.
- Modify: `backend/README.md`: a "Place admins" section covering root key creation (`keygen --key-id wave-root-2026`), certifying an admin, and a full laptop-only rehearsal with the two test helpers.
- Modify: `.gitignore` if needed, so `keys/admin-*.private.pem` stays ignored (`*.pem` is already ignored).
- Test: `backend/tests/test_admin_certs.py`.

- [ ] **Step 1: Write the failing tests:**
  - `test_cli_full_flow(tmp_path)`: `main([...])` for keygen root (no passphrase), admin-request, certify-admin (with `--png`), sign-place-registry, verify-package → exit 0 and "VALID" printed.
  - `test_cli_certify_refuses_other_place`
  - `test_test_vectors_have_cert_and_package_cases` (generate, then self-check every case's `expect`)
- [ ] **Step 2: Run them and check they fail.**
- [ ] **Step 3: Implement**, then regenerate and copy the vectors.
- [ ] **Step 4: Run all backend tests, plus the app's unit tests** (`./gradlew testDebugUnitTest`, because the vectors file changed). Expected: all pass.
- [ ] **Step 5: Commit.** `git commit -m "feat(signing): admin CLI, test vectors and CONTRACT §6-7"`
- [ ] **Step 6: Ask the user before pushing.** PR title: `feat(signing): phase 1 — admin certificates`.
