#!/usr/bin/env python3
"""Shelly Wall Display firmware monitor and Wayback Machine archiver.

Polls the Shelly OTA manifest endpoints for new firmware versions, preserves
them in the Internet Archive before Shelly replaces the CDN object in-place,
and renders the canonical firmware Discussion body.

Unlike Sonoff NSPanel Pro (CoolKit CDN retains all versions indefinitely),
Shelly's CDN keeps only the *current* build per product. Once a new version
ships, the previous firmware URL goes 404. Hourly polling + immediate archival
gives the best chance to capture every release before it disappears.

Data file (fw-shelly-walldisplay.dat):
    track|version|build_id|bytes|discovered|cdn_url|wayback_ts

Commands:
    probe   poll manifests, append new versions to .dat, set GITHUB_OUTPUT flags
    archive submit unarchived CDN URLs to Wayback Machine, fill wayback_ts
    render  emit the Discussion markdown body
"""

import argparse
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

try:
    import certifi
    _SSL_CONTEXT = ssl.create_default_context(cafile=certifi.where())
except ImportError:
    _SSL_CONTEXT = ssl.create_default_context()

MANIFESTS = {
    "WallDisplay": "https://updates.shelly.cloud/update/WallDisplay",
    "WallDisplayV2": "https://updates.shelly.cloud/update/WallDisplayV2",
}

TRACK_LABELS = {
    "WallDisplay": (
        "Legacy — armeabi-v7a",
        "Stargate (4\"), Atlantis, Pegasus (X2)",
    ),
    "WallDisplayV2": (
        "Modern — arm64-v8a",
        "Blake (XL), Jenna (X2i), Cally (X1i), Maverick (U1), Dayna (D1)",
    ),
}

PROBE_TIMEOUT = 20
SPN_URL = "https://web.archive.org/save"
AVAIL_URL = "https://archive.org/wayback/available"
WAVE_SIZE = 3          # keep waves small — firmware ZIPs are 30–40 MB each
DRAIN_WAIT = 90        # seconds between SPN waves
CONFIRM_WAIT = 180     # seconds to let captures settle before confirming


# --------------------------------------------------------------------------- #
# data file
# --------------------------------------------------------------------------- #

def load_dat(path):
    entries = []
    try:
        with open(path) as fh:
            for raw in fh:
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                parts = line.split("|")
                if len(parts) != 7:
                    continue
                track, version, build_id, bytes_str, discovered, cdn_url, wayback_ts = parts
                entries.append({
                    "track": track,
                    "version": version,
                    "build_id": build_id,
                    "bytes": int(bytes_str) if bytes_str else 0,
                    "discovered": discovered,
                    "cdn_url": cdn_url,
                    "wayback_ts": wayback_ts,
                })
    except FileNotFoundError:
        pass
    return entries


def save_dat(path, entries):
    lines = [
        "# Shelly Wall Display firmware archive — managed by shelly-firmware-monitor workflow",
        "# Source of truth: track|version|build_id|bytes|discovered|cdn_url|wayback_ts",
        "#",
        "# Tracks:",
        "#   WallDisplay    — legacy armeabi-v7a (Stargate 4\", Atlantis, Pegasus X2)",
        "#   WallDisplayV2  — modern arm64-v8a  (Blake/XL, Jenna/X2i, Cally/X1i, Maverick/U1, Dayna/D1)",
        "#",
        "# cdn_url:   the opaque hash-addressed CDN URL from the manifest at time of discovery.",
        "#            Valid only while this version is current; Shelly replaces in-place on each release.",
        "# wayback_ts: Wayback Machine capture timestamp (yyyymmddHHMMSS), filled by the archive step.",
        "#             Empty = archival pending. Use the Wayback URL for durable access to old versions.",
    ]
    for e in entries:
        lines.append("|".join([
            e["track"], e["version"], e["build_id"],
            str(e["bytes"]), e["discovered"], e["cdn_url"], e["wayback_ts"],
        ]))
    with open(path, "w") as fh:
        fh.write("\n".join(lines) + "\n")


def gha_output(name, value):
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a") as fh:
            fh.write(f"{name}={value}\n")
    else:
        print(f"  (output) {name}={value}")


# --------------------------------------------------------------------------- #
# probe
# --------------------------------------------------------------------------- #

def fetch_manifest(url):
    req = urllib.request.Request(url, headers={"User-Agent": "ha-paneld-shelly-monitor"})
    with urllib.request.urlopen(req, timeout=PROBE_TIMEOUT, context=_SSL_CONTEXT) as r:
        return json.load(r)


def head_size(url):
    req = urllib.request.Request(
        url, method="HEAD", headers={"User-Agent": "ha-paneld-shelly-monitor"})
    try:
        with urllib.request.urlopen(req, timeout=PROBE_TIMEOUT, context=_SSL_CONTEXT) as r:
            return int(r.headers.get("Content-Length") or 0)
    except Exception:
        return 0


def cmd_probe(args):
    entries = load_dat(args.dat)
    known = {(e["track"], e["version"]) for e in entries}
    new_entries = []

    for track, manifest_url in MANIFESTS.items():
        try:
            data = fetch_manifest(manifest_url)
        except Exception as exc:
            print(f"ERROR: could not fetch {track} manifest: {exc}", file=sys.stderr)
            continue
        stable = data.get("stable", {})
        version = stable.get("version", "")
        build_id = stable.get("build_id", "")
        cdn_url = stable.get("url", "")
        if not version or not cdn_url:
            print(f"WARN: {track} manifest missing version or url", file=sys.stderr)
            continue
        if (track, version) in known:
            print(f"known:  {track} {version}")
        else:
            size = head_size(cdn_url)
            today = time.strftime("%Y-%m-%d", time.gmtime())
            entry = {
                "track": track, "version": version, "build_id": build_id,
                "bytes": size, "discovered": today,
                "cdn_url": cdn_url, "wayback_ts": "",
            }
            new_entries.append(entry)
            print(f"NEW:    {track} {version} ({size / 1e6:.1f} MB)")

    if new_entries:
        entries.extend(new_entries)
        save_dat(args.dat, entries)
        gha_output("has_new", "true")
        gha_output("new_versions",
                   " ".join(f"{e['track']}/{e['version']}" for e in new_entries))
    else:
        gha_output("has_new", "false")
        gha_output("new_versions", "")

    pending = [e for e in entries if not e["wayback_ts"]]
    gha_output("has_pending", "true" if pending else "false")
    print(f"pending archival: {len(pending)}")
    return 0


# --------------------------------------------------------------------------- #
# archive
# --------------------------------------------------------------------------- #

def _wayback_auth():
    key = os.environ.get("WAYBACK_S3", "").strip()
    return (f"LOW {key}", True) if key else (None, False)


def _spn_request(url, body_fields, auth):
    headers = {
        "Accept": "application/json",
        "Content-Type": "application/x-www-form-urlencoded",
        "User-Agent": "ha-paneld-shelly-archiver",
    }
    if auth:
        headers["Authorization"] = auth
    body = urllib.parse.urlencode(body_fields).encode()
    req = urllib.request.Request(SPN_URL, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace") if e.fp else ""
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {"status": "error", "raw": raw[:200]}
    except Exception as exc:
        return {"status": "error", "exc": str(exc)}


def spn_submit(url, auth):
    resp = _spn_request(url, {
        "url": url,
        "skip_first_archive": "1",
        "if_not_archived_within": "365d",
    }, auth)
    return resp.get("job_id"), resp


def spn_available(url):
    q = AVAIL_URL + "?url=" + urllib.parse.quote(url, safe="")
    try:
        with urllib.request.urlopen(q, timeout=30) as r:
            d = json.load(r)
    except Exception:
        return None
    snap = d.get("archived_snapshots", {}).get("closest")
    return snap.get("timestamp") if snap else None


def cmd_archive(args):
    auth, authed = _wayback_auth()
    if not authed:
        print("WAYBACK_S3 not set — skipping archival. Set the secret to enable automatic "
              "Wayback Machine preservation.")
        return 0

    entries = load_dat(args.dat)
    pending = [e for e in entries if not e["wayback_ts"]]
    if not pending:
        print("All entries already archived.")
        return 0

    print(f"Archiving {len(pending)} entry/entries to Wayback Machine...")

    # Pass A: confirm anything already captured from prior submissions.
    def _check(e):
        return e, spn_available(e["cdn_url"])

    with ThreadPoolExecutor(max_workers=8) as ex:
        for entry, ts in ex.map(_check, pending):
            if ts:
                entry["wayback_ts"] = ts
                print(f"  pre-confirmed: {entry['track']} {entry['version']} ({ts})")

    still_pending = [e for e in pending if not e["wayback_ts"]]

    # Pass B: submit remaining, paced into waves.
    submitted = []
    waves = [still_pending[i:i + WAVE_SIZE] for i in range(0, len(still_pending), WAVE_SIZE)]
    for wi, wave in enumerate(waves, 1):
        for entry in wave:
            jid, resp = spn_submit(entry["cdn_url"], auth)
            if jid:
                submitted.append(entry)
                print(f"  submitted:  {entry['track']} {entry['version']} (job {jid})")
            else:
                ext = resp.get("status_ext") or resp.get("status") or repr(resp)
                print(f"  WARN: submit failed for {entry['track']} {entry['version']}: {ext}",
                      file=sys.stderr)
        if wi < len(waves):
            time.sleep(DRAIN_WAIT)

    # Pass C: confirm this run's submissions.
    if submitted:
        print(f"Waiting {CONFIRM_WAIT}s for captures to settle...")
        time.sleep(CONFIRM_WAIT)
        for entry in submitted:
            ts = spn_available(entry["cdn_url"])
            if ts:
                entry["wayback_ts"] = ts
                print(f"  confirmed:  {entry['track']} {entry['version']} ({ts})")
            else:
                print(f"  WARN: {entry['track']} {entry['version']} not confirmed yet "
                      f"(will retry next run)", file=sys.stderr)

    save_dat(args.dat, entries)
    done = sum(1 for e in pending if e["wayback_ts"])
    print(f"Archived {done}/{len(pending)} this run.")
    return 0


# --------------------------------------------------------------------------- #
# render
# --------------------------------------------------------------------------- #

_INTRO = """\
# Shelly Wall Display firmware — OTA download archive & upgrade guide

Direct download links for every recorded Shelly Wall Display firmware version, with Wayback Machine backups for versions that have since been replaced on the CDN.

Covers both generations:
- **Legacy (armeabi-v7a):** Stargate (4"), Atlantis, Pegasus (X2 6.9") — `WallDisplay` OTA track
- **Modern (arm64-v8a):** Blake (XL 10.1"), Jenna (X2i), Cally (X1i/XLi), Maverick (U1), Dayna (D1) — `WallDisplayV2` OTA track

> [!NOTE]
> **Why this archive exists:** Shelly's CDN (`fwcdn.shelly.cloud`) keeps only the *current* build per product and replaces it in-place when a new version ships — the previous firmware URL goes 404 immediately. This archive captures each version on discovery so older builds remain accessible even after a Shelly update.

> [!TIP]
> **Use the Wayback Machine link for any version that is not the latest.** The CDN link is valid while that version is live; once Shelly pushes a newer build the Wayback copy is the only surviving download.

## How to update your Shelly Wall Display firmware

The normal path (no PC needed):

1. Open the **Shelly app** → tap your Wall Display → **Settings → Device Information → Firmware**. If an update is available, tap **Update**.
2. Alternatively, open the local WebUI at `http://<device-ip>` → **Settings → Firmware** → **Update**.

Shelly rolls firmware out to the fleet gradually over several days, so your device may not see a new version immediately after release. If you need to apply a specific version manually, download the ZIP from the table below and flash it via the WebUI's **Firmware → Custom firmware** field (`http://<device-ip>/#/settings/firmware` on modern devices).

> [!IMPORTANT]
> Modern devices (arm64-v8a: Blake/XL, Jenna, Cally, Maverick, Dayna) and legacy devices (MT6580: Stargate, Pegasus) use **separate OTA tracks** and **incompatible firmware ZIPs** — do not flash WallDisplay firmware onto a WallDisplayV2 device or vice versa.

## Which version should I run?

The latest stable build is always the recommended choice — Shelly does not remove features between releases and has a good track record of fixing regressions quickly. Check the [official changelog](https://github.com/ShellyGroup/Wall-Display-Changelog) for what changed between versions.
"""

_FOOTER = """\
---

*This page is updated automatically when new Shelly Wall Display firmware is detected. The source data lives in [`tools/firmware-index/fw-shelly-walldisplay.dat`](https://github.com/maxlyth/ha-paneld/tree/main/tools/firmware-index/fw-shelly-walldisplay.dat). To add a missing version or report a correction, reply below or open an issue — a CDN URL + `curl -sI <url> | grep -i content-length` showing the file size is enough to add it.*
"""


def _human(b):
    return f"{b / 1048576:.1f} MB" if b else "—"


def _wb_link(ts, cdn_url):
    if not ts:
        return "*(pending)*"
    return f"[Wayback ↗](https://web.archive.org/web/{ts}/{cdn_url})"


def cmd_render(args):
    entries = load_dat(args.dat)

    def ver_key(e):
        try:
            return tuple(int(x) for x in e["version"].split("."))
        except ValueError:
            return (0,)

    blocks = [_INTRO]
    for track, (label, models) in TRACK_LABELS.items():
        track_entries = sorted(
            [e for e in entries if e["track"] == track], key=ver_key, reverse=True)
        blocks.append(f"## {label}")
        blocks.append(f"*Covers: {models}*\n")
        if not track_entries:
            blocks.append("*No entries recorded yet.*\n")
            continue
        rows = [
            "| Version | Discovered | Size | CDN link (current only) | Archive |",
            "| --- | --- | --- | --- | --- |",
        ]
        for e in track_entries:
            cdn = f"[{e['version']}]({e['cdn_url']})" if e["cdn_url"] else e["version"]
            rows.append(
                f"| **{e['version']}** | {e['discovered']} | {_human(e['bytes'])} "
                f"| {cdn} | {_wb_link(e['wayback_ts'], e['cdn_url'])} |"
            )
        blocks.append("\n".join(rows) + "\n")

    blocks.append(_FOOTER)
    body = "\n".join(blocks)

    if args.out:
        with open(args.out, "w") as fh:
            fh.write(body)
    else:
        sys.stdout.write(body)
    return 0


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #

def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("probe", help="check manifests for new versions, update .dat")
    p.add_argument("--dat", required=True)
    p.set_defaults(func=cmd_probe)

    a = sub.add_parser("archive", help="submit unarchived URLs to the Wayback Machine")
    a.add_argument("--dat", required=True)
    a.set_defaults(func=cmd_archive)

    r = sub.add_parser("render", help="emit the Discussion markdown body")
    r.add_argument("--dat", required=True)
    r.add_argument("--out", help="output file (default: stdout)")
    r.set_defaults(func=cmd_render)

    args = ap.parse_args()
    sys.exit(args.func(args))


if __name__ == "__main__":
    main()
