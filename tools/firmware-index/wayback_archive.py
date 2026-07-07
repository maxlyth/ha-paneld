#!/usr/bin/env python3
"""Submit the firmware index + every firmware URL to the Wayback Machine.

The CoolKit OTA CDN can disappear or be refactored at any time, taking the
firmware with it. This preserves a copy in the Internet Archive:

  1. The index page (GitHub Discussion #7) is saved with capture_outlinks=1 — a
     bonus snapshot of the release-note / repo links.
  2. Every firmware URL is submitted to Save Page Now *explicitly*, newest-first,
     so the current builds (4.6.0, 4.5.1, 4.4.0, 4.0.12, recent APKs) are preserved
     before the long tail. capture_outlinks caps at 100 links per request — far
     short of the ~196 files — so we don't rely on the page crawler to find them.

Save Page Now throttles to ~12 active captures per account and runs them
asynchronously, so submissions are **paced into waves** (submit a batch, wait for
it to drain, submit the next) rather than fired all at once and bounced. Status
is confirmed via the **availability API** (the job-status endpoint reports
pending/gateway-timeout long after the file is actually stored). Firmware URLs are
immutable, so each is archived once and recorded in the state file; later runs
only chase stragglers.

Auth is required: Save Page Now rejects anonymous API calls. Set the WAYBACK_S3
env var to "accesskey:secret" (free archive.org account, then S3 keys at
https://archive.org/account/s3.php). Without it the script skips cleanly.

Usage:
  python wayback_archive.py --state wayback.json [--page-url URL] [--max N]
                            [--wave-size N] [--drain-wait S] [--workers N]
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

from firmware_index import apk_url, diff_url, full_url, load_devices, vkey

SAVE = "https://web.archive.org/save"
AVAIL = "https://archive.org/wayback/available"
FILE_FRESHNESS = "365d"      # server-side: skip re-capture if archived within a year
WAVE_SIZE = 8                # submissions per wave (SPN active-capture cap ~12)
DRAIN_WAIT = 75              # seconds between waves, to let captures finish + free slots
CONFIRM_WAIT = 120           # seconds before confirming this run's submissions
CONFIRM_WORKERS = 16
_lock = threading.Lock()


def auth_header():
    key = os.environ.get("WAYBACK_S3", "").strip()
    return ({"Authorization": f"LOW {key}"}, True) if key else ({}, False)


def _read(req):
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace") if e.fp else ""
    except Exception as e:  # noqa: BLE001 — network flake; caller retries next run
        return {"status": "error", "exc": str(e)}
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"status": "error", "raw": raw[:200]}


def submit(url, outlinks=False):
    """Fire a Save Page Now capture. Returns (job_id_or_None, raw_response)."""
    hdr, _ = auth_header()
    hdr.update({
        "Accept": "application/json",
        "Content-Type": "application/x-www-form-urlencoded",
        "User-Agent": "ha-paneld-firmware-archiver",
    })
    fields = {"url": url, "skip_first_archive": "1"}
    if outlinks:
        fields["capture_outlinks"] = "1"
        fields["capture_all"] = "1"
    else:
        fields["if_not_archived_within"] = FILE_FRESHNESS
    body = urllib.parse.urlencode(fields).encode()
    resp = _read(urllib.request.Request(SAVE, data=body, headers=hdr, method="POST"))
    return resp.get("job_id"), resp


def available(url):
    """Return the snapshot timestamp if the URL is in the Wayback Machine, else None."""
    q = f"{AVAIL}?url=" + urllib.parse.quote(url, safe="")
    try:
        with urllib.request.urlopen(q, timeout=30) as r:
            d = json.load(r)
    except Exception:  # noqa: BLE001
        return None
    snap = d.get("archived_snapshots", {}).get("closest")
    return snap.get("timestamp") if snap else None


def load_state(path):
    try:
        with open(path) as fh:
            s = json.load(fh)
    except (FileNotFoundError, json.JSONDecodeError):
        s = {}
    s.setdefault("files", {})
    return s


def save_state(path, s):
    with _lock:
        with open(path, "w") as fh:
            json.dump(s, fh, indent=1, sort_keys=True)


def confirm_batch(state, path, urls):
    """Mark every URL the availability API reports as archived. Returns count."""
    files = state["files"]
    hits = 0

    def check(u):
        return u, available(u)

    with ThreadPoolExecutor(max_workers=CONFIRM_WORKERS) as ex:
        for u, ts in ex.map(check, urls):
            if ts:
                files[u] = {"ts": int(time.time()), "wb": ts}
                hits += 1
    if hits:
        save_state(path, state)
    return hits


def priority_urls(devices):
    """Newest / highest-value first, so current builds are preserved before the long tail."""
    out = []
    for d in devices:
        for ver, idx, fn, _sz in sorted(d["fulls"], key=lambda x: vkey(x[0]), reverse=True):
            out.append(full_url(d, idx, fn))
        for to, frm, idx, _sz in sorted(d["diffs"], key=lambda x: (vkey(x[0]), vkey(x[1])), reverse=True):
            out.append(diff_url(d, idx, frm, to))
        for ver, idx, _sz in sorted(d["apks"], key=lambda x: vkey(x[0]), reverse=True):
            out.append(apk_url(d, idx, ver))
    seen, res = set(), []
    for u in out:
        if u not in seen:
            seen.add(u)
            res.append(u)
    return res


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--state", required=True)
    ap.add_argument("--page-url", help="index page to save (with outlinks)")
    ap.add_argument("--max", type=int, default=0, help="cap captures submitted per run (0 = all)")
    ap.add_argument("--workers", type=int, default=6, help="concurrent submissions within a wave")
    ap.add_argument("--wave-size", type=int, default=WAVE_SIZE)
    ap.add_argument("--drain-wait", type=int, default=DRAIN_WAIT)
    ap.add_argument("--confirm-wait", type=int, default=CONFIRM_WAIT)
    args = ap.parse_args()

    _, authed = auth_header()
    if not authed:
        print("WAYBACK_S3 secret not set — skipping (Save Page Now requires authentication).")
        print("Create a free archive.org account, generate S3 keys at")
        print("https://archive.org/account/s3.php, and add them as the WAYBACK_S3 Actions")
        print("secret in the form  accesskey:secret  to enable archiving.")
        return 0

    state = load_state(args.state)
    files = state["files"]
    urls = priority_urls(load_devices())

    # The index page: fire a capture with outlinks (bonus archive of the linked pages).
    if args.page_url:
        jid, info = submit(args.page_url, outlinks=True)
        state["page"] = {"ts": int(time.time()), "job": jid, "ok": bool(jid)}
        save_state(args.state, state)
        print(f"page: {'submitted (+outlinks)' if jid else 'submit failed'} — {info.get('status', 'ok')}")

    # Pass A — confirm anything already archived (catches prior runs' async completions).
    got = confirm_batch(state, args.state, [u for u in urls if u not in files])
    print(f"confirmed {got} already-archived; {len(files)}/{len(urls)} done")

    # Pass B — fire captures newest-first, paced into waves to respect SPN's active cap.
    todo = [u for u in urls if u not in files]
    if args.max > 0:
        todo = todo[:args.max]

    def fire(u):
        jid, info = submit(u)
        if not jid:
            print(f"  defer (retry next run): {u} ({info.get('status_ext') or info.get('status')})", file=sys.stderr)
        return u, bool(jid)

    submitted = []
    waves = [todo[i:i + args.wave_size] for i in range(0, len(todo), args.wave_size)]
    for wi, wave in enumerate(waves, 1):
        accepted = 0
        with ThreadPoolExecutor(max_workers=args.workers) as ex:
            for u, ok in ex.map(fire, wave):
                if ok:
                    submitted.append(u)
                    accepted += 1
        print(f"  wave {wi}/{len(waves)}: {accepted}/{len(wave)} accepted")
        if wi < len(waves):
            time.sleep(args.drain_wait)
    print(f"submitted {len(submitted)}/{len(todo)} captures across {len(waves)} wave(s)")

    # Pass C — let them settle, then confirm this run's submissions.
    if submitted:
        time.sleep(args.confirm_wait)
        got = confirm_batch(state, args.state, submitted)
        print(f"confirmed {got}/{len(submitted)} of this run's submissions")

    save_state(args.state, state)
    remaining = len(urls) - len(files)
    print(f"TOTAL: {len(files)}/{len(urls)} firmware URLs archived"
          + (f"; {remaining} still pending (will confirm next run)" if remaining else " — complete"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
