<picture>
  <source media="(prefers-color-scheme: dark)" srcset="app/src/main/res/drawable-night-nodpi/wordmark.png">
  <img src="app/src/main/res/drawable-nodpi/wordmark.png" width="360" alt="ha-paneld">
</picture>

[![CI](https://github.com/maxlyth/ha-paneld/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/maxlyth/ha-paneld/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/maxlyth/ha-paneld?include_prereleases&sort=semver&style=flat-square&color=blue)](https://github.com/maxlyth/ha-paneld/releases)
[![License](https://img.shields.io/badge/license-Apache_2.0-blue?style=flat-square)](LICENSE)

**ha-paneld is free, open-source, and exists to fix what's wrong with Home Assistant wall panels** — the per-vendor fragmentation, the sluggish dashboards, and the clunky manufacturer software you're otherwise stuck with. It gives one consistent, Home-Assistant-first way to run a panel: full control of its hardware — screen, LEDs, buttons, sensors, relays and audio — across panels from *different* makers; a built-in admin launcher and on-screen navigation bar so a key-less panel behaves like an appliance; and the tooling to make a dashboard actually feel fast on cheap hardware. It's growing from a single-panel agent toward managing a whole fleet, with zero-touch remote provisioning.

It's a small Android agent that exposes panel-side hardware to Home Assistant over HTTP + MQTT auto-discovery + mDNS, so a panel pairs itself with HA when you sideload the APK — no per-device YAML.

It is built for panel-class Android — with explicit device profiles for Sonoff NSPanel Pro, Tuya TPA10, Electron WF1589T, ZHICAI SMT1019, Smatek S9E, the ZX-SMT156/RK3566_T, the LineageOS-based Echo Show 5 Gen 2, and (preliminary) the Shelly Wall Display family — **not** personal phones. Dashboard rendering is your choice: the official [HA Companion app](https://github.com/home-assistant/android) (today's default), or — new in 0.9 — ha-paneld's own **built-in renderer**.

<picture>
  <source media="(prefers-color-scheme: light)" srcset="docs/img/config-ui-light.png">
  <img src="docs/img/config-ui-dark.png" alt="ha-paneld's on-panel configuration page — responsive cards for panel info, capabilities, live performance and configuration">
</picture>

**Performance is a first-class concern.** Cheap panel hardware can make a dashboard that flies on a phone crawl on the wall — usually with no visibility into *why*. So ha-paneld measures and tunes: on-device CPU / GPU / clock and thermal throttling, a dashboard responsiveness metric, the top CPU consumers, and a 1-click WebView DevTools relay. See [docs/performance.md](docs/performance.md) and the [performance comparison](docs/hardware/README.md#performance-comparison--practical-deployment).

## Install

First enable network ADB on the panel (Developer options → "ADB debugging"). Then, from any machine with `adb` on the same LAN, paste:

```sh
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash
```

No checkout, no parameters: it checks your tools (with fix-it hints if `adb`/`curl` are missing), prompts for the panel IP (and optional id / MQTT broker), downloads the **latest signed release**, and provisions the panel. To install the latest **pre-release** (release candidate) instead, append `--prerelease`:

```sh
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --prerelease
```

For scripted/fleet installs use [`scripts/provision.sh`](scripts/provision.sh) directly — see [Provisioning & fleet updates](docs/provisioning.md).

> [!IMPORTANT]
> **On Windows, run the one-liner in Git Bash or WSL — not PowerShell.** It's a `bash` script, so PowerShell fails at the `bash` step. Git Bash ships with [Git for Windows](https://gitforwindows.org/); install `adb` first with `winget install Google.PlatformTools`, then reopen the shell and paste the command. macOS and Linux run it as-is.

> [!IMPORTANT]
> **First-run gotcha — update the panel's system WebView.** Even modern panels ship with a WebView/Chromium far too old to render a current Home Assistant dashboard, so the HA Companion app might show a blank or broken UI. Fix it cleanly over adb: see [Updating the system WebView](docs/hardware/README.md#updating-the-system-webview). This trips up almost everyone; do it before judging anything else.

> [!NOTE]
> ha-paneld is the headless agent — your dashboard launcher is the [HA Companion app](https://github.com/home-assistant/android). On panels without Google Play, install its [**minimal** release APK](https://github.com/home-assistant/android/releases/latest/download/app-minimal-release.apk).

> [!NOTE]
> ha-paneld can only be sideloaded on a panel — it isn't on the Play Store, so even Play-capable panels (eg 2026 Android 14 models) should sideload it.

### Other ways to install

- **F-Droid (on-device, no PC).** Add ha-paneld's F-Droid repository and install + auto-update straight from the panel — see [Installing via F-Droid](docs/fdroid.md). Easiest where the panel can run F-Droid (Sonoff NSPanel Pro firmware ≥ 4.0.0 bundles it). Delivers the app only — the root-gated features still need [provisioning](docs/provisioning.md).
- **USB bootstrap, no-adb sideload, and scripted / whole-fleet installs** — if your panel doesn't expose adb over the network, or you want to roll many panels at once, see [Provisioning & fleet updates](docs/provisioning.md).

## Why not just the Home Assistant Companion app?

The [HA Companion app](https://github.com/home-assistant/android) targets personal phones and tablets. Wall panels need different primitives: screen / LED / button control, hardware-button events back to HA, a built-in launcher and on-screen navigation bar for key-less hardware, fleet provisioning, and turnkey mDNS pairing. ha-paneld covers those regardless of what draws the dashboard.

For the dashboard itself there are **two supported paths**. The Companion app is today's default and the right choice when using **Home Assistant's Voice Assistant (Assist)** or native notifications — ha-paneld doesn't replace either. Since 0.9 there is also ha-paneld's own **built-in renderer** (experimental): it turns the panel into a single-app appliance — one APK to install, sign-in provisioned from your admin machine (no typing on the panel, no OAuth flow on a stale WebView), and page-level resilience engineered for a dashboard that runs untouched for weeks — screen-off freezing, connection watchdogs, bounded memory, crash containment. It deliberately has **no Voice Assistant and no notifications**; if those matter on your panel, stay with the Companion. Both paths remain supported long-term.

## Why not Fully Kiosk?

[Fully Kiosk Browser](https://www.fully-kiosk.com/) is the usual answer for HA wall panels, and it's genuinely capable. But it is overly complicated, sits awkwardly against Home Assistant's own values, and on a small mixed fleet its wins are narrow for the friction it adds. ha-paneld is free, open, and Home-Assistant-first: it supports the official Companion app by default, or its own built-in renderer. It fills the *panel-hardware* gap without a per-device licence.

<details>
<summary>The three friction points in full</summary>

- **Not free, not open source.** Fully Kiosk is closed-source commercial software. The free tier is limited and nags; the parts people actually want for a panel — the remote-admin REST/MQTT API, motion/screensaver controls, no watermark — need the paid **Plus** licence, **per device**. That cuts against HA's and ha-paneld's free, open, local-first ethos.
- **The Companion app already serves dashboards better.** For day-to-day dashboard rendering, the Companion app is purpose-built for HA — native auth and sessions, push notifications, deep links, Home Assistant Voice Assistant (Assist), and it tracks the frontend. A general-purpose kiosk browser is a second rendering path to keep working.
- **Per-device config doesn't scale on a non-homogeneous fleet.** Fully Kiosk is configured per device (its settings UI / per-device cloud), so a mixed fleet of different panels drifts and each unit is a bespoke setup. ha-paneld is config-as-code: MQTT auto-discovery, uniform entities across every panel, and one `update-fleet.sh` to roll them together.

If Fully Kiosk's specific extras (e.g. its kiosk lockdown or its particular screensaver) are load-bearing for you, keep using it.
</details>

## Why not FreeKiosk?

[FreeKiosk](https://github.com/RushB-fr/freekiosk) is an unrelated project, despite the similar name. It re-implements Fully Kiosk's feature set on a completely different, fully open-source codebase. One technical difference worth knowing: FreeKiosk is built on React Native, and the second JavaScript runtime it requires can severely impact performance on typical low-RAM panels (1–2 GB).

## Capabilities

| Cap | Surface |
|-----|---------|
| Screen brightness | `light.<panel>_screen` brightness |
| Screen on/off (true backlight off, no lock/PIN) | `light.<panel>_screen` on/off |
| RGB LED | `light.<panel>_led` (per-panel HAL: rk3576 NDK `/dev/ledjni`, or sysfs via the root helper) |
| Hardware-button events | `event.<panel>_button` (a11y key capture) |
| Ambient light / proximity | `sensor.<panel>_illuminance`, `binary_sensor.<panel>_proximity` |
| Launcher / Home Assistant (bring a launcher or the HA dashboard forward) | `button.<panel>_launcher`, `button.<panel>_home` |
| URL navigate | `text.<panel>_navigate` |
| Reload dashboard / reboot | `button.<panel>_reload`, `button.<panel>_reboot` |
| TTS / announce audio | `POST /play` + `number.<panel>_volume` (HA has no MQTT media_player platform) — server-side TTS recipe in [docs/tts.md](docs/tts.md) |
| Panel info + config web page | `GET /` (the device "Visit" link) |

Every panel publishes the **same** MQTT-discovery entities regardless of underlying hardware, so HA picks them up with no YAML. The full entity reference, the HTTP contract on `:8888`, and how pairing works are in **[docs/api.md](docs/api.md)** (or browse it live at `http://<panel>:8888/api`).

## What needs root — and what doesn't

A few of ha-paneld's features reach hardware that is only accessible to a privileged process, AKA **root**. Whether a panel permits root access is a property of the **panel's firmware**, not of ha-paneld. For most wall panels this is fine: purpose-built panels (Sonoff NSPanel Pro) ship with on-device root, and many others (Tuya TPA10, and the Shelly Wall Display family) expose `adb root` at setup, which lets ha-paneld's small helper run privileged from then on. Either way you get the full feature set. The reduced set is for the genuine hold-outs: locked-down consumer tablets and appliance firmware (corporate and EPOS devices) that grant neither. This limits some capability, and the root-gated features are **shown greyed with a 🔒 lock note** in the web UI. If you are not sure which camp your panel is in, the installer tells you at install time.

**Works on every panel (no root):** Home Assistant pairing + all MQTT sensors, screen brightness and dim, audio announcements/TTS, both dashboard renderers (HA Companion and the built-in renderer), the full web UI and REST API, Back/Recents navigation, wake-on-wave, the soft navigation bar, and config backup/restore.

**Needs a rooted panel:** true screen-off (backlight hard-off), RGB LED and relays, vendor-app taming, display sizing (density/text scale), self-update and Companion install/update from the panel, remote screenshot + tap control, full system logs, kiosk lock, CPU governor, and borrowing the Companion's sign-in when switching renderers.

## Supported hardware

ha-paneld needs no system-signed install. Standard-Android capabilities (brightness, sleep, navigate, TTS) work on any panel; LED/buttons depend on a per-panel hardware abstraction layer (HAL) or direct support in ha-paneld via a profile.

| Panel class | SoC | Android | ABI | Notes |
|-------------|-----|---------|-----|-------|
| Sonoff NSPanelPro / Pro120 | Rockchip PX30 | 8.1 (API 27) | arm64-v8a | toolbox `su` |
| Tuya TPA10 | Rockchip rk3566 | 11 (API 30) | armeabi-v7a | 32-bit userspace |
| Electron WF1589T | Rockchip rk3576 | userdebug (`adb root`) | arm64-v8a | RGB LED via clean-room NDK ioctl on `/dev/ledjni` (no vendor lib) |
| ZHICAI SMT1019 | Rockchip rk3576 | 14 (API 34) | arm64-v8a | no root; RGB LED firmware-locked (ioctl denied) — community-reported ([#8](https://github.com/maxlyth/ha-paneld/issues/8)) |
| ZX-SMT156 / RK3566_T | Rockchip rk3566 | 13 (API 33) | arm64-v8a | **preliminary** — app-direct RGB LED, light/proximity; vendor climate/relay paths still being characterised ([#24](https://github.com/maxlyth/ha-paneld/issues/24)) |
| Amazon Echo Show 5 Gen 2 (`cronos`) | MediaTek MT8163 | LineageOS 11 (API 30) | armeabi-v7a | **preliminary** — targets the community LineageOS/userdebug installation, not stock Fire OS ([#28](https://github.com/maxlyth/ha-paneld/issues/28)) |
| Smatek S9E | Rockchip rk3566 | — | arm64-v8a | onboard relays + button LEDs; proximity via root GPIO (not SensorManager) |
| Shelly Wall Display — Stargate / Atlantis / Pegasus | MediaTek MT6580 | — | armeabi-v7a | **preliminary** — hardware verification in progress; no root; relay via HA Shelly integration (not sysfs); deploy via ADB |
| Shelly Wall Display — Blake XL / Jenna / Cally / Maverick / Dayna | Arm64 (SoC TBC) | — | arm64-v8a | **preliminary** — hardware verification in progress; no root; deploy via ADB or Shelly AppStore (≥ v2.6.0) |

## Status & roadmap

**Latest release candidate — 0.9.2-rc2:** screen state now remains accurate on helper-controlled panels, release downloads lead with the supported installer, and built-in-renderer testers can opt into an experimental exact entity allow-list. Reconfiguration, reconnect and shutdown handling has also been hardened across MQTT, dashboard, media, sensors, input, logging, updates and hardware controls so superseded work cannot affect the active runtime. Full notes for every release are in [CHANGELOG.md](CHANGELOG.md).

**Where it's heading** — the near-term direction is **fleet-scale operation** and **full remote provisioning**: bringing a new or factory-wiped panel all the way up with zero typing on the panel, and pushing config/updates to a whole fleet from one place. Other planned work includes MQTT TLS for self-signed brokers, an on-device scheduler, deeper performance tooling, and continued iteration on the HTTP UI. The full curated list is in **[docs/roadmap.md](docs/roadmap.md)**.

## Documentation

- **[docs/api.md](docs/api.md)** — the control API: uniform MQTT entities, the HTTP contract (`:8888`), and pairing. Browse and try every endpoint live at `http://<panel>:8888/api`; the machine-readable spec is at `/openapi.json`.
- **[docs/provisioning.md](docs/provisioning.md)** — headless provisioning, whole-fleet updates, adb bootstrap, and the permission grants.
- **[docs/built-in-renderer.md](docs/built-in-renderer.md)** — the built-in dashboard renderer (experimental): what it is, turning it on (incl. the one-click Companion sign-in borrow), theming, and what it deliberately omits.
- **[docs/building.md](docs/building.md)** — build from source (Docker or local toolchain) and the signing notes forkers need. See also [docs/local-builds.md](docs/local-builds.md) (devcontainer).
- **[docs/roadmap.md](docs/roadmap.md)** — the full planned + stretch roadmap (shipped work is in [CHANGELOG.md](CHANGELOG.md)).
- **[docs/hardware/](docs/hardware/)** — reverse-engineered hardware references for the supported panels (SoC, LED control path, sensors, radios), since these devices are otherwise undocumented: [NSPanel Pro](docs/hardware/nspanel-pro.md) (PX30), [TPA10](docs/hardware/tpa10.md) (rk3566), [WF1589T](docs/hardware/wf1589t.md) (rk3576), [SMT1019](docs/hardware/smt1019.md) (rk3576, community), [Smatek S9E](docs/hardware/s9e.md) (rk3566), [ZX-SMT156](docs/hardware/zx-smt156.md) (community), [Echo Show 5 Gen 2](docs/hardware/echo-show-5-gen2.md) (LineageOS, community), and [Shelly Wall Display](docs/hardware/shelly-wall-display.md) (preliminary) — plus a [performance comparison](docs/hardware/README.md#performance-comparison--practical-deployment).
- **[docs/tts.md](docs/tts.md)** — server-side TTS recipe: render a phrase with any HA engine (Piper, Cloud) and send it to a panel via a small script (no add-on, no on-device TTS).
- **[docs/performance.md](docs/performance.md)** — panel performance tuning: why dashboards lag on weak panels and how to fix it (the WebSocket-event-volume problem; the split-instance approach).
- **[docs/display-sizing.md](docs/display-sizing.md)** *(experimental / R&D)* — matching dashboard size to a desktop browser via display density + system font scale (Android panels often ship these mismatched to the physical screen).
- **[helper/README.md](helper/README.md)** — the root LED/control helper daemon for sysfs-LED panels (build + boot-persistent install).
- **`GET /diag`** on a panel — a copy-paste hardware/firmware/capability dump for bug reports.

## Screenshots

| ha-paneld standing screen | REST API explorer |
|---|---|
| <img src="docs/img/standing-screen.png" alt="ha-paneld standing screen — icon, config URL and a QR code to open the config page" width="480"> | <picture><source media="(prefers-color-scheme: light)" srcset="docs/img/api-explorer-light.png"><img src="docs/img/api-explorer-dark.png" alt="REST API explorer" width="480"></picture> |

## Stack

- **HTTP** — Ktor CIO engine (coroutine I/O, no thread-per-connection).
- **MQTT** — HiveMQ MQTT 5 client (NIO transport; ABI-agnostic).
- **mDNS** — JmDNS (chosen over `NsdManager` for reliable TXT records across API levels).

## Want your panel supported?

ha-paneld has no donate button. It's free, and the "payment" that actually moves it forward is **more panels supported** — which takes hardware to study. Every panel here was added by hands-on adb analysis: probing the device and watching how it responds to real button, LED and sensor interaction.

So if you'd like to help:
- **Open an issue with your panel's diagnostics.** Visit `http://<panel-ip>:8888/diag` (or the diag link on the panel's config page) and paste the dump into a new issue — build, SELinux, `/dev` + `/sys` listings, capability probe. That's enough to start; from there we'll work out a short interactive workflow to map the buttons/LEDs/sensors that need a person at the panel.
- **Or send me the panel.** I'm **UK-based** and happy to do the reverse-engineering directly — the fastest route to a fully-supported new model. You'll get it back (I have way too many already); open an issue first so we can sort the details.

The result is always open: your panel becomes a profile everyone can use — a bit less per-vendor fragmentation for the next person. That's the donation.

## Acknowledgements

Thanks to **Seaky** for [**NSPanel Pro Tools**](https://github.com/seaky/nspanel_pro_tools_apk) ([releases](https://github.com/seaky/nspanel_pro_tools_apk/releases)), which showed what good panel-side Home Assistant tooling can do — genuinely excellent work. It targets the Sonoff NSPanel-Pro class and is distributed as a closed-source APK; ha-paneld exists to be an **open, multi-vendor** alternative that any Android panel can adopt and extend.

## Licence

Apache-2.0. See [LICENSE](LICENSE).
