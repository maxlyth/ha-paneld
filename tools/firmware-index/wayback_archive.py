#!/usr/bin/env python3
"""Submit the firmware index + every firmware URL to the Wayback Machine.

The CoolKit OTA CDN can disappear or be refactored at any time, taking the
firmware with it. This preserves a copy in the Internet Archive:

  1. The index page (GitHub Discussion #7) is saved, with capture_outlinks=1
     when authenticated — a bonus snapshot of the release-note / repo links.
  2. Every firmware URL is submitted to Save Page Now *explicitly*. This is the
     reliable path: capture_outlinks caps at 100 links per request, far short of
     the ~196 firmware files, so we don't rely on the page crawler to find them.

Firmware URLs are immutable (a build's bytes never change), so each is archived
once and recorded in a state file; later runs only submit new ones.

Auth is required: Save Page Now rejects anonymous API calls. Set the WAYBACK_S3
env var to "accesskey:secret" (free archive.org account, then S3 keys at
https://archive.org/account/s3.php). Without it the script skips cleanly.

Usage:
  python wayback_archive.py --state wayback.json [--page-url URL] [--max N] [--workers N]
"""

import argparse
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from firmware_index import all_urls, load_devices

SAVE = "https://web.archive.org/save"
POLL_INTERVAL = 5
POLL_TRIES = 18                 # ~90s, within SPN's 2-minute capture ceiling
FILE_FRESHNESS = "365d"         # server-side skip if archived within the last year
_lock = threading.Lock()


def auth_header():
    key = os.environ.get("WAYBACK_S3", "").strip()
    return ({"Authorization": f"LOW {key}"}, True) if key else ({}, False)


def _post(url, fields):
    hdr, _ = auth_header()
    hdr.update({
        "Accept": "application/json",
        "Content-Type": "application/x-www-form-urlencoded",
        "User-Agent": "ha-paneld-firmware-archiver",
    })
    body = urllib.parse.urlencode(fields).encode()
    req = urllib.request.Request(SAVE, data=body, headers=hdr, method="POST")
    return _read(req)


def _get_status(job_id):
    hdr, _ = auth_header()
    hdr.update({"Accept": "application/json", "User-Agent": "ha-paneld-firmware-archiver"})
    req = urllib.request.Request(f"{SAVE}/status/{job_id}", headers=hdr)
    return _read(req)


def _read(req):
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace") if e.fp else ""
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {"status": "error", "http": e.code, "raw": raw[:200]}
    except Exception as e:  # noqa: BLE001 — network flake; caller retries next run
        return {"status": "error", "exc": str(e)}
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"status": "error", "raw": raw[:200]}


def archive(url, outlinks, poll):
    """Submit url to SPN; optionally poll to completion. Returns (ok, info)."""
    fields = {"url": url, "skip_first_archive": "1"}
    if not outlinks:
        fields["if_not_archived_within"] = FILE_FRESHNESS
    else:
        fields["capture_outlinks"] = "1"
        fields["capture_all"] = "1"

    resp = _post(url, fields)
    job = resp.get("job_id")
    if not job:
        return False, resp                         # rate-limited / blocked / error
    if not poll:
        return True, {"job_id": job, "status": "submitted"}

    for _ in range(POLL_TRIES):
        time.sleep(POLL_INTERVAL)
        st = _get_status(job)
        s = st.get("status")
        if s == "success":
            return True, st
        if s == "error":
            return False, st
    return False, {"job_id": job, "status": "pending"}   # timed out — retry next run


def load_state(path):
    try:
        with open(path) as fh:
            s = json.load(fh)
    except (FileNotFoundError, json.JSONDecodeError):
        s = {}
    s.setdefault("files", {})
    return s


def save_state(path, s):
    with open(path, "w") as fh:
        json.dump(s, fh, indent=1, sort_keys=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--state", required=True)
    ap.add_argument("--page-url", help="index page to save (with outlinks if authenticated)")
    ap.add_argument("--max", type=int, default=0, help="cap new firmware URLs per run (0 = all)")
    ap.add_argument("--workers", type=int, default=0, help="concurrent captures (0 = auto)")
    args = ap.parse_args()

    _, authed = auth_header()
    if not authed:
        print("WAYBACK_S3 secret not set — skipping (Save Page Now requires authentication).")
        print("Create a free archive.org account, generate S3 keys at")
        print("https://archive.org/account/s3.php, and add them as the WAYBACK_S3 Actions")
        print("secret in the form  accesskey:secret  to enable archiving.")
        return 0
    workers = args.workers or 8                       # SPN authenticated concurrency cap is 12
    print(f"Wayback archiver — authenticated, {workers} workers")

    state = load_state(args.state)
    files = state["files"]

    if args.page_url:
        ok, info = archive(args.page_url, outlinks=True, poll=True)
        state["page"] = {"ts": int(time.time()), "ok": ok, "wb": info.get("timestamp")}
        save_state(args.state, state)
        print(f"page: {'archived (+outlinks)' if ok else 'pending/failed'} — {info.get('status')}")

    urls = all_urls(load_devices())
    todo = [u for u in urls if u not in files]
    if args.max > 0:
        todo = todo[:args.max]
    print(f"firmware: {len(files)} archived, {len(urls) - len(files)} remaining, submitting {len(todo)} now")

    def work(u):
        ok, info = archive(u, outlinks=False, poll=True)
        if ok:
            with _lock:
                files[u] = {"ts": int(time.time()), "wb": info.get("timestamp")}
                save_state(args.state, state)
        else:
            print(f"  retry-next-run: {u} ({info.get('status')}/{info.get('status_ext', info.get('http', ''))})",
                  file=sys.stderr)
        return ok

    done = 0
    if todo:
        with ThreadPoolExecutor(max_workers=workers) as ex:
            done = sum(1 for ok in ex.map(work, todo) if ok)
    print(f"archived {done}/{len(todo)} this run; {len(urls) - len(files)} firmware URLs still remaining")
    return 0


if __name__ == "__main__":
    sys.exit(main())
