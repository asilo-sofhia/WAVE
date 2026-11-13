# WAVE backend: signing tool + server

Two parts, both defined by **[CONTRACT.md](CONTRACT.md)** (read that first):
- **Signing tool** (offline, your laptop only): creates the Ed25519 key, signs `registry.json`, makes signed Wi-Fi QR codes, and writes test vectors for the Android verifier.
- **Server** (FastAPI, see [Server](#server)): serves the already-signed registry and answers the exit-path check. It never holds a private key.

```
wave-backend/
├── CONTRACT.md                  data formats the whole team follows
├── signing/wave_sign.py         the tool
├── registry/
│   ├── registry.json            campus registry (placeholders until the survey)
│   └── registry.home-dev.json   your home network, for at-home testing (git-ignored)
├── test-vectors/test_vectors.json  copy into the Android project for Step 2
├── tests/                       python -m pytest -q
├── keys/                        your keys go here (private keys are git-ignored)
├── out/                         generated QR images (git-ignored)
├── requirements-server.txt      server dependencies
└── server/                      FastAPI server (main.py, update_asn_db.py, data/ is git-ignored)
```

## Setup (Windows, macOS or Linux)
```bash
python -m venv .venv
# Windows: .venv\Scripts\activate     macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt
python -m pytest -q                 # signing tests pass; server tests are skipped until you install requirements-server.txt
```

## Daily use

**1. Make keys (once, on YOUR laptop, never on a server)**
```bash
# Real campus key, protected by a passphrase you will be asked for:
python signing/wave_sign.py keygen --key-id wave-2026-a
# Throwaway key for testing at home:
python signing/wave_sign.py keygen --key-id wave-dev --no-passphrase
```
It prints a line to paste into the Android app (`WAVE_PUBLIC_KEYS`). Back up `keys/wave-2026-a.private.pem` on a USB stick kept offline.

**2. Sign and check the registry**
```bash
python signing/wave_sign.py sign-registry registry/registry.home-dev.json --key keys/wave-dev.private.pem
python signing/wave_sign.py verify-registry registry/registry.home-dev.json --pub keys/wave-dev.public.json
```
The tool refuses to sign if the file has typos (uppercase BSSID, unknown security type, duplicate BSSID…). Every time you edit the registry, **increase `version`** and sign again.

**3. Make a signed Wi-Fi QR code**
```bash
python signing/wave_sign.py sign-qr --key keys/wave-dev.private.pem \
  --ssid GFiber_8C809 --type WPA --until 2026-12-10 \
  --registry registry/registry.home-dev.json --png out/home-qr.png
```
You will be asked for the Wi-Fi password, which keeps it out of your shell history. Add `--show > out/qr.txt` to save the text.

**4. Check a QR code** (same logic the app must follow)
```bash
python signing/wave_sign.py verify-qr @out/qr.txt --pub keys/wave-dev.public.json --registry registry/registry.home-dev.json
python signing/wave_sign.py verify-qr "WIFI:T:WPA;S:GFiber_8C809;P:x;;" --pub keys/wave-dev.public.json   # -> UNSIGNED
```
Results: `VALID`, `UNSIGNED`, `MALFORMED`, `UNKNOWN_KEY`, `BAD_SIGNATURE`, `EXPIRED`.

**5. Regenerate test vectors for the Kotlin verifier (Step 2)**
```bash
python signing/wave_sign.py test-vectors --out test-vectors/test_vectors.json
```

## Security rules
- The private key stays on the signing laptop. Not in Git, not on the server, not in chat.
- The app has the public key built in. It never downloads keys.
- Signatures cover the **exact file bytes**. Re-formatting the JSON breaks the signature (there's a test proving it).
- A valid QR signature proves the code is genuine, **not** that the nearby AP is. The app must still check BSSIDs.

## To do before Step 2
- [ ] Team reviews and approves CONTRACT.md.
- [ ] Scan `out/home-qr.png` with each team phone's **stock camera / Google Lens**. Does it still offer to join the network with the extra `WAVE-*` fields? Write down the results.
- [ ] Campus visit: measure the campus ASN and put it in `registry.json`.

## Server

FastAPI server for CONTRACT section 4. Read-only (three GET endpoints), no database, no accounts, no CORS, and **no private key**: it only serves files you signed on the laptop.

| Endpoint | Answer |
|---|---|
| `GET /v1/health` | `status`, `registry_loaded`, `registry_version`, `asn_db_loaded`, `asn_db_date` |
| `GET /v1/registry` | `key_id`, `registry_b64url`, `signature_b64url`: the exact signed bytes, re-read automatically after you re-sign |
| `GET /v1/exit-path` | `public_ip`, `asn`, `as_name`, `checked_at` (`Cache-Control: no-store`). A private/local address gets nulls and a `note`, never a guess |

All commands below run in `backend/` with the venv active (Python 3.11 or newer).

**1. Install**
```bash
pip install -r requirements-server.txt
```

**2. Get the IP-to-ASN data** (IPtoASN `ip2asn-combined.tsv.gz`, ~11 MB, Public Domain Dedication and License)
```bash
python server/update_asn_db.py
```
If the network blocks it, download https://iptoasn.com/data/ip2asn-combined.tsv.gz by hand and save it as `server/data/ip2asn-combined.tsv.gz`. Re-run weekly and restart the server. Without the file the server still starts, but `/v1/exit-path` answers 503.

**3. Run locally**
```bash
uvicorn server.main:app --host 127.0.0.1 --port 8000
```
Open http://127.0.0.1:8000/v1/health. Locally, `/v1/exit-path` only says it sees a private address: the server has to be reached over the internet to see your real public IP (step 4).

**4. Test with a phone at home through a Cloudflare quick tunnel**
1. Install `cloudflared` (https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/).
2. Start the server so it trusts the tunnel's header, serving your home registry:
   ```powershell
   # Windows PowerShell
   $env:WAVE_TRUSTED_PROXY = "cloudflare"; $env:WAVE_REGISTRY_FILE = "registry/registry.home-dev.json"
   uvicorn server.main:app --host 127.0.0.1 --port 8000
   ```
   ```bash
   # macOS / Linux
   WAVE_TRUSTED_PROXY=cloudflare WAVE_REGISTRY_FILE=registry/registry.home-dev.json uvicorn server.main:app --host 127.0.0.1 --port 8000
   ```
3. In a second terminal: `cloudflared tunnel --url http://localhost:8000`. It prints a `https://….trycloudflare.com` address.
4. On the phone, open `<tunnel-url>/v1/exit-path`. On home Wi-Fi it should show **AS4775 Globe**; on the DITO hotspot, DITO's ASN (**AS139831**).
Keep `--host 127.0.0.1`: in `cloudflare` mode the header is trusted only from this machine.

**Settings** (environment variables)
| Variable | Default | Meaning |
|---|---|---|
| `WAVE_REGISTRY_FILE` | `registry/registry.json` | Registry to serve; its signature is the same path + `.sig`. Relative paths are from `backend/`. |
| `WAVE_ASN_DB` | `server/data/ip2asn-combined.tsv.gz` | IPtoASN dataset |
| `WAVE_TRUSTED_PROXY` | `none` | How the phone's address is found. `none`: the connection's address only, all forwarding headers ignored. `cloudflare`: `CF-Connecting-IP`, only from loopback (cloudflared on the same machine). `forwarded`: right-most `X-Forwarded-For`, only from `WAVE_TRUSTED_PROXY_IPS`. |
| `WAVE_TRUSTED_PROXY_IPS` | (empty) | Comma-separated proxy addresses, for `forwarded` |

**Privacy:** the server never logs or stores client IPs itself. Uvicorn's console access log does print them; turn it off with `--no-access-log`.

**Not part of the server:** the Android app's exit-path client (sending the request over the Wi-Fi network and comparing `asn` with the registry's `campus_asns`) comes next.

Third-party code: `cryptography` (Apache-2.0/BSD), `qrcode` (BSD), `Pillow` (MIT-CMU), `pytest` (MIT); server: `fastapi` (MIT; with `starlette` BSD-3-Clause and `pydantic` MIT), `uvicorn[standard]` (BSD-3-Clause), `httpx` (BSD-3-Clause, tests only). Data: IPtoASN (PDDL).
