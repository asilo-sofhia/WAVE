"""Download the IPtoASN dataset (IPv4 + IPv6) into backend/server/data/.

    python server/update_asn_db.py            (run from backend/)

Dataset: https://iptoasn.com/ - "ip2asn-combined.tsv.gz", Public Domain Dedication and License (PDDL).
It is updated about hourly upstream; re-run this every week or so. Restart the server afterwards.

If this machine can't reach iptoasn.com (blocked network), download the file by hand from
    https://iptoasn.com/data/ip2asn-combined.tsv.gz
and save it as backend/server/data/ip2asn-combined.tsv.gz (or point WAVE_ASN_DB at it).
"""
from __future__ import annotations

import gzip
import shutil
import sys
import urllib.request
from pathlib import Path

URL = "https://iptoasn.com/data/ip2asn-combined.tsv.gz"
TARGET = Path(__file__).resolve().parent / "data" / "ip2asn-combined.tsv.gz"
TIMEOUT_S = 120


def main() -> int:
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    tmp = TARGET.with_suffix(".gz.part")
    print(f"Downloading {URL} ...")
    try:
        req = urllib.request.Request(URL, headers={"User-Agent": "wave-server-asn-updater"})
        with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp, open(tmp, "wb") as out:
            shutil.copyfileobj(resp, out)
    except OSError as exc:
        tmp.unlink(missing_ok=True)
        print(f"Download failed: {exc}", file=sys.stderr)
        print(f"Download it by hand from {URL}\nand save it as {TARGET}", file=sys.stderr)
        return 1

    # Only replace the old file if the new one is a complete gzip with the expected columns.
    try:
        with gzip.open(tmp, "rt", encoding="utf-8", errors="replace") as f:
            rows = 0
            for line in f:
                if rows == 0 and len(line.rstrip("\n").split("\t")) != 5:
                    raise ValueError("unexpected format (expected 5 tab-separated columns)")
                rows += 1
    except (OSError, EOFError, ValueError) as exc:
        tmp.unlink(missing_ok=True)
        print(f"Downloaded file is not a valid dataset: {exc}", file=sys.stderr)
        return 1

    tmp.replace(TARGET)
    print(f"Saved {rows:,} ranges to {TARGET} ({TARGET.stat().st_size / 1e6:.1f} MB). Restart the server to load it.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
