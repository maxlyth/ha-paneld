<picture>
  <source media="(prefers-color-scheme: dark)" srcset="app/src/main/res/drawable-night-nodpi/wordmark.png">
  <img src="app/src/main/res/drawable-nodpi/wordmark.png" width="360" alt="ha-paneld">
</picture>

[![CI](https://github.com/maxlyth/ha-paneld/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/maxlyth/ha-paneld/actions/workflows/ci.yml)
[![Release](https://img.shields.io/badge/release-v0.8.7-blue?style=flat-square)](https://github.com/maxlyth/ha-paneld/releases/latest)
[![License](https://img.shields.io/badge/license-Apache_2.0-blue?style=flat-square)](LICENSE)

**ha-paneld is free, open-source, and exists to fix what's wrong with Home Assistant wall panels** — the per-vendor fragmentation, the sluggish dashboards, and the clunky manufacturer software you're otherwise stuck with. It gives one consistent, Home-Assistant-first way to run a panel: full control of its hardware — screen, LEDs, buttons, sensors, relays and audio — across panels from *different* makers; a built-in admin launcher and on-screen navigation bar so a key-less panel behaves like an appliance; and the tooling to make a dashboard actually feel fast on cheap hardware. It's growing from a single-panel agent toward managing a whole fleet, with zero-touch remote provisioning.

It's a small Android agent that exposes panel-side hardware to Home Assistant over HTTP + MQTT
auto-discovery + mDNS, so a panel pairs itself with HA when you sideload the APK — no per-device YAML.

It is built for panel-class Android — with explicit device profiles for Sonoff NSPanel Pro, Tuya
TPA10, Electron WF1589T, ZHICAI SMT1019, Smatek S9E, and (preliminary) the Shelly Wall Display
family — **not** personal phones. The official [HA Companion app](https://github.com/home-assistant/android) remains the HOME
launcher and dashboard; ha-paneld runs as a headless foreground service alongside it and never takes
the foreground.

![ha-paneld's on-panel configuration page — responsive cards for panel info, capabilities, live performance and configuration](docs/img/config-ui.png)

**Performance is a first-class concern.** Cheap panel hardware can make a dashboard that flies on a phone crawl on the wall — usually with no visibility into *why*. So ha-paneld measures and tunes: on-device CPU / GPU / clock and thermal throttling, a dashboard responsiveness metric, the top CPU consumers, and a 1-click WebView DevTools relay. See [docs/performance.md](docs/performance.md) and the [performance comparison](docs/hardware/README.md#performance-comparison--practical-deployment).

> [!NOTE]
> **v0.x preview** — entity names and the API may still change before v1.0.0. It's an ordinary app
> install (no root-partition or firmware changes), so it uninstalls cleanly if you change your mind.

## Install

First enable network ADB on the panel (Developer options → "ADB debugging"). Then, from any machine
with `adb` on the same LAN, paste:

```sh
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash
```

No checkout, no parameters: it checks your tools (with fix-it hints if `adb`/`curl` are missing),
prompts for the panel IP (and optional id / MQTT broker), downloads the **latest signed release**, and
provisions the panel. For scripted/fleet installs use [`scripts/provision.sh`](scripts/provision.sh)
directly — see [Provisioning & fleet updates](docs/provisioning.md).

> [!IMPORTANT]
> **On Windows, run this in Git Bash or WSL — not PowerShell.** It's a `bash` one-liner (and PowerShell's `curl` is an alias for `Invoke-WebRequest`, not the real thing), so PowerShell fails at the `bash` step. Git Bash ships with [Git for Windows](https://gitforwindows.org/); install `adb` first with `winget install Google.PlatformTools`, then reopen the shell and paste the command. macOS and Linux run it as-is.

> [!NOTE]
> ha-paneld is the headless agent — your dashboard launcher is the
> [HA Companion app](https://github.com/home-assistant/android). On panels without Google Play, install
> its [**minimal** release APK](https://github.com/home-assistant/android/releases/latest/download/app-minimal-release.apk)
> (the no-Google-Play build).

> [!NOTE]
> ha-paneld can only be sideloaded on a panel — it isn't on the Play Store (its accessibility use for
> buttons/nav doesn't fit Play policy), so even Play-capable panels (eg 2026 Android 14 models) should sideload it.

> [!IMPORTANT]
> **First-run gotcha — update the panel's system WebView.** Even modern panels ship with a
> WebView/Chromium far too old to render a current Home Assistant dashboard, so the HA Companion app
> might show a blank or broken UI. Fix it cleanly over adb — **no F-Droid or third-party app store needed**:
> see [Updating the system WebView](docs/hardware/README.md#updating-the-system-webview). This trips up
> almost everyone; do it before judging anything else.

### Other ways to install

- **F-Droid (on-device, no PC).** Add ha-paneld's F-Droid repository and install + auto-update straight
  from the panel — see [Installing via F-Droid](docs/fdroid.md). Easiest where the panel can run F-Droid
  (Sonoff NSPanel Pro firmware ≥ 4.0.0 bundles it). Delivers the app only — the root-gated features still
  need [provisioning](docs/provisioning.md).
- **USB bootstrap, no-adb sideload, and scripted / whole-fleet installs** — for panels that don't expose
  adb over the network, or to roll many panels at once, see [Provisioning & fleet updates](docs/provisioning.md).

## Why not just the Home Assistant Companion app?

The [HA Companion app](https://github.com/home-assistant/android) targets personal phones and tablets. Wall panels need different primitives: screen / LED / button control, hardware-button events back to HA, a built-in launcher and on-screen navigation bar for key-less hardware, arbitrary-URL audio announcements, fleet provisioning, and turnkey mDNS pairing. ha-paneld covers those; Companion keeps doing what it does (and remains the dashboard host).

## Why not Fully Kiosk?

[Fully Kiosk Browser](https://www.fully-kiosk.com/) is the usual answer for HA wall panels, and it's genuinely capable. But it sits awkwardly against Home Assistant's own values, and on a small mixed fleet its wins are narrow for the friction it adds. ha-paneld is Apache-2.0, leaves dashboard hosting to the official open Companion app, and is config-as-code (MQTT auto-discovery, uniform entities across every panel, one `update-fleet.sh`). It doesn't try to replace a browser — it replaces the *panel-hardware* gap, openly, without a per-device licence.

<details>
<summary>The three friction points in full</summary>

- **Not free, not open source.** Fully Kiosk is closed-source commercial software. The free tier is
  limited and nags; the parts people actually want for a panel — the remote-admin REST/MQTT API,
  motion/screensaver controls, no watermark — need the paid **Plus** licence, **per device**. That
  cuts against HA's free, open, local-first ethos; ha-paneld is Apache-2.0 and the dashboard runs in
  the official, open Companion app.
- **The Companion app already serves dashboards better.** For day-to-day dashboard rendering, the
  Companion app is purpose-built for HA — native auth and sessions, push notifications, deep links,
  and it tracks the frontend. A general-purpose kiosk browser is a second rendering path to keep
  working; ha-paneld deliberately leaves dashboard hosting to HA Companion and only adds the panel
  hardware HA can't otherwise reach.
- **Per-device config doesn't scale on a non-homogeneous fleet.** Fully Kiosk is configured per
  device (its settings UI / per-device cloud), so a mixed fleet of different panels drifts and each
  unit is a bespoke setup. ha-paneld is config-as-code: MQTT auto-discovery, uniform entities across
  every panel, and one `update-fleet.sh` to roll them together.

If Fully Kiosk's specific extras (e.g. its kiosk lockdown or its particular screensaver) are
load-bearing for you, keep using it.

</details>

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

Every panel publishes the **same** MQTT-discovery entities regardless of underlying hardware, so HA
picks them up with no YAML. The full entity reference, the HTTP contract on `:8888`, and how pairing
works are in **[docs/api.md](docs/api.md)** (or browse it live at `http://<panel>:8888/api`).

## Supported hardware

ha-paneld needs no system-signed install. Standard-Android capabilities (brightness, sleep,
navigate, TTS) work on any panel; LED/buttons depend on a per-panel HAL.

| Panel class | SoC | Android | ABI | Notes |
|-------------|-----|---------|-----|-------|
| Sonoff NSPanelPro / Pro120 | Rockchip PX30 | 8.1 (API 27) | arm64-v8a | toolbox `su` |
| Tuya TPA10 | Rockchip rk3566 | 11 (API 30) | armeabi-v7a | 32-bit userspace |
| Electron WF1589T | Rockchip rk3576 | userdebug (`adb root`) | arm64-v8a | RGB LED via clean-room NDK ioctl on `/dev/ledjni` (no vendor lib) |
| ZHICAI SMT1019 | Rockchip rk3576 | 14 (API 34) | arm64-v8a | no root; RGB LED firmware-locked (ioctl denied) — community-reported ([#8](https://github.com/maxlyth/ha-paneld/issues/8)) |
| Smatek S9E | Rockchip rk3566 | — | arm64-v8a | onboard relays + button LEDs; proximity via root GPIO (not SensorManager) |
| Shelly Wall Display — Stargate / Atlantis / Pegasus | MediaTek MT6580 | — | armeabi-v7a | **preliminary** — hardware verification in progress; no root; relay via HA Shelly integration (not sysfs); deploy via ADB |
| Shelly Wall Display — Blake XL / Jenna / Cally / Maverick / Dayna | Arm64 (SoC TBC) | — | arm64-v8a | **preliminary** — hardware verification in progress; no root; deploy via ADB or Shelly AppStore (≥ v2.6.0) |

**minSdk is 26.** API < 26 is unsupported (the MQTT client cannot connect below API 26).

**How hardware access works.** The RGB LED is reached per-panel: on the rk3576 WF1589T ha-paneld
drives `/dev/ledjni` directly with its **own ~70-line clean-room NDK driver** ([`led_jni.c`](app/src/main/cpp/led_jni.c))
— the ioctl protocol was reverse-engineered from a hardware sample, so **no vendor library is bundled
or required**. Panels that expose the LED only through root-only `/sys/class/leds/*` (e.g. Tuya TPA10)
use a small **root helper daemon** instead — see [helper/README.md](helper/README.md). Other Android
panels are welcome — contribute a HAL adapter for your hardware. Deep per-panel hardware references
(SoC, sensors, LED path, radios) are in [docs/hardware/](docs/hardware/).

## Status & roadmap

Validated across the panel fleet: Sonoff NSPanel Pro (PX30, Android 8.1), Tuya TPA10 (rk3566,
Android 11), Electron WF1589T (rk3576, Android 14).

**Latest release — 0.8.7:** substantially more reliable install/provisioning scripts (su-dialect
probing, adb preflighting, honest verification and classified failure reporting), more reliable panel
telemetry — diagnostic sensors that populate on more hardware via the helper daemon — room
temperature/humidity on the Tuya TPA10, and a responsive `:8888` header for narrow panels. Full notes
for every release are in [CHANGELOG.md](CHANGELOG.md).

**Where it's heading** — the near-term direction is **fleet-scale operation** and **full remote
provisioning**: bringing a new or factory-wiped panel all the way up (dashboard renderer + Home
Assistant login) with zero typing on the panel, and pushing config/updates to a whole fleet from one
place. Other planned work includes MQTT TLS, an on-device scheduler, deeper performance tooling, and
continued iteration on the HTTP UI. The full curated list is in **[docs/roadmap.md](docs/roadmap.md)**.

## Documentation

- **[docs/api.md](docs/api.md)** — the control API: uniform MQTT entities, the HTTP contract (`:8888`),
  and pairing. Browse and try every endpoint live at `http://<panel>:8888/api`; the machine-readable
  spec is at `/openapi.json`.
- **[docs/provisioning.md](docs/provisioning.md)** — headless provisioning, whole-fleet updates, adb
  bootstrap, and the permission grants.
- **[docs/building.md](docs/building.md)** — build from source (Docker or local toolchain) and the
  signing notes forkers need. See also [docs/local-builds.md](docs/local-builds.md) (devcontainer).
- **[docs/roadmap.md](docs/roadmap.md)** — the full planned + stretch roadmap (shipped work is in
  [CHANGELOG.md](CHANGELOG.md)).
- **[docs/hardware/](docs/hardware/)** — reverse-engineered hardware references for the supported
  panels (SoC, LED control path, sensors, radios), since these devices are otherwise undocumented:
  [NSPanel Pro](docs/hardware/nspanel-pro.md) (PX30), [TPA10](docs/hardware/tpa10.md) (rk3566),
  [WF1589T](docs/hardware/wf1589t.md) (rk3576), [SMT1019](docs/hardware/smt1019.md) (rk3576, community),
  [Smatek S9E](docs/hardware/s9e.md) (rk3566), [Shelly Wall Display](docs/hardware/shelly-wall-display.md) (preliminary) — plus a [performance comparison](docs/hardware/README.md#performance-comparison--practical-deployment).
- **[docs/tts.md](docs/tts.md)** — server-side TTS recipe: render a phrase with any HA engine (Piper,
  Cloud) and send it to a panel via a small script (no add-on, no on-device TTS).
- **[docs/performance.md](docs/performance.md)** — panel performance tuning: why dashboards lag on
  weak panels and how to fix it (the WebSocket-event-volume problem; the split-instance approach).
- **[docs/display-sizing.md](docs/display-sizing.md)** *(experimental / R&D)* — matching dashboard size
  to a desktop browser via display density + system font scale (Android panels often ship these
  mismatched to the physical screen).
- **[helper/README.md](helper/README.md)** — the root LED/control helper daemon for sysfs-LED panels
  (build + boot-persistent install).
- **`GET /diag`** on a panel — a copy-paste hardware/firmware/capability dump for bug reports.

## Screenshots

| On-panel launcher screen | REST API explorer |
|---|---|
| ![ha-paneld launcher screen](docs/img/launcher.png) | ![REST API explorer](docs/img/api-explorer.png) |

## Stack

- **HTTP** — Ktor CIO engine (coroutine I/O, no thread-per-connection).
- **MQTT** — HiveMQ MQTT 5 client (NIO transport; ABI-agnostic).
- **mDNS** — JmDNS (chosen over `NsdManager` for reliable TXT records across API levels).

## Want your panel supported?

ha-paneld has no donate button. It's free, and the "payment" that actually moves it forward is **more
panels supported** — which takes hardware to study. Every panel here was added by hands-on adb analysis:
probing the device and watching how it responds to real button, LED and sensor interaction.

So if you'd like to help:

- **Open an issue with your panel's diagnostics.** Visit `http://<panel-ip>:8888/diag` (or the diag link
  on the panel's config page) and paste the dump into a new issue — build, SELinux, `/dev` + `/sys`
  listings, capability probe. That's enough to start; from there we'll work out a short interactive
  workflow to map the buttons/LEDs/sensors that need a person at the panel.
- **Or send me the panel.** I'm **UK-based** and happy to do the reverse-engineering directly — the
  fastest route to a fully-supported new model. You'll get it back (I have way too many already);
  open an issue first so we can sort the details.

The result is always open: your panel becomes a profile everyone can use — a bit less per-vendor
fragmentation for the next person. That's the donation.

## Acknowledgements

Thanks to **Seaky** for [**NSPanel Pro Tools**](https://github.com/seaky/nspanel_pro_tools_apk)
([releases](https://github.com/seaky/nspanel_pro_tools_apk/releases)), which showed what good
panel-side Home Assistant tooling can do — genuinely excellent work. It targets the Sonoff
NSPanel-Pro class and is distributed as a closed-source APK; ha-paneld exists to be an **open,
multi-vendor** alternative that any Android panel can adopt and extend. The two solve overlapping
problems for different audiences.

## Licence

Apache-2.0. See [LICENSE](LICENSE).
