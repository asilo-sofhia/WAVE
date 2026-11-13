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

App logic: `asn` in registry `campus_asns` → OK; otherwise → Warning. Request fails 3 times in 30 s → "Unverified – don't enter passwords".

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
