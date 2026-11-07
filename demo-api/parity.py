#!/usr/bin/env python3
"""Check that a deployed demo API answers exactly like the starters' test mock.

    python3 parity.py [BASE]   # BASE defaults to https://vgi-starter-api.query-farm.services

Starts shared/test/mock_api/server.py on a free port and compares parsed JSON for
every page size and page, plus the auth and error paths.
"""
import json, subprocess, sys, urllib.error, urllib.request
from pathlib import Path

BASE = (sys.argv[1] if len(sys.argv) > 1 else "https://vgi-starter-api.query-farm.services").rstrip("/")
MOCK = Path(__file__).resolve().parent.parent / "shared/test/mock_api/server.py"

def get(url, token=None):
    req = urllib.request.Request(url, headers={"User-Agent": "vgi-starter-api-parity"})
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.load(e)

proc = subprocess.Popen([sys.executable, str(MOCK)], stdout=subprocess.PIPE, text=True)
try:
    port = proc.stdout.readline().strip().split(":")[1]
    mock = f"http://127.0.0.1:{port}"
    cases = [f"/items?page={p}&page_size={s}" for s in (1, 7, 10, 25, 100) for p in (1, 2, 3, 4)]
    cases += ["/items", "/items?page=0", "/items?page_size=0", "/items?page_size=1001", "/items?page=x"]
    bad = 0
    for c in cases:
        a, b = get(mock + c), get(BASE + "/v1" + c)
        if a[0] != b[0] or (a[0] == 200 and a[1] != b[1]):
            bad += 1
            print(f"MISMATCH {c}: mock={a} hosted={b}")
    s, _ = get(BASE + "/v1/auth/items")
    t, body = get(BASE + "/v1/auth/items?page=1&page_size=25", token="demo-token")
    if s != 401 or t != 200 or len(body["items"]) != 25:
        bad += 1
        print(f"AUTH: no-token={s} token={t}")
    print(f"{len(cases) + 1} checks, {bad} mismatches")
    sys.exit(1 if bad else 0)
finally:
    proc.terminate()
