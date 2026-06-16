# Changelog

Human-readable summaries of each release. The auto-generated commit list is appended below these
notes on each [GitHub release](https://github.com/maxlyth/ha-paneld/releases). Cutting a release? Follow
the pre-tag checklist in [docs/RELEASING.md](docs/RELEASING.md) (it starts with "check the README").

From **v0.8.0**, entries are grouped under **Added** (new features/entities), **Changed** (behaviour
changes to existing features), **Fixed** (bug fixes), and **Docs** (documentation) — only groups with
content appear. Earlier releases predate this convention and keep their flat lists.

## v0.8.3 - unreleased

### Changed

- **Lower-latency root actions** — root commands now run through a single long-lived `su` shell instead of forking `su` afresh per call (each fork+auth cost ~200–300 ms). Navbar **Back / Launcher / Recents** and other root-gated actions respond noticeably faster. Transparently falls back to a per-call `su` if the persistent shell is unavailable or a command stalls, so it's never worse than before.
- **Navbar auto-hide lingers longer** — the *Swipe reveal* bar now stays ~5 s (was 4 s) before sliding away.
- **Info-page card headers demarcated** — each card's title is now a subtly set-off header bar (slight tint + divider), separating it from the content. Groundwork for upcoming drag-to-reorder + collapsible cards.

### Added

- **Live screenshot in the info page + `/screenshot.png`** — the HTTP UI now shows a live panel screenshot (root `screencap`), scaled to a single column and **click-to-open full size** in a new tab. The `/screenshot.png` endpoint is also usable directly as a Home Assistant camera `still_image_url` / Picture-card image. LAN-only (like the rest of the surface), captured on demand — no background polling. Root required.
- **Smatek S9E proximity** — the S9E's `SensorManager` proximity registers but never delivers events, so `binary_sensor.<panel>_proximity` (and wake-on-wave) didn't work. ha-paneld now reads the raw proximity GPIO (gpio18: 1 = near, 0 = far) over root instead, on panels whose profile declares one. Reporter-confirmed (GitHub #5).

### Fixed

- **Navbar volume ± reliable on all panels** — on a panel whose audio stream reports a small max number of steps, the old percent round-trip could round back to the *same* raw level, so a tap changed nothing and the system volume slider never appeared. The buttons now step the raw stream level directly, so every tap moves the volume **and** flashes the slider.
- **No navbar flash on auto-hide** — after the bar slid off the bottom edge it could flash back into view for a single frame before disappearing; it now hides cleanly.
- **Swipe-reveal no longer scrolls the dashboard** — a swipe-up to reveal the navbar could also scroll/displace the dashboard behind it (blank space at the bottom, top cropped). The reveal strip now consumes the whole gesture (it was letting un-consumed move events fall through), and its capture zone is taller so a fast off-screen swipe lands on it reliably.

## v0.8.2 - 2026-06-16

Adds a soft on-screen navigation bar for panels whose firmware hides the native one, and completes Smatek S9E hardware support.

### Added

- **Soft navigation bar** — an on-screen overlay bar (`select.<panel>_navbar`: **Off / Always on / Swipe reveal**) for panels whose firmware suppresses the native Android navbar (e.g. NSPanel Pro). Buttons: **Back**, **Launcher** (the device launcher/app-drawer), **Recents**, and — since these panels have no physical keys — **Brightness ±** and **Volume ±** (tap to step, press-and-hold to ramp; volume shows the system slider). On wide panels (e.g. the landscape TPA10) the live brightness/volume **percentage** shows between each ± pair. Back/Recents fire via root `input keyevent` where the app can `su` (no accessibility service needed), falling back to the accessibility service only where su is sandbox-blocked; **Recents is omitted on panels whose firmware has no overview screen** rather than presenting a dead control. Button presses show a highlight **held until the action completes** (so a ~250 ms root key-injection doesn't look ignored), and *Swipe reveal* hides the bar behind a bottom-edge strip that slides it up on touch and auto-hides.
- Drawing the overlay needs `SYSTEM_ALERT_WINDOW`; it's self-granted via in-app `su` on SuperSU panels, and `provision.sh` grants it for sandbox-walled panels (Tuya TPA10) that can't.

### Fixed

- **Smatek S9E now detected** — it was falling back to the generic profile (reported via its generic `rk3566_r` Build fields), which hid its two mains relays and four button LEDs. Detection now also matches the vendor model code in `ro.product.version` (`S9…`), so the S9E picks up its profile (relays, button LEDs at gpio 147–150, Smatek/S9E labels).
- **S9E relays visible on current firmware** — the relay sysfs class was renamed `st_relay` → `strelay` between S9E firmware 1.0.2 and 1.1.0, so ha-paneld now probes both names and uses whichever the panel actually exposes. Previously only the original 1.0.2 image worked; relays now surface on the 1.1.0+ firmware most panels ship. *Still untested on hardware.*
- **S9E button LEDs export their GPIOs** — gpio 147–150 aren't exported at boot (the firmware exports only gpio113), so the LED nodes didn't exist and the lights never appeared; ha-paneld now exports (and sets to output) each pin on demand before use. *Still untested on hardware.*

## v0.8.1 - 2026-06-10

Locks the HTTP control surface to the local network, makes the info page screenshot-safe, and adds a one-click jump to the panel's Home Assistant device page.

### Added

- **LAN-only control surface** — the HTTP API/UI (`:8888`) now refuses any request whose source isn't local (loopback / RFC1918 / link-local / IPv6 ULA). On a dual-stack panel this **closes the surface off from the public internet over a routable IPv6**, rather than relying on the home router to firewall inbound v6.
- **Screenshot-safe info page** — identity and network values (Device ID, MQTT broker, globally-routable IP/IPv6) are **blurred by default**; click a value, or the **Reveal** toggle, to show them (auto re-blurs). Only *globally-routable* addresses blur — a LAN RFC1918 / ULA address stays visible. Config-form fields blur the text only (the field outline stays crisp, and focusing a field reveals it for editing).
- **"Open in Home Assistant"** — when the panel's MQTT credentials are also a Home Assistant user, the info page resolves the panel's **own HA device page** and links straight to it. Works for **non-admin** HA users and across reverse-proxy / tunnel setups (it resolves the device id from HA's entity registry over the WebSocket API, and finds HA via the MQTT broker host when mDNS can't reach it).
- **Screen diagonal** on the info page — calculated from resolution + dpi (assuming square pixels); click to toggle inches ↔ cm, with width × height on hover.
- **Recommended display density per model** — the density "rec" button now suggests **160 dpi (86P) / 250 dpi (120P)** at text-scale 1.0.

### Fixed

- **Config-form race** — saving the config form twice in quick succession could leave MQTT stopped or half-connected (the "resubmit until it sticks" symptom); config reloads are now serialized so they can't interleave. The setup banner no longer reports "needs the MQTT broker" while the broker is merely mid-reconnect.

### Docs

- First **unit-test harness** — JVM tests (no emulator) covering the config-reload serialization and the setup-banner logic, so those regressions are guarded.

## v0.8.0 - 2026-06-08

On-panel auto-brightness, a major hardening of NSPanel Pro hardware support, and an overhauled info / diagnostics page.

### Added

- **Auto-brightness** *(opt-in)* — `switch.<panel>_auto_brightness` drives the backlight from a lux stream (the panel's own ambient-light sensor, or HA-fed `number.<panel>_ambient_lux` on sensor-less panels), with an asymmetric response (snappy on lights-on, smoothed on daylight drift) and a Dimmer↔Brighter bias. Off by default.
- **Controls card** (software nav bar) — Back / Recents / Launcher (reach Settings & other apps), plus Vol−/Vol+ and a confirmed Reboot, for panels with no physical nav bar. Each button **disables itself when its capability is absent** (Back/Recents need the accessibility service; Launcher/Reboot need root).
- **Auto-reload on update** — an open info-page tab reloads itself when the app is updated (reload-banner fallback while you're mid-edit in a field).
- **Debug sensor trace** *(instrumentation)* — `GET /sensortrace` exposes a RAM ring-buffer of raw lux / proximity samples + the auto-brightness internals (CSV or JSON) for filter fit-testing.

### Changed

- **Panel info reorganised** into separate cards — **Panel information**, **Networking**, **ha-paneld profile** (the device-profile-declared rows, linked to the profile source), alongside **Capabilities** — so it scales across columns instead of one ever-growing card.
- **Richer hardware readout** — a **Model · firmware** row (distinguishes 86P / 120P / 86P-Gen2), Zigbee provenance, **total eMMC** storage (matches the box spec), and Light / Proximity rows showing **technology · value-type · range** (e.g. `Infrared · Integer · 0–9 cm`). Proximity graded-vs-binary is decided authoritatively from the firmware version where the profile knows the rule.
- **Info-page layout** rebuilt on native CSS multi-column masonry — balanced columns with near-zero layout-shift (CLS) from phone to 15″ panels; live tables wrap full values (touch can't hover a tooltip); dark-mode polish throughout.
- **Diagnostics (`/diag`) rewritten** — terse, version-stamped, reflects every field ha-paneld detects, **safe to paste into a public issue** (network addresses + instance identifiers omitted), and structured for ingest into a regression-test harness.
- **Network ADB readout** distinguishes *active now* from *persistent* (survives a reboot).

### Fixed

- **NSPanel Pro Zigbee** — detects both the stock **vendor-native** gateway and the NSPanelTools-managed one; the `switch.<panel>_zigbee_router` works on both, an explicit *off* persists across reboot, and the **120P vendor-guard CPU-spin** is resolved (ha-paneld no longer starts a duplicate guard).
- **Accurate relay reporting** — Gen1 NSPanel Pro (86P / 120P) correctly shows **no relays**; the PX30 kernel's phantom `st_relay` nodes are no longer mistaken for hardware.

### Docs

- **Firmware backup & restore guide** for button-less (Rockchip) panels.

## v0.7.1 - 2026-06-07

Hardware buttons, CPU/display controls, and per-panel identity.

- **Hardware buttons instrumented via the daemon's evdev reader** — keys Android doesn't deliver to
  apps now reach HA:
    - **WF1589T power button** — ha-paneld **suppresses the button's built-in screen-lock** and
      instead publishes each press as an `event.<panel>_button` event, so its action is **decided by
      Home Assistant** (an automation), not hard-wired to lock the panel. (The PMIC's long-press
      hardware power-off is unchanged.)
    - **TPA10 5th (orange) button** — reports `SW_MUTE_DEVICE` (a switch, not a key), which is why
      stock firmware never surfaced it; now published as a `KEYCODE_MUTE` event.
- **CPU profile tiers** — the CPU governor select is now three intent-based options — **Performance /
  Efficiency / Auto** — instead of raw kernel governor names. Auto maps to the SoC's dynamic governor
  (ramps up on interaction, idles low — best for a mains, 24/7 panel).
- **Display sizing** *(experimental / R&D)* — set display **density** and **text size** to match an HA
  dashboard to a desktop browser (Android panels often ship these mismatched to the physical screen).
  Root panels only; the right per-panel values aren't dialled in yet — see
  [docs/display-sizing.md](docs/display-sizing.md).
- **Per-panel HA device identity** — manufacturer/model defaults per panel (Sonoff / NSPanel Pro,
  Tuya / TPA10, Electron / WF1589T, Smatek / S9E; inferred from `Build.*` on unknown panels). The
  default model carries a " (ha-paneld)" suffix so the device is distinguishable from a co-installed
  integration managing the same hardware; the Configure form's value overrides it verbatim.
- **Theme-aware App UI** — the on-panel standing screen follows the panel's light/dark setting.
- **Stable HTTP performance table** — optional rows latch with a `–` placeholder instead of vanishing,
  so the page no longer jumps as metrics come and go.
- **README hero renders on both GitHub themes** (light/dark `<picture>` wordmark).
- **Security policy** — `SECURITY.md` + GitHub Private Vulnerability Reporting enabled.
- **Docs** — TPA10 hardware doc now covers all three button classes, incl. the recessed pin-hole
  (Rockchip factory-reset / MASKROM-loader, not a Linux input).

## v0.7.0 - 2026-06-06

Architecture-focused release (no new entities).

- **Device-profile architecture** — each supported panel now has a single canonical silo
  (`device/<panel>.kt`) declaring its quirks/paths; the LED, Zigbee and relay controllers read the
  active profile (detected once at startup) instead of hardcoding device specifics, while still
  runtime-probing to confirm. An unrecognised panel falls back to a Generic profile and works for
  whatever it physically has. The detected platform is shown on the info page. No change to the HA
  entities. Design: [docs/architecture/device-profiles.md](docs/architecture/device-profiles.md).
- **Security hardening** — the Zigbee role-switch is allowlisted before any shell interpolation; the
  security posture (LAN-trust, network-layer access control, HA-auth as the future path) is documented
  in [docs/architecture/security.md](docs/architecture/security.md).
- **Docs** — a "Why not Fully Kiosk?" section; releases are now cut as contenders (tagged only on
  approval), per [docs/RELEASING.md](docs/RELEASING.md).

## v0.6.3 - 2026-06-05

Small fixes and polish; documents the 0.7.0 roadmap.

- **Better entity icons** — `mdi:adb` for Network ADB and `mdi:monitor-dashboard` for Navigate
  (the previous `mdi:android-debug-bridge` isn't a valid MDI name and rendered blank).
- **Fleet update fix** — `scripts/update-fleet.sh` no longer reads panels from a non-tty stdin when they
  were given as args (a pipeline/CI stdin had clobbered the panel list).
- **Roadmap** — documents the **0.7.0 device-profile architecture refactor** (architecture only, no new
  features): [docs/architecture/device-profiles.md](docs/architecture/device-profiles.md). DLNA renderer
  reframed as under-consideration (it would be a separate HA device; the TTS recipe already covers announce).

## v0.6.2 - 2026-06-05

New controls (root/su panels):

- **CPU governor** (`select.<panel>_cpu_governor`) — set the scaling governor across all cores
  (powersave ↔ performance) to trade panel heat/noise against dashboard responsiveness; options come
  from the kernel's `scaling_available_governors`. Automatable from HA (e.g. powersave when empty).
- **Persistent network adb** (`switch.<panel>_network_adb`, opt-in) — keep `adb tcpip 5555` across
  reboots via `persist.adb.tcp.port`, plus a `provision.sh --persist-adb` flag. Leaves a standing LAN
  adb port, so it's off by default.
- **Smatek S9E button LEDs** (`light.<panel>_button_led1..4`) — the four button LEDs (gpio147–150).
  Experimental/untested like the rest of S9E.

Other changes:

- **IPv6** — the HTTP server now binds dual-stack (`::`), so the panel UI/API answer on IPv6 as well as
  IPv4, and the info page shows the panel's IPv6 address.
- **Navigate is local-only** (`text.<panel>_navigate`) — any scheme + host is stripped from the posted
  value and navigation is driven via the in-app `homeassistant://navigate/<path>` deep link. This stops
  the HA Companion opening a disorienting WebView for external URLs; the entity now holds a local path
  (defaults to `/` instead of `unknown`).
- **More robust Zigbee gateway detection** — the router switch is now gated on the guard script ha-paneld
  actually invokes, not just the `package_version` marker. This still shows on a configured panel that
  lost only the marker, and correctly hides on panels left with an empty `siliconlabs_host` dir + an
  orphaned `zgateway` (where ON could not restart it and it wouldn't survive a reboot).
- **Zigbee toggle no longer blocks MQTT** — the slow vendor lifecycle (OFF ~8 s; ON's gateway spawns on
  a ~30 s timer) now runs off the MQTT callback thread, publishing optimistically then reconciling to
  the real running state (polling for the slow start).
- **Fleet updates that don't leave panels dead** — `adb install -r` puts the app in Android's *stopped*
  state, which never auto-starts (not even via `START_STICKY`) until something launches it, so a bare
  install loop leaves panels installed-but-dead (entities `unavailable` in HA). `scripts/provision.sh`
  now retries the launch and explains why it's mandatory, and a new `scripts/update-fleet.sh` wraps
  provision.sh across many panels (downloading the release once) so every panel is installed **and
  launched and verified**.
- Docs: refreshed the on-panel launcher screenshot to the v0.6.x responsive UI (480×480).

## v0.6.1 - 2026-06-05

Zigbee router control for the Sonoff NSPanel Pro (the only panel with a Zigbee radio).

- **`switch.<panel>_zigbee_router`** — turn the panel's built-in Silicon Labs EFR32 into a Zigbee
  router/repeater (extends an existing mesh) and back off. ON starts the Sonoff `zgateway` host stack
  (kept alive by its own supervisor) and ensures router mode; OFF stops it and frees the radio.
- **Info-page Zigbee row** — shows the installed gateway driver and version (e.g. `sonoff 3.7.1`),
  whether it's running, and the current network role.
- Implementation is local and credential-free: ha-paneld talks to the panel's on-device mosquitto
  broker (`zigbee/system/network-role/…`); no firmware reflash, no `/dev/ttyS5` handling. The router
  appears as a normal device in your ZHA/Zigbee2MQTT coordinator, with its own signal/last-seen there.
- **Smatek S9E (barebones, experimental/untested)** — on-board **relays** as `switch.<panel>_relay1/2`
  (`/sys/class/st_relay`, gated on presence), and the four buttons reported as `event.<panel>_button`
  (`KEYCODE_F1`–`F4`). Derived from vendor paths in seaky#98; no S9E was available to validate, and the
  relays switch mains loads — treat as experimental. Button LEDs + proximity radar documented, not yet
  wired. See [docs/hardware/s9e.md](docs/hardware/s9e.md).

## v0.6.0 - 2026-06-05

New entities and on-panel UX — all app-side (no daemon or root changes).

- **Temperature + humidity sensors** — `sensor.<panel>_{temperature,humidity}` where the panel has
  them (e.g. the TPA10's CHT8305), read via `SensorManager`. Recorder-friendly (on-change only, sane
  deltas + min interval, rounded values); the illuminance gate was also relaxed (2 s → 15 s).
- **Button-backlight light** — `light.<panel>_buttons` (brightness) on sysfs-LED panels, via the
  helper daemon.
- **Remote nav actions** — `button.<panel>_{back,recents}` via the accessibility service
  (`performGlobalAction`); uniform from HA dashboards/scripts, no root.
- **Wake-on-wave** — local, instant screen wake on proximity-near (`switch.<panel>_wake_on_wave`,
  default on where a proximity sensor exists). Sleep stays HA's job.
- **Auto-return to dashboard** — after an app update the launcher UI bounces back to the Home
  Assistant app once connected (touch cancels; configurable).
- **Config QR code** — scan the panel's config URL instead of typing it; the App UI is now responsive
  (fits 480×480 without scrolling, larger on roomy panels).
- **Touch-sound switch** — `switch.<panel>_touch_sound` for consistent UI click sound across the fleet.
- **TTS recipe** — `docs/tts.md`: render with any HA engine (Piper/Cloud) → panel `/play`.

## v0.5.1 - 2026-06-05

- **Removed the device admin.** It was effectively unused — screen-off already powers the backlight
  via the root helper daemon / `su` (`bl_power`), never `lockNow()` — but once activated on an older
  build it **blocked the app's own uninstall** (`DELETE_FAILED_DEVICE_POLICY_MANAGER`), and Android
  won't remove a non-test admin via `dpm`. Dropping it removes that trap entirely; fresh installs are
  never affected. Upgrading from a build where you'd activated it: deactivate it under **Settings →
  Security → Device admin apps** before uninstalling.

## v0.5.0 - 2026-06-05

The first release aimed at general use — a redesigned web UI, an in-app config screen, deeper
performance insight, and proper release signing. Especially for Tuya TPA10 owners.

**Web UI**

- Redesigned as a responsive **masonry dashboard** — single column on a phone, multiple columns on a
  wide screen, with full-width charts and labelled gridlines you can actually read.
- A **new app icon** — a wall panel showing the Home Assistant mark.
- **REST API explorer** at `/api` (try any endpoint from the browser) plus an OpenAPI spec at
  `/openapi.json` you can import into Swagger or Postman for fleet tooling.

**On the panel**

- Tapping the launcher icon now opens a proper **info screen** (status, the config URL, and buttons to
  open configuration or the Home Assistant app) instead of dropping back to the launcher.
- **Configure on the panel itself** — the config page opens in an in-app browser, so kiosk panels with
  no browser installed still work.

**Performance**

- **CPU clock** (current vs hardware max) so thermal throttling is visible, plus a **responsiveness**
  metric that reflects how the dashboard actually feels.
- **Top processes** — live top-5 by CPU, to confirm the dashboard is getting the CPU. Needs root.
- **1-click WebView DevTools** relay for deep dashboard debugging — no `adb` cable needed.
- Instrumentation now **only samples while you're viewing the page** and can be **switched off**, so
  the performance tool isn't itself a constant background cost.

**Proximity**

- Calibrate the near/far cutoff from the web page. A guided "capture near / capture far" flow handles
  the wildly different sensor scales and inverted polarity across panels; sensitivity (High/Med/Low)
  controls flap resistance. The raw value stays on the panel (shown live); only a clean on/off goes to
  Home Assistant, so a graded sensor can't flood the recorder. Fixes proximity being effectively dead
  on panels whose raw value exceeds the reported sensor range.

**Fleet & releases**

- **Config API** — read/update any setting over HTTP (`GET /config`; partial-merge `POST /config`).
- **One-command provisioning** (`scripts/provision.sh`) — install, grant permissions, set id/MQTT;
  idempotent, with a `--verify` check; runs on Linux, macOS, and Windows (Git Bash/WSL).
- Releases are now **signed with a dedicated release key**; the README documents the signing model and
  the build/fork steps.

## v0.4.2 - 2026-06-04

Self-diagnostics, so you can tell what works on *your* panel — and get help when it doesn't.

- **Capabilities matrix** on the web page: every feature (screen-off, LED, buttons, root, brightness)
  shown as working / degraded / unavailable, each with the exact command to fix a shortfall on your
  firmware.
- **`/diag` dump** — one-click hardware / firmware / SELinux / sensor report to paste into a bug
  report, so a panel can be diagnosed without the maintainer owning that hardware.
- New **[performance tuning guide](docs/performance.md)** — why HA wall panels get slow (the
  WebSocket event firehose) and how to fix it.

## v0.4.1 - 2026-06-04

First public release. Live diagnostics and navigation for keyless panels.

- **Live performance view** on the web page — CPU / GPU / RAM history chart, load average, temperature.
- **Launcher** and **Home** buttons for panels with no physical Home/Back keys.
- **True screen-off** on Sonoff PX30 panels (real backlight-off, not just dimming) — no lock screen / PIN.
- HA device card enriched with configurable manufacturer / model, firmware and serial, plus a **Visit**
  link to the panel's web page.
- MQTT auto-reconnect and retained-state restore after a broker or panel restart.

## v0.4.0 - 2026-06-04

Hardware control across panel types, and a configuration web page.

- **RGB LED**: clean-room native driver for rk3576 panels (`/dev/ledjni` — no vendor blob, no root)
  and a root helper daemon for sysfs-LED panels (Tuya TPA10).
- **Panel web page** for status and configuration — no per-device app UI needed.
- **True screen-off** and **boot-persistence** for the helper daemon.
- Reproducible **Docker build** so anyone can build the APK without a local toolchain.

## v0.3.0 - 2026-06-03

The control API — Home Assistant can now drive the panel, validated on real hardware.

- **Brightness** and **sleep / wake** (`light.<panel>_screen`).
- **RGB LED** (`light.<panel>_led`), **navigate-to-URL** (`text.<panel>_navigate`), and
  **hardware-button events** (`event.<panel>_button`).
- **TTS volume** control (0–100%); TTS plays on the media stream, not the quiet accessibility stream.
- **Reload dashboard** and **reboot** buttons (root).
- Panel **light + proximity** sensors exposed to HA.
- Builds signed with a stable keystore so updates install in place (no uninstall dance).

## v0.1.0-MVP - 2026-06-03

Initial release — the TTS receiver, reimplemented as a proper Android app.

- **`POST /play`** TTS-announce HTTP contract (replaces the bash receiver).
- **MQTT auto-discovery**, **mDNS** advertisement, and a foreground service that survives reboots.
