#!/usr/bin/env python3
"""NSPanel Pro firmware OTA index — generator and URL availability monitor.

The firmware download links published in GitHub Discussion #7 are point-in-time
hits against the CoolKit OTA CDN (the bucket cannot be listed, so each URL is an
exact-filename probe). This script keeps that page honest in two ways:

  probe   range-GET every URL, record up/down into a rolling 24h history file
  render  emit the Discussion markdown body, with a 24h availability sparkline
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
    urls = []
    for d in devices:
        for ver, idx, fn, sz in d["fulls"]:
            urls.append(full_url(d, idx, fn))
        for to, frm, idx, sz in d["diffs"]:
            urls.append(diff_url(d, idx, frm, to))
        for ver, idx, sz in d["apks"]:
            urls.append(apk_url(d, idx, ver))
    seen, out = set(), []
    for u in urls:
        if u not in seen:
            seen.add(u)
            out.append(u)
    return out


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
    h["samples"] = [s for s in h["samples"] if s.get("t", 0) >= cutoff][-MAX_POINTS:]


def sparkline(url, h):
    """12 squares, oldest→newest, left-padded with grey when data is missing."""
    cells = []
    for s in h["samples"][-MAX_POINTS:]:
        v = s.get("r", {}).get(url)
        cells.append(UP if v == 1 else DOWN if v == 0 else NODATA)
    cells = [NODATA] * (MAX_POINTS - len(cells)) + cells
    return "".join(cells)


# --------------------------------------------------------------------------- #
# probe
# --------------------------------------------------------------------------- #

def probe_one(url):
    req = urllib.request.Request(url, method="GET")
    req.add_header("Range", "bytes=0-0")
    req.add_header("User-Agent", "ha-paneld-firmware-monitor")
    try:
        t0 = time.time()
        with urllib.request.urlopen(req, timeout=PROBE_TIMEOUT) as r:
            ms = int((time.time() - t0) * 1000)
            return r.status in (200, 206), ms
    except urllib.error.HTTPError as e:
        return e.code in (200, 206), None      # 403 (missing) → down
    except Exception:
        return False, None


def cmd_probe(args):
    devices = load_devices()
    urls = all_urls(devices)
    with ThreadPoolExecutor(max_workers=PROBE_WORKERS) as ex:
        outcomes = list(ex.map(probe_one, urls))
    results = {u: (1 if up else 0) for u, (up, _ms) in zip(urls, outcomes)}

    h = load_history(args.history)
    now = int(time.time())
    h["samples"].append({"t": now, "r": results})
    trim(h, now)
    save_history(args.history, h)

    up = sum(results.values())
    down = len(urls) - up
    print(f"probed {len(urls)} URLs at {time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(now))}: "
          f"{up} up, {down} down ({len(h['samples'])} samples retained)")
    return 1 if down > 0 else 0


# --------------------------------------------------------------------------- #
# render
# --------------------------------------------------------------------------- #

INTRO = """# NSPanel Pro firmware — OTA download index (community-maintained)

Direct, clickable, **live-verified** download links for Sonoff NSPanel Pro OTA firmware — both the **86P** (480×480, PX30) and the **120P** (750×1334, rk3326-S). Every link was confirmed live (HTTP `206` + exact `Content-Length`) by exhaustively enumerating the CoolKit OTA CDN. The S3 bucket can't be listed, so these are exact-filename hits, not a directory listing.

Flashing how-to (fully remote, no recovery-mode ADB): see [the repo's firmware guide](https://github.com/maxlyth/ha-paneld/blob/main/docs/hardware/nspanel-pro-firmware.md). `/data` (apps + settings) is preserved across an OTA.

> [!TIP]
> **To reach the latest verified ROM (4.5.1):** flash the **4.0.12 full ROM**, then apply the **4.0.12 → 4.5.1 diff** (or **4.4.0 → 4.5.1** if you're already on 4.4.0). `4.0.12` is the full-ROM checkpoint; everything past `3.x` is incremental. **4.5.2 is an APK-only app update** that installs over the 4.5.1 ROM — there is no 4.5.2 ROM, and no full ROM newer than 4.0.12.

> [!NOTE]
> **Frontier / help wanted:** the newest published **ROM** target is **4.5.1**; **4.5.0 / 4.5.2** ship **APK-only** on top of it. No full ROM newer than `4.0.12` is published — distribution is diff-based off that checkpoint. Spot a newer build or a missing link? Reply with the URL (plus a `curl -I` showing `206` + size) and it gets added. This thread is the living list.
"""

CHANGES = """## Which version should an HA panel run? (newer isn't always better)

Vendor release notes are written for eWeLink / Zigbee-hub users, not for people running the panel purely as a Home Assistant dashboard. Below is the same history re-read through an HA-panel lens, combining the official notes, community reports from the release threads (**[c]**), and our own fleet testing (**[f]**, ha-paneld, June 2026). Several releases trade dashboard convenience for hub features an HA-only panel doesn't need.

> [!TIP]
> **Picking a build for an HA-only panel:**
> - **Lean kiosk:** a late **3.x** build is lighter, keeps the **Web Shortcut** web-app (point straight at a dashboard URL, no sideload), and ships **no Termux**.
> - **Modern app model:** if you want F-Droid / the HA Companion app or 4.x MQTT features, stop at **4.0.12** — the stable full-ROM checkpoint — then turn on **Settings → About → Block Firmware Updates** to pin it.
> - **Avoid the bleeding edge:** the current ROM target is **4.5.1** (with **4.5.2** an app-only update on top), but community reports cite reboot loops every ~10–60 min on both 120P and 86P — pin a known-good build rather than chasing it.
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
| [**4.5.1 / 4.5.2**](https://forum.ewelink.cc/t/nspanel-pro-firmware-4-5-2-has-been-released/208527) | Zigbee water-valve + PIR motion support; general fixes. 4.5.1 is the ROM; 4.5.2 is an APK-only update on top. | **[c]** **Frequent restarts (~10–60 min) on both 120P and 86P**; app crashes viewing logs; Matter Bridge missing on some units. |

**Cross-cutting gotchas (firmware-independent) [f]:**

- **WebView is the #1 blocker.** The stock System WebView is too old to render the HA dashboard (blank/broken). Sideload a current `com.android.webview` (no root needed) — verified on PX30 (138), TPA10/Cromite (147), rk3576 (150).
- **Proximity granularity is per-model, not per-version.** The **86P reports graded (continuous)** proximity; the **120P reports only binary near/far** — it tracks the on-device version, not the 4.x marketing number. Affects how finely you can tune wake-on-approach / presence.
- **4.x bundles Termux** (`/system/app`, plus Termux:Boot) — the runtime some community Zigbee2MQTT-on-panel bridges use. An HA panel where the HA server already owns Zigbee doesn't need it; it's disableable without root.
- **The vendor's own MQTT exposure (4.1.0+) is unfiltered** — it can flood HA with raw Zigbee DP entities. If you just want the panel as a dashboard, leave the eWeLink↔HA MQTT bridge off and drive the panel from HA directly.

*Legend: unmarked = official release notes · **[c]** community-reported in the release thread · **[f]** ha-paneld fleet testing, June 2026.*
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
| 4.5.1 / 4.5.2 | [eWeLink — firmware 4.5.2 released](https://forum.ewelink.cc/t/nspanel-pro-firmware-4-5-2-has-been-released/208527) |
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
> `4.0.12` is the **only full ROM** on either channel. The ROM path past it is diff-based: `4.0.12 → 4.4.0` and `4.0.12 → 4.5.1` (or `4.4.0 → 4.5.1`). **`4.5.0` and `4.5.2` are APK-only** — no ROM diff; they update the eWeLink app over the 4.5.1 ROM. Plan the path accordingly.
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


def fulls_table(d, h):
    out = ["| Version | Size | Download | 24h |", "| --- | --- | --- | --- |"]
    for ver, idx, fn, sz in sorted(d["fulls"], key=lambda x: vkey(x[0]), reverse=True):
        url = full_url(d, idx, fn)
        out.append(f"| **{ver}** | {human(sz)} | [{fn}]({url}) | {sparkline(url, h)} |")
    return "\n".join(out)


def diffs_table(d, h):
    out = ["| To (target) | From | Size | Download | 24h |", "| --- | --- | --- | --- | --- |"]
    for to, frm, idx, sz in sorted(d["diffs"], key=lambda x: (vkey(x[0]), vkey(x[1])), reverse=True):
        url = diff_url(d, idx, frm, to)
        fn = f"CK_{frm}_{to}{d['suffix']}-diff.zip"
        out.append(f"| **{to}** | {frm} | {human(sz)} | [{fn}]({url}) | {sparkline(url, h)} |")
    return "\n".join(out)


def apks_table(d, h):
    out = ["| Version | Size | Download | 24h |", "| --- | --- | --- | --- |"]
    for ver, idx, sz in sorted(d["apks"], key=lambda x: vkey(x[0]), reverse=True):
        url = apk_url(d, idx, ver)
        fn = f"{d['apkfmt']}{ver}.apk"
        out.append(f"| {ver} | {human(sz)} | [{fn}]({url}) | {sparkline(url, h)} |")
    return "\n".join(out)


def device_block(name, sub, d, h):
    return f"""## {name} ({sub}) — channel `{d['channel']}`

### Full ROMs

{fulls_table(d, h)}

<details open>
<summary><b>Incremental diffs ({len(d['diffs'])})</b> — patch an existing version up to a target</summary>

{diffs_table(d, h)}

</details>

<details open>
<summary><b>eWeLink app APKs ({len(d['apks'])})</b></summary>

{apks_table(d, h)}

</details>
"""


def cmd_render(args):
    devices = load_devices()
    parsed = {fn: d for (_, _, fn), d in zip(DEVICES, devices)}
    h = load_history(args.history) if args.history else {"samples": []}

    blocks = [INTRO, availability_banner(h), CHANGES, RELEASE_NOTES]
    for name, sub, fn in DEVICES:
        blocks.append(device_block(name, sub, parsed[fn], h))
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
