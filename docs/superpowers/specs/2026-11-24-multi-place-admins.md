# Multi-place admins: design

**Status:** agreed with the user (2026-11-24); storage: Supabase.

## Goal
A place admin (BatStateU, a mall, …) scans, approves/blocks and **publishes from the phone**, with no laptop and no Render dashboard. Users' phones get every place's list automatically. It stays safe: only a certified admin can publish for their own place, and nobody can change a list after it is signed.

## People and keys
| Who | Key | Where it lives |
|---|---|---|
| **WAVE owner** (root) | `wave-root-2026`, Ed25519, passphrase-protected | the owner's laptop only (offline). Public key built into the app and set on the server |
| **Place admin** (Ana at BatStateU) | one P-256 key per admin phone (ES256) | **Android Keystore** (secure hardware), usable only after fingerprint/PIN |
| **User** | none | — |

The server holds **no private keys**: only the root **public** key, to check uploads.

## Chain of trust
```
wave-root-2026 (in the app)
  └─ signs an admin certificate: "this phone key may sign for place batstateu until <date>"
       └─ the admin phone key signs BatStateU's registry
```
New places and new admins need **no app update**.

## Formats (CONTRACT §6 and §7)
**Admin certificate:** JSON bytes signed by the root with domain `"WAVE-CERT-v1\n"`:
```json
{
  "schema": "wave-admin-cert/1",
  "cert_id": "3f9a1c0d2b7e4a51",
  "place_id": "batstateu",
  "place_name": "Batangas State University",
  "admin_name": "Ana Cruz",
  "admin_key_alg": "ES256",
  "admin_public_key": "<base64url of the P-256 SubjectPublicKeyInfo DER>",
  "issued_at": "2026-11-24T00:00:00Z",
  "expires_at": "2027-11-24T00:00:00Z",
  "root_key_id": "wave-root-2026"
}
```
- **Certificate QR text:** `WAVECERT:1:<cert_b64url>.<signature_b64url>`.
- **Enrolment request** (phone → owner, shared as text): `WAVEREQ:1:<b64url of {"admin_name","place_id","admin_key_alg","admin_public_key"}>`.

**Place registry:** today's registry JSON plus `"place_id"`. Signed by the admin phone key (ECDSA P-256, SHA-256, DER signature) over `"WAVE-REGISTRY-v1\n" + bytes`. Published as a **package**:
```json
{ "place_id": "batstateu", "registry_b64url": "...", "signature_b64url": "...", "cert": "<certificate QR text>" }
```

**A package is valid only if all of these hold:**
1. The certificate's root signature is valid.
2. Its `root_key_id` is trusted.
3. The certificate's `place_id` is the package's and the registry's.
4. The registry signature verifies with the certificate's `admin_public_key`.
5. The certificate was valid at the registry's `issued_at`.
6. The certificate is not revoked (phase 6).
7. The version is higher than the stored or cached one for that place.

## Flows
1. **Enrol (once per admin):** the phone creates its Keystore key and shares the request text. The owner runs `wave_sign.py certify-admin` and gets a certificate PNG/QR. The admin scans it, and the app shows "Admin · BatStateU".
2. **Publish:** Manage registry → **Publish** → fingerprint → the phone signs and uploads the package. The server checks rules 1–5 and 7, then stores it in **Supabase Postgres**. No login: the certificate plus signature is the proof.
3. **Users:** the app downloads the list of places and each place's package, checks rules 1–7, and merges them. A network is Verified when its BSSID is in the place list that has its SSID.
4. **Revoke (phase 6):** the owner signs a revocation list (`cert_id`s). The server serves it and phones refuse those certificates.

## Phases (each its own plan, branch and PR)
| # | Phase | Main parts |
|---|---|---|
| 1 | **Certificates (backend)** | root key, `certify-admin`, `verify-cert`, place-registry signing and checking, test vectors, CONTRACT §6–7 |
| 2 | **Server storage + endpoints** | Supabase tables; `POST /v1/places/{id}/registry` (chain-checked), `GET /v1/places`, `GET /v1/places/{id}/registry`; `DATABASE_URL` on Render |
| 3 | **App: admin enrolment** | Keystore key (fingerprint/PIN), share request, scan certificate, "Admin · Place" |
| 4 | **App: publish from the phone** | sign with the Keystore key, upload, show the result |
| 5 | **App: users get every place** | download and check packages, cache, rollback per place, classifier merges places |
| 6 | **Revocation + move BatStateU over** | signed revocation list; the old single-registry flow retired |

## Decisions and limits
- **No accounts or passwords** (CLAUDE.md's "accounts/login out of scope" still holds). This is an **architecture change**, so CLAUDE.md's phase section gets updated with the team's OK.
- **New dependencies:** server `psycopg[binary]` (phase 2) for Postgres. The app needs none: Android Keystore and `java.security` are built in. The request goes out as **text** (share sheet), so the app needs no QR generator. The certificate comes back as a **QR**, which the app's existing scanner reads.
- **Supabase secrets** (`DATABASE_URL`) live only in Render's environment, never in Git or chat.
- The planned `wave-2026-b` campus key is replaced by the **root key**. The exposed `wave-2026-a` stays untrusted.
