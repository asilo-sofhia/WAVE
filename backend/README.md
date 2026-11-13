# WAVE backend: Step 1, signing tool

Offline signing station for WAVE. It creates the Ed25519 key, signs `registry.json`, makes signed Wi-Fi QR codes, and writes test vectors for the Android verifier. All formats are defined in **[CONTRACT.md](CONTRACT.md)**. Read that first.

```
wave-backend/
├── CONTRACT.md                  data formats the whole team follows
├── signing/wave_sign.py         the tool
├── registry/
│   ├── registry.json            campus registry (placeholders until the survey)
│   └── registry.home-dev.json   your home network, for at-home testing (git-ignored)
├── test-vectors/test_vectors.json  copy into the Android project for Step 2
├── tests/test_wave_sign.py      20 tests (python -m pytest -q)
├── keys/                        your keys go here (private keys are git-ignored)
└── out/                         generated QR images (git-ignored)
```

## Setup (Windows, macOS or Linux)
```bash
python -m venv .venv
# Windows: .venv\Scripts\activate     macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt
python -m pytest -q                 # should print: 20 passed
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

Third-party code: `cryptography` (Apache-2.0/BSD), `qrcode` (BSD), `Pillow` (MIT-CMU), `pytest` (MIT).
