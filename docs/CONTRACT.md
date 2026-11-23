# WAVE Data Contract (v1)

Everything the team builds reads or writes these formats. **Change this file only by team agreement**, and bump the version when you do.

Owner: John Vincent Doria (backend) · Status: DRAFT for team review · Last updated: Nov 10, 2026

---

## 1. Keys and signatures

| Item | Rule |
|---|---|
| Algorithm | Ed25519 (RFC 8032). Public key = 32 bytes, signature = 64 bytes. |
| Encoding | **base64url without padding** everywhere (public keys, signatures, byte blobs). Android: `java.util.Base64.getUrlDecoder()`. |
| Key ID | Short name such as `wave-2026-a`. Every signed item names the key that signed it. |
| Private key | Lives **only** on the offline signing laptop (`keys/*.private.pem`, passphrase-protected). Never in Git, never on the server. |
| Public key | **Built into the app** as a constant (`key_id` → base64url). The app never downloads public keys from the server. |
| Domain separation | The signed bytes always start with a fixed label, so a registry signature can never be reused as a QR signature (or the reverse):<br>• Registry: `"WAVE-REGISTRY-v1\n"` + raw file bytes<br>• QR code: `"WAVE-QR-v1\n"` + QR text up to the signature field |

---

## 2. `registry.json`

### Rule #1: sign and verify the exact file bytes
The app verifies the **raw downloaded bytes** first and only then parses JSON. Never parse → re-serialize → verify.

### Format
```json
{
  "schema": "wave-registry/1",
  "version": 1,
  "key_id": "wave-2026-a",
  "issued_at": "2026-11-10T00:00:00Z",
  "expires_at": "2026-12-10T00:00:00Z",
  "campus_asns": [
    { "asn": 0, "name": "TBD - measure on campus" }
  ],
  "networks": [
    {
      "ssid": "BSU-ICT-TESTLAB",
      "security": "WPA2-PSK",
      "aps": [
        {
          "bssid": "00:00:5e:00:53:01",
          "band": "2.4GHz",
          "channel": 6,
          "building": "CICS Lab",
          "rssi_min": -85,
          "rssi_max": -25
        }
      ]
    }
  ],
  "blocked": [
    { "bssid": "6a:3f:00:11:22:9c", "ssid": "BSU Free WiFi", "reason": "Rogue hotspot seen in the canteen" }
  ]
}
```

| Field | Type | Meaning |
|---|---|---|
| `schema` | string | Always `wave-registry/1` for this contract version. |
| `version` | int ≥ 1 | Goes up every time the registry is re-signed. **The app rejects a registry whose version is lower than its cached one** (stops rollback to an old list). |
| `key_id` | string | Which key signed it. Must match the `.sig` file's key. |
| `issued_at` / `expires_at` | ISO-8601 UTC | App treats an expired registry as stale: still usable offline, but shows "registry out of date" and tries to re-sync. |
| `campus_asns` | list | ASNs the campus traffic legitimately exits through. Filled after the campus visit. |
| `blocked` | list, **optional** | Access points the campus admin marked as **not theirs** (`bssid` lowercase, `ssid` as seen, `reason`). Phones show them as **Warning: "Blocked by campus admin"**, whatever name they use. A BSSID can't be both in `blocked` and in `networks[].aps`. Missing = empty. |
| `networks[].ssid` | string | Exact SSID, case-sensitive. |
| `networks[].security` | enum | `OPEN`, `WPA2-PSK`, `WPA3-SAE`, `WPA2-WPA3` (transition mode). |
| `aps[].bssid` | string | Lowercase, colon-separated `xx:xx:xx:xx:xx:xx`. One entry **per radio** (a dual-band AP has two). |
| `aps[].band` | enum | `2.4GHz`, `5GHz`, `6GHz`. |
| `aps[].channel` | int | Channel seen during the survey. Informational. |
| `aps[].building` | string | Where the AP is. |
| `aps[].rssi_min` / `rssi_max` | int (dBm) | Normal signal range for that AP in the surveyed area. A reading far outside it is suspicious. |

### Signature file: `registry.json.sig`
```json
{ "key_id": "wave-2026-a", "alg": "Ed25519", "signature": "<base64url, 86 chars>" }
```

### Android scan → registry `security` mapping
Android's `ScanResult.capabilities` string, e.g. `[RSN-PSK-CCMP][ESS]`:

| capabilities contains | registry `security` |
|---|---|
| `PSK` and `SAE` (e.g. `RSN-PSK+SAE-CCMP`) | `WPA2-WPA3` |
| `SAE` only | `WPA3-SAE` |
| `PSK` only | `WPA2-PSK` |
| `EAP` | out of scope (Enterprise) → `Unknown` |
| `OWE` | out of scope → `Unknown` |
| none of the above | `OPEN` |

---

## 3. Signed Wi-Fi QR code

### Format
A standard `WIFI:` string with three extra fields. **`WAVE-SIG` must be the last field.**
```
WIFI:T:WPA;S:BSU-ICT-TESTLAB;P:<password>;WAVE-KID:wave-2026-a;WAVE-UNTIL:20261210;WAVE-SIG:<base64url 86 chars>;;
```

| Field | Meaning |
|---|---|
| `T` | `WPA` (WPA2/mixed), `SAE` (WPA3-only), `nopass` (open). Standard field. |
| `S`, `P` | SSID and password. Standard fields. Special characters `\ ; , : "` are escaped with a backslash. |
| `WAVE-KID` | Key ID that signed the code. |
| `WAVE-UNTIL` | Last valid date, `YYYYMMDD`, inclusive. |
| `WAVE-SIG` | Ed25519 signature, base64url. |

Field names were chosen so they **do not end in a letter that standard parsers look for** (`T S P H R I K E A`), so simple parsers that search for `"S:"` or `"P:"` are not confused.

### What is signed
`"WAVE-QR-v1\n"` + the UTF-8 QR text **from `WIFI:` up to and including the `;` just before `WAVE-SIG:`**.

### App verification steps (Kotlin must match `wave_sign.py verify-qr`)
1. Text must start with `WIFI:` and end with `;;`. Otherwise → `MALFORMED`.
2. Find `;WAVE-SIG:`. Not found → `UNSIGNED`. Found more than once → `MALFORMED`.
3. After the signature value there must be exactly `;;` and nothing else → otherwise `MALFORMED` (stops someone appending unsigned fields).
4. Read `WAVE-KID`. Not in the app's built-in keys → `UNKNOWN_KEY`.
5. Verify the signature. Fails → `BAD_SIGNATURE`.
6. Today (device local date) after `WAVE-UNTIL` → `EXPIRED`.
7. Otherwise → `VALID`. Then look up `S` + `T` in the registry.

| Result | User sees |
|---|---|
| `VALID` + in registry | ✅ "Signed by WAVE. Still checking nearby access points…" |
| `VALID` + not in registry | ⚠️ Unknown |
| `UNSIGNED`, `UNKNOWN_KEY` | ⚠️ Unknown – "Not issued by your campus" |
| `BAD_SIGNATURE`, `MALFORMED` | ⛔ Warning – "This QR code was altered" |
| `EXPIRED` | ⚠️ Unknown – "This QR code has expired" |

> **Security note:** a valid signature proves the *payload* is genuine, not that the AP you join is genuine. A signed QR code can be photocopied and posted next to a twin. **After a QR check, the app still runs the BSSID classification** on the networks it actually sees.

### Open item
- [ ] Test on every team phone: does the stock camera / Google Lens still offer "Join network" when the extra `WAVE-*` fields are present? If not, fall back to a WAVE-only QR format (`WAVE1.<payload>.<sig>`).

---

## 4. Server API (FastAPI)

Base URL: `https://<wave-server>/v1`. HTTPS only. The server must be on the **public internet** so it sees the phone's public IP.

### `GET /v1/registry`
```json
{
  "key_id": "wave-2026-a",
  "registry_b64url": "<raw bytes of registry.json>",
  "signature_b64url": "<64-byte signature>"
}
```
Delivered in one response so the file and signature can never be mismatched. The outer `key_id` is only a hint; the app trusts only what is inside the verified bytes.

### `GET /v1/exit-path`
```json
{
  "public_ip": "203.0.113.7",
  "asn": 4775,
  "as_name": "Globe Telecoms",
  "checked_at": "2026-11-10T03:00:00Z"
}
```
Header `Cache-Control: no-store`. **The app must send this request over the Wi-Fi `Network` object** (`network.socketFactory` / `network.openConnection()`), or Android may route it over mobile data.

App logic: the check runs only on networks the registry already marks **Verified** (an unregistered network never exits through campus, so a mismatch there says nothing). `asn` in registry `campus_asns` → OK; otherwise → Warning. No usable answer (error, timeout, or `asn` null) 3 times in 30 s → **Server unreachable** – "don't enter passwords yet". The verdict stays as it is. A registry with no real campus ASN (empty, or only the placeholder `0`) can't judge anything: the app doesn't ask the server and shows the same Unreachable state, never Warning.

### `GET /v1/health`
`{ "status": "ok" }`

---

## 5. Lab-trial log row (Room table `scan_log`)
| Column | Example |
|---|---|
| `ts_ms` | 1762740000000 |
| `trial_id` | `R1-T03` |
| `ssid`, `bssid`, `security`, `rssi`, `freq_mhz` | from `ScanResult` |
| `verdict` | `VERIFIED` / `UNKNOWN` / `WARNING` |
| `reason` | `UNREGISTERED_BSSID`, `LOCALLY_ADMINISTERED`, `SECURITY_MISMATCH`, `RSSI_OUT_OF_RANGE`, `EXIT_ASN_MISMATCH`, … |

Export as CSV for the evaluation metrics.

---

## 6. Admin certificates (place admins)
A **place admin** (e.g. the BatStateU IT office, a mall) publishes their place's registry from their phone. The WAVE owner certifies each admin once with the **root key**:

```
wave-root-2026 (Ed25519, offline on the owner's laptop; public key in the app and on the server)
  └─ admin certificate: "this phone key may sign for place <place_id> until <date>"
       └─ the admin's phone key (P-256, Android Keystore) signs that place's registry (§7)
```

**Enrolment request** (shared by the admin's phone as text): `WAVEREQ:1:` + base64url of
```json
{ "admin_name": "Ana Cruz", "place_id": "batstateu", "admin_key_alg": "ES256", "admin_public_key": "<base64url SPKI DER, P-256>" }
```

**Certificate** (given back as a QR): `WAVECERT:1:<cert_b64url>.<signature_b64url>`. The signature is Ed25519 by the root over `"WAVE-CERT-v1\n"` + the exact certificate bytes (compact JSON, sorted keys; never re-serialized to verify):
```json
{
  "schema": "wave-admin-cert/1",
  "cert_id": "3f9a1c0d2b7e4a51",
  "place_id": "batstateu",
  "place_name": "Batangas State University",
  "admin_name": "Ana Cruz",
  "admin_key_alg": "ES256",
  "admin_public_key": "<base64url SPKI DER, P-256>",
  "issued_at": "2026-11-24T00:00:00Z",
  "expires_at": "2027-11-24T00:00:00Z",
  "root_key_id": "wave-root-2026"
}
```

| Field | Rule |
|---|---|
| `place_id` | `^[a-z0-9][a-z0-9-]{0,31}$`; the certificate is only valid for this place |
| `cert_id` | 16 lowercase hex characters, random |
| `admin_key_alg` | always `ES256` (ECDSA P-256 + SHA-256) |
| times | ISO UTC with `Z`; `expires_at` after `issued_at` |

**Results:** `VALID`, `MALFORMED`, `UNKNOWN_KEY` (root not trusted), `BAD_SIGNATURE`, `NOT_YET_VALID`, `EXPIRED`, checked in that order. A certificate is valid when `issued_at ≤ t ≤ expires_at`.

**Only root keys certify admins.** Certificates are checked against the **root key set** (`wave-root-*`) only, never against the §1 registry/QR keys. A certificate "signed" by `wave-2026-a`, `wave-dev` or any other non-root key is `UNKNOWN_KEY`.

## 7. Place registry packages
A place registry is a §2 registry plus `"place_id"`. Its `key_id` is the signing certificate's `cert_id`. The admin's phone key signs `"WAVE-REGISTRY-v1\n"` + the raw registry bytes (ECDSA P-256, SHA-256, DER signature). It is published as one package:
```json
{ "place_id": "batstateu", "registry_b64url": "...", "signature_b64url": "...", "cert": "WAVECERT:1:..." }
```

**A package is valid only if all of these hold, checked in this order:**
1. The registry is a valid §2 registry with a `place_id`. Otherwise `MALFORMED`.
2. The certificate is `VALID` (§6) **at the registry's `issued_at`**, and the registry's `expires_at` is not later than the certificate's. Otherwise its result, or `EXPIRED`. Without the second part, an admin whose certificate expired could keep publishing by backdating `issued_at`.
3. The certificate's, the package's and the registry's `place_id` are the same. Otherwise `WRONG_PLACE`.
4. The registry's `key_id` equals the certificate's `cert_id`. Otherwise `MALFORMED`.
5. The registry signature verifies with the certificate's `admin_public_key`. Otherwise `BAD_SIGNATURE`.

**The server also checks at upload time:** the certificate must be valid *now*, and `issued_at` may be at most 5 minutes in the future. Phones skip these two checks, because they have no trusted clock.

**Later phases** add: the server stores packages and refuses lower versions per place; phones check every place's package and merge them; a revocation list signed by the root (by `cert_id`).
