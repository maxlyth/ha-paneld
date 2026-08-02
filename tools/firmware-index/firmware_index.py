#!/usr/bin/env python3
"""NSPanel Pro firmware OTA index — generator and URL availability monitor.

The firmware download links published in GitHub Discussion #7 are point-in-time
hits against the CoolKit OTA CDN (the bucket cannot be listed, so each URL is an
exact-filename probe). This script keeps that page honest in two ways:

  probe   range-GET every URL, record up/down into a rolling 7-day history file
  render  emit the Discussion markdown body, with a 7-day availability sparkline
          (green/red/grey squares) on every download row

Both subcommands derive their URLs from the same builders, so the squares line
up exactly with the table rows. The CI workflow runs `probe` then `render` on a
schedule and pushes the result to the Discussion; the history lives on a
dedicated data branch so the main history stays clean.

Data files (`fw-120p.dat`, `fw-86p.dat`) are the source of truth. Format:

    channel   <cdn-channel>
    diffsuffix <suffix-or-empty>          # 120P uses V228, 86P is bare
    apkfmt    <apk-filename-prefix>       # 86P "app", 120P "228V"
    full|<ver>|<idx>|<filename>|<bytes>
    diff|<target>|<idx>|<from>:<bytes>|<from>:<bytes>...
    apk|<ver>|<idx>|<bytes>
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

HOST = "https://global-otadl2bsy.coolkit.cc"
HERE = os.path.dirname(os.path.abspath(__file__))

# (display name, sub-title, data file) — render order is top to bottom.
DEVICES = [
    ("120P", "750×1334, rk3326-S", "fw-120p.dat"),
    ("86P", "480×480, PX30", "fw-86p.dat"),
]

WINDOW_HOURS = 24 * 7    # 7-day rolling window
MAX_POINTS = 7           # one dot per day
PROBE_TIMEOUT = 20
PROBE_WORKERS = 16
GITHUB_BODY_LIMIT = 65536

UP, DOWN, NODATA = "🟩", "🟥", "⬜"


# --------------------------------------------------------------------------- #
# data parsing + URL builders (shared by render and probe)
# --------------------------------------------------------------------------- #

def human(b):
    return f"{b} ({b / 1048576:.1f} MB)"


def vkey(v):
    return tuple(int(x) for x in v.split("."))


def parse(path):
    d = {"fulls": [], "diffs": [], "apks": [], "suffix": "", "apkfmt": "", "channel": ""}
    with open(path) as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith("channel "):
                d["channel"] = line.split(" ", 1)[1].strip()
            elif line.startswith("diffsuffix"):
                parts = line.split(" ", 1)
                d["suffix"] = parts[1].strip() if len(parts) > 1 else ""
            elif line.startswith("apkfmt "):
                d["apkfmt"] = line.split(" ", 1)[1].strip()
            elif line.startswith("full|"):
                _, ver, idx, fn, sz = line.split("|")
                d["fulls"].append((ver, idx, fn, int(sz)))
            elif line.startswith("diff|"):
                p = line.split("|")
                to, idx = p[1], p[2]
                for fs in p[3:]:
                    frm, sz = fs.split(":")
                    d["diffs"].append((to, frm, idx, int(sz)))
            elif line.startswith("apk|"):
                _, ver, idx, sz = line.split("|")
                d["apks"].append((ver, idx, int(sz)))
    return d


def full_url(d, idx, fn):
    return f"{HOST}/{d['channel']}/rom/{idx}/{fn}"


def diff_url(d, idx, frm, to):
    return f"{HOST}/{d['channel']}/rom-diff/{idx}/CK_{frm}_{to}{d['suffix']}-diff.zip"


def apk_url(d, idx, ver):
    return f"{HOST}/{d['channel']}/apk/{idx}/{d['apkfmt']}{ver}.apk"


def all_urls(devices):
    """Every downloadable URL across all devices (deduped, stable order)."""
    return list(all_url_sizes(devices))


def all_url_sizes(devices):
    """Every downloadable URL mapped to its expected total byte size."""
    urls = {}
    for d in devices:
        for ver, idx, fn, sz in d["fulls"]:
            urls[full_url(d, idx, fn)] = sz
        for to, frm, idx, sz in d["diffs"]:
            urls[diff_url(d, idx, frm, to)] = sz
        for ver, idx, sz in d["apks"]:
            urls[apk_url(d, idx, ver)] = sz
    return urls


def load_devices():
    return [parse(os.path.join(HERE, fn)) for _, _, fn in DEVICES]


# --------------------------------------------------------------------------- #
# history
# --------------------------------------------------------------------------- #

def load_history(path):
    try:
        with open(path) as fh:
            h = json.load(fh)
    except (FileNotFoundError, json.JSONDecodeError):
        h = {}
    h.setdefault("samples", [])
    return h


def save_history(path, h):
    with open(path, "w") as fh:
        json.dump(h, fh, separators=(",", ":"))


def trim(h, now):
    cutoff = now - WINDOW_HOURS * 3600 - 1800   # 30 min grace for scheduler jitter
    latest_by_day = {}
    for sample in h["samples"]:
        timestamp = sample.get("t", 0)
        if timestamp < cutoff:
            continue
        day = timestamp // 86400
        previous = latest_by_day.get(day)
        if previous is None or timestamp >= previous.get("t", 0):
            latest_by_day[day] = sample
    h["samples"] = [latest_by_day[day] for day in sorted(latest_by_day)][-MAX_POINTS:]


def sparkline(url, h, now=None):
    """Seven UTC-day squares, oldest→newest, with grey for every missing day."""
    if now is None:
        now = int(time.time())
    current_day = now // 86400
    latest_by_day = {}
    for sample in h["samples"]:
        timestamp = sample.get("t", 0)
        day = timestamp // 86400
        previous = latest_by_day.get(day)
        if previous is None or timestamp >= previous.get("t", 0):
            latest_by_day[day] = sample

    cells = []
    for day in range(current_day - MAX_POINTS + 1, current_day + 1):
        sample = latest_by_day.get(day, {})
        value = sample.get("r", {}).get(url)
        cells.append(UP if value == 1 else DOWN if value == 0 else NODATA)
    return "".join(cells)


# --------------------------------------------------------------------------- #
# probe
# --------------------------------------------------------------------------- #

def response_total_size(response):
    """Read the complete object size from a range or full response."""
    if response.status == 206:
        content_range = response.headers.get("Content-Range", "")
        try:
            return int(content_range.rsplit("/", 1)[1])
        except (IndexError, ValueError):
            return None
    if response.status == 200:
        try:
            return int(response.headers.get("Content-Length", ""))
        except ValueError:
            return None
    return None


def probe_one(url, expected_size):
    req = urllib.request.Request(url, method="GET")
    req.add_header("Range", "bytes=0-0")
    req.add_header("User-Agent", "ha-paneld-firmware-monitor")
    try:
        t0 = time.time()
        with urllib.request.urlopen(req, timeout=PROBE_TIMEOUT) as r:
            ms = int((time.time() - t0) * 1000)
            return response_total_size(r) == expected_size, ms
    except urllib.error.HTTPError:
        return False, None      # 403 (missing) → down
    except Exception:
        return False, None


def cmd_probe(args):
    devices = load_devices()
    url_sizes = all_url_sizes(devices)
    entries = list(url_sizes.items())
    with ThreadPoolExecutor(max_workers=PROBE_WORKERS) as ex:
        outcomes = list(ex.map(lambda item: probe_one(*item), entries))
    results = {u: (1 if up else 0) for (u, _size), (up, _ms) in zip(entries, outcomes)}

    h = load_history(args.history)
    now = int(time.time())
    h["samples"].append({"t": now, "r": results})
    trim(h, now)
    save_history(args.history, h)

    up = sum(results.values())
    down = len(entries) - up
    print(f"probed {len(entries)} URLs at {time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(now))}: "
          f"{up} up, {down} down ({len(h['samples'])} samples retained)")
    return 1 if down > 0 else 0


# --------------------------------------------------------------------------- #
# render
# --------------------------------------------------------------------------- #

INTRO = """# NSPanel Pro firmware — OTA download index (community-maintained)

Direct, clickable, **live-verified** download links for Sonoff NSPanel Pro OTA firmware — both the **86P** (480×480, PX30) and the **120P** (750×1334, rk3326-S). Every link was confirmed live by a range response whose total object size exactly matches the index. The S3 bucket can't be listed, so these are exact-filename hits, not a directory listing.

Flashing how-to (fully remote, no recovery-mode ADB): see [the repo's firmware guide](https://github.com/maxlyth/ha-paneld/blob/main/docs/hardware/nspanel-pro-firmware.md). `/data` (apps + settings) is preserved across an OTA.

> [!TIP]
> **How to read this index:** the per-model tables below are generated from the index data and are the authority on what exists — this page deliberately does not name a “latest” version in prose, because prose goes stale the moment a release lands. `4.0.12` is the full-ROM checkpoint, and it is itself a full ROM. A release indexed after it arrives either as one or more diffs — each patching from a specific earlier version, and a release commonly has several inbound diffs to choose from — or as an APK-only update carrying no ROM at all, and a release can be a ROM on one model and APK-only on the other — so an upgrade is not always a single hop, and the per-model tables show which form each release takes. The project has hardware-verified the flashing procedure only through **4.4.0**; anything newer is CDN-verified only, so try it on one recoverable panel first.

> [!NOTE]
> **Help wanted:** this index lists what has been *found*, not everything that exists. The bucket cannot be listed and the per-build index cannot be derived from a version number, so a failed probe rules out one filename at one index and never a build — absence here is not proof of non-existence. Spot a build or a link that is missing? Reply with the URL plus a range probe (`curl -s -r 0-0 -D - -o /dev/null "<url>"`) showing `206` and the total size, and it gets added. This thread is the living list.
"""

CHANGES = """## Which version should an HA panel run? (newer isn't always better)

Vendor release notes are written for eWeLink / Zigbee-hub users, not for people running the panel purely as a Home Assistant dashboard. Below is the same history re-read through an HA-panel lens, combining the official notes where they exist, community reports from the release and user feedback threads (**[c]**), and our own fleet testing (**[f]**, ha-paneld, June 2026). Several releases trade dashboard convenience for hub features an HA-only panel doesn't need.

> [!TIP]
> **Picking a build for an HA-only panel:**
> - **Lean kiosk:** a late **3.x** build is lighter, keeps the **Web Shortcut** web-app (point straight at a dashboard URL, no sideload), and ships **no Termux**.
> - **Modern app model:** if you want F-Droid / the HA Companion app or 4.x MQTT features, stop at **4.0.12** — the conservative full-ROM checkpoint — then turn on **Settings → About → Block Firmware Updates** to pin it.
> - **Latest / bleeding edge:** take the newest rows of the table below and of the per-model download tables. Nothing past **4.4.0** is live-flash verified by this project, and recent releases carry unverified community reports — restart loops on 4.5.1 / 4.5.2, sub-device connectivity trouble on 4.7.0. Try any of them on one recoverable panel first; **4.0.12** remains the conservative full-ROM checkpoint to pin for maximum stability.
> - **Always:** sideload a current **System WebView** — the stock one is too old to render the HA dashboard (see the repo's [docs/hardware](https://github.com/maxlyth/ha-paneld/tree/main/docs/hardware) notes).

| Version | What it changes for HA-panel use | Watch out for |
| --- | --- | --- |
| **≤ 3.x** | Web Shortcut web-app (dashboard URL, no sideload); lighter UI; no Termux. | Old stock WebView — sideload a current one **[f]**. |
| **4.0.0** | Faster UI; adds F-Droid + direct app install (HA Companion). | **"Web Shortcut" web-app phased out** — you install an app now instead of pointing at a URL. (4.x also begins bundling Termux — see gotchas below.) |
| [**4.0.7**](https://forum.ewelink.cc/t/nspanel-pro-v4-0-7-optimized-version-released/205842) | Roller-shutter support; Zigbee 3rd-party control + T&H display fixes. | **[c]** Random reboots / power-on failures; screensaver off-centre and **disabled while the HA Companion app runs**. |
| [**4.1.0**](https://forum.ewelink.cc/t/nspanel-pro-v4-1-0-release-new-features-enhancements/206443) | Export Zigbee devices to HA over MQTT; Matter Bridge; Matter SDK 1.4. | **[c]** MQTT published **without `retain`** → HA entities go blank after a broker/HA restart until the next state change. Needs eWeLink app ≥ 5.21.0. |
| [**4.2.0**](https://forum.ewelink.cc/t/nspanel-pro-v4-2-0-officially-released-new-features-enhancements/206900) | App Switcher + set a **"Home App" (kiosk launcher)**; control accessibility-button visibility; auto-start on boot; more Zigbee devices. | **[c]** Freezing, shell errors, extra restarts, slower Zigbee rebuild; MQTT still publishes **unfiltered Zigbee DP values** → entity/log spam. |
| [**4.3.0**](https://forum.ewelink.cc/t/nspanel-pro-v4-3-0-officially-released-new-features-enhancements/207281) | **Panel exposes its own capabilities as HA entities over MQTT** (speaker, screen on/off, brightness, ambient light, security status, IP); 0.1 °C heating; custom ringtones. | **[c]** Tap-to-toggle vs tap-for-details is fiddly; heating over-cycles on small thresholds; schedules reset on config change. |
| [**4.4.0**](https://forum.ewelink.cc/t/nspanel-pro-v4-4-0-officially-released-new-features-enhancements/207640) | Native **wake-on-proximity**, **mic recording** and **speaker playback** over MQTT (play a file or URL from HA); Zigbee NCP 8.x. | **[c]** Widespread **persistent ticking/tapping sound** from the proximity feature (disable Touch Sounds or the feature); heating not shutting off at setpoint. |
| [**4.5.1 / 4.5.2**](https://forum.ewelink.cc/t/nspanel-pro-firmware-4-5-2-has-been-released/208527) | Zigbee water-valve + PIR motion support; general fixes. 4.5.2 is an APK-only update over the 4.5.1 ROM. | **[c]** **Frequent restarts (~10–60 min) on both 120P and 86P**; app crashes viewing logs; Matter Bridge missing on some units. |
| [**4.5.3**](https://forum.ewelink.cc/t/rolling-with-new-releases-nspanel-pro-firmware-updates/207466) | Matter auto-discovery and screen-management optimizations; ROM diffs on 120P but APK-only on 86P. | No 4.5.3-specific restart-loop evidence found; followed by 4.6.0. |
| [**4.6.0**](https://forum.ewelink.cc/t/rolling-with-new-releases-nspanel-pro-firmware-updates/207466) | **Local Web Portal** — the panel is now reachable on the LAN at `http://nspanelpro.local` (or its IP) for setup + management: add Zigbee / eWeLink sub-devices, arm/disarm Smart Security, configure the **MQTT broker to sync Zigbee into Home Assistant**, authorize HA + Matter Bridge pairing, upload custom ringtones/screensavers. | Released **2026-06-30** — not yet live-flash verified here. Distributed as diffs off 4.0.12 / 4.4.0 / 4.5.1, with no new full ROM. |
| **4.6.2** | No release notes found; indexed as an APK-only update with no ROM diff on either channel. | Located by probing the CDN — treat the absence of notes as unknown-content, not as a minor release. |
| **4.7.0** | Covers Gen1 and the Gen2 panels; users report added Basic gen-5 relay (BASIC-1GS) support. **No official release notes were found** — this is drawn from the [eWeLink user feedback thread](https://forum.ewelink.cc/t/nspanel-pro-v4-7-0-feeback/208789), which is a discussion thread rather than a release announcement or a vendor changelog. | Released ~**2026-07-16**; not live-flash verified here. **[c]** The thread carries reports of sub-device connectivity trouble after updating, some resolved by a reboot and others described as continuing. This project has not reproduced or quantified them; treat them as unverified user reports rather than a known regression. |

**Cross-cutting gotchas (firmware-independent) [f]:**

- **WebView is the #1 blocker.** The stock System WebView is too old to render the HA dashboard (blank/broken). Sideload a current `com.android.webview` (no root needed) — verified on PX30 (138), TPA10/Cromite (147), rk3576 (150).
- **Proximity granularity is per-model, not per-version.** The **86P reports graded (continuous)** proximity; the **120P reports only binary near/far** — it tracks the on-device version, not the 4.x marketing number. Affects how finely you can tune wake-on-approach / presence.
- **4.x bundles Termux** (`/system/app`, plus Termux:Boot) — the runtime some community Zigbee2MQTT-on-panel bridges use. An HA panel where the HA server already owns Zigbee doesn't need it; it's disableable without root.
- **The vendor's own MQTT exposure (4.1.0+) is unfiltered** — it can flood HA with raw Zigbee DP entities. If you just want the panel as a dashboard, leave the eWeLink↔HA MQTT bridge off and drive the panel from HA directly.

*Legend: unmarked = official release notes · **[c]** community-reported in the release or user feedback thread · **[f]** ha-paneld fleet testing, June 2026.*
"""

RELEASE_NOTES = """## Release notes

Notes are published per "NSPanel Pro" — not split by 86P vs 120P.

| Version(s) | Where the notes live |
| --- | --- |
| 1.x – 3.8.x | [SONOFF "NSPanel Pro Version Update Information and FAQ"](https://sonoff.tech/en-us/blogs/news/sonoff-nspanel-pro-version-update-information-and-faq) — dated changelog |
| 4.0.0 | [SONOFF V4.0.0 update blog](https://sonoff.tech/en-us/blogs/news/nspanel-pro-v4-0-0-update-now-supports-f-droid-and-home-assistant-app-install) (F-Droid + HA app install) |
| 4.0.7 | [eWeLink — V4.0.7 optimized version](https://forum.ewelink.cc/t/nspanel-pro-v4-0-7-optimized-version-released/205842) |
| 4.1.0 / 4.2.0 / 4.3.0 | eWeLink per-version threads ([4.1.0](https://forum.ewelink.cc/t/nspanel-pro-v4-1-0-release-new-features-enhancements/206443) · [4.2.0](https://forum.ewelink.cc/t/nspanel-pro-v4-2-0-officially-released-new-features-enhancements/206900) · [4.3.0](https://forum.ewelink.cc/t/nspanel-pro-v4-3-0-officially-released-new-features-enhancements/207281)) |
| 4.4.0 | [eWeLink — V4.4.0 officially released](https://forum.ewelink.cc/t/nspanel-pro-v4-4-0-officially-released-new-features-enhancements/207640) (native wake-on-proximity, mic record + speaker playback over MQTT) |
| 4.5.1–4.5.3 | [eWeLink — firmware 4.5.2 released](https://forum.ewelink.cc/t/nspanel-pro-firmware-4-5-2-has-been-released/208527) · [eWeLink rolling release notes](https://forum.ewelink.cc/t/rolling-with-new-releases-nspanel-pro-firmware-updates/207466) |
| 4.6.0 | [SONOFF "NSPanel Pro Version Update Information and FAQ"](https://sonoff.tech/en-us/blogs/news/sonoff-nspanel-pro-version-update-information-and-faq) (Local Web Portal) |
| 4.6.2 | **No release notes found** — this project found neither a vendor changelog entry nor a community thread for it. It was located by probing the CDN, so this index records the files only. |
| 4.7.0 | **No vendor changelog entry found.** Community discussion only: [eWeLink 4.7.0 user feedback thread](https://forum.ewelink.cc/t/nspanel-pro-v4-7-0-feeback/208789) — a discussion thread of user reports, not a release announcement and not official release notes. |
| 3.9.4 / 4.0.10 / 4.0.12 | No official notes published (bug threads only) |
| All 4.x (rolling) | [eWeLink "[Rolling] NSPanel Pro Firmware Updates"](https://forum.ewelink.cc/t/rolling-with-new-releases-nspanel-pro-firmware-updates/207466) |
"""

SCHEME = """## How the URLs are built (to verify these or find new ones)

Host: `global-otadl2bsy.coolkit.cc`. Three path forms, per channel:

- Full ROM: `…/<channel>/rom/<idx>/<file>`
- Incremental diff: `…/<channel>/rom-diff/<idx>/CK_<from>_<to>[suffix]-diff.zip`
- eWeLink app APK: `…/<channel>/apk/<idx>/<file>`

Channels and conventions differ by model:

| | 86P | 120P |
| --- | --- | --- |
| Channel | `nspanel-pro` | `nspanel-pro-ver120` |
| Diff suffix | *(none)* | `V228` |
| APK filename | `app<ver>.apk` | `228V<ver>.apk` |

`<idx>` is a per-build serial (the `rom-diff` index is per **target** version). Probing: a missing file returns `403`; a real file answers a range request with `206` + a `Content-Range` total. Check one with `curl -s -r 0-0 -D - -o /dev/null "<url>"`.

> [!CAUTION]
> The per-model tables above are the authority on which full ROMs, diffs and APKs are indexed. Past the `4.0.12` full-ROM checkpoint a release arrives either as one or more diffs — each patching from a specific earlier version, so there is usually more than one way in — or as an APK-only update carrying no ROM diff at all, and a release can be a ROM on one model and APK-only on the other. Read the route off the tables rather than assuming a direct hop.
"""

FOOTER = """---

*This page is generated and refreshed automatically — do not hand-edit the body. The link list lives in [`tools/firmware-index/`](https://github.com/maxlyth/ha-paneld/tree/main/tools/firmware-index) and the 7-day availability squares are updated daily by [a GitHub Action](https://github.com/maxlyth/ha-paneld/blob/main/.github/workflows/firmware-url-monitor.yml). To add or correct a link, edit the data files (or reply below) — don't edit this post.*
"""


def availability_banner(h):
    samples = h.get("samples", [])
    if not samples:
        return ("> [!NOTE]\n> **Live availability:** the daily checker hasn't run yet — the **7-day** "
                "column fills in over the next week.\n")
    last = samples[-1]
    total = len(last.get("r", {}))
    up = sum(1 for v in last["r"].values() if v == 1)
    ts = time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime(last["t"]))
    return (f"> [!NOTE]\n> **Live availability** — each download row carries a 7-day sparkline, "
            f"newest on the right, checked daily: {UP} reachable · {DOWN} unreachable · {NODATA} no data yet. "
            f"Last check **{ts}**: **{up}/{total}** URLs reachable.\n")


def fulls_table(d, h, now):
    out = ["| Version | Size | Download | 7d |", "| --- | --- | --- | --- |"]
    for ver, idx, fn, sz in sorted(d["fulls"], key=lambda x: vkey(x[0]), reverse=True):
        url = full_url(d, idx, fn)
        out.append(f"| **{ver}** | {human(sz)} | [{fn}]({url}) | {sparkline(url, h, now)} |")
    return "\n".join(out)


def diffs_table(d, h, now):
    out = ["| To (target) | From | Size | Download | 7d |", "| --- | --- | --- | --- | --- |"]
    for to, frm, idx, sz in sorted(d["diffs"], key=lambda x: (vkey(x[0]), vkey(x[1])), reverse=True):
        url = diff_url(d, idx, frm, to)
        fn = f"CK_{frm}_{to}{d['suffix']}-diff.zip"
        out.append(f"| **{to}** | {frm} | {human(sz)} | [{fn}]({url}) | {sparkline(url, h, now)} |")
    return "\n".join(out)


def apks_table(d, h, now):
    out = ["| Version | Size | Download | 7d |", "| --- | --- | --- | --- |"]
    for ver, idx, sz in sorted(d["apks"], key=lambda x: vkey(x[0]), reverse=True):
        url = apk_url(d, idx, ver)
        fn = f"{d['apkfmt']}{ver}.apk"
        out.append(f"| {ver} | {human(sz)} | [{fn}]({url}) | {sparkline(url, h, now)} |")
    return "\n".join(out)


def device_block(name, sub, d, h, now):
    return f"""## {name} ({sub}) — channel `{d['channel']}`

### Full ROMs

{fulls_table(d, h, now)}

<details open>
<summary><b>Incremental diffs ({len(d['diffs'])})</b> — patch an existing version up to a target</summary>

{diffs_table(d, h, now)}

</details>

<details open>
<summary><b>eWeLink app APKs ({len(d['apks'])})</b></summary>

{apks_table(d, h, now)}

</details>
"""


def cmd_render(args):
    devices = load_devices()
    parsed = {fn: d for (_, _, fn), d in zip(DEVICES, devices)}
    h = load_history(args.history) if args.history else {"samples": []}
    render_time = int(time.time())

    blocks = [INTRO, availability_banner(h), CHANGES, RELEASE_NOTES]
    for name, sub, fn in DEVICES:
        blocks.append(device_block(name, sub, parsed[fn], h, render_time))
    blocks += [SCHEME, FOOTER]
    body = "\n".join(blocks) + "\n"

    if args.out:
        with open(args.out, "w") as fh:
            fh.write(body)
    else:
        sys.stdout.write(body)

    n = len(body.encode())
    msg = (f"rendered {n} bytes "
           f"({sum(len(d['fulls']) for d in devices)} ROMs, "
           f"{sum(len(d['diffs']) for d in devices)} diffs, "
           f"{sum(len(d['apks']) for d in devices)} APKs)")
    if n >= GITHUB_BODY_LIMIT:
        print(f"WARNING: {msg} — exceeds GitHub's {GITHUB_BODY_LIMIT}-byte body limit", file=sys.stderr)
        return 1
    print(msg, file=sys.stderr)
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("probe", help="check every URL and append up/down to the history file")
    p.add_argument("--history", required=True)
    p.set_defaults(func=cmd_probe)

    r = sub.add_parser("render", help="emit the Discussion markdown body")
    r.add_argument("--history", help="history JSON (omit for an empty sparkline)")
    r.add_argument("--out", help="output file (default: stdout)")
    r.set_defaults(func=cmd_render)

    args = ap.parse_args()
    sys.exit(args.func(args))


if __name__ == "__main__":
    main()
