# ha-paneld

A small Android agent for **wall-mounted Home Assistant panels**. It exposes panel-side hardware
to Home Assistant over HTTP + MQTT auto-discovery + mDNS, so a panel pairs itself with HA when you
sideload the APK — no per-device YAML.

It is built for panel-class Android (Sonoff NSPanelPro, Tuya TPA10, and similar), **not** personal
phones. The official HA Companion app remains the HOME launcher and dashboard; ha-paneld runs as a
headless foreground service alongside it and never takes the foreground.

> **Status: v0.x preview.** The API is not yet stable and breaking changes are expected until
> v1.0.0. Use it on a panel you're comfortable re-flashing.

## Why not just the Companion app?

The Companion app targets personal phones. Wall panels need different primitives: arbitrary-URL
audio announcements, screen/LED/button control (via the bundled NDK or a small root helper),
hardware-button events back to HA, and turnkey mDNS pairing. ha-paneld covers those; Companion keeps
doing what it does (and remains the dashboard host).

## Capabilities

| Cap | Surface |
|-----|---------|
| TTS / announce audio | `POST /play` + `number.<panel>_volume` (HA has no MQTT media_player platform) |
| Screen brightness | `light.<panel>_screen` brightness |
| Screen on/off (true backlight off, no lock/PIN) | `light.<panel>_screen` on/off |
| RGB LED | `light.<panel>_led` (per-panel HAL: rk3576 NDK `/dev/ledjni`, or sysfs via the root helper) |
| URL navigate | `text.<panel>_navigate` |
| Hardware-button events | `event.<panel>_button` (a11y key capture) |
| Ambient light / proximity (data only) | `sensor.<panel>_illuminance`, `binary_sensor.<panel>_proximity` |
| Reload dashboard / reboot | `button.<panel>_reload`, `button.<panel>_reboot` |
| Launcher / Home Assistant (bring a launcher or the HA dashboard forward) | `button.<panel>_launcher`, `button.<panel>_home` |
| Panel info + config web page | `GET /` (the device "Visit" link) |

> [!NOTE]
> ha-paneld exposes the panel's light + proximity sensors as data (standard `SensorManager`), but
> they are **not** the occupancy/lux authority — room-level HA sensors (motion, lux, occupancy) are,
> being better placed and already calibrated. Brightness is therefore **HA-driven**: ha-paneld
> exposes the brightness actuator; the policy (from room sensors) lives in Home Assistant. Zigbee
> gateway and app-watchdog are out of scope (coexist with a dedicated tool if you need them).

## The control API — uniform MQTT entities

Every panel publishes the **same** Home Assistant MQTT-discovery entities, regardless of underlying
hardware (the per-panel HAL is hidden behind them). Configure an MQTT broker and they appear with
no YAML:

| Entity | Capability | Notes |
|--------|------------|-------|
| `light.<panel>_screen` | brightness + on/off | on = backlight on, off = true backlight-off (no keyguard/PIN); JSON schema, brightness 0–255 |
| `light.<panel>_led` | RGB | published only when a LED backend is present (NDK `/dev/ledjni` or the root helper) |
| `text.<panel>_navigate` | push a URL to the panel | depends on Companion intent handling; last URL restored on reconnect |
| `event.<panel>_button` | hardware button presses | published only when the a11y key-filter is enabled |
| `number.<panel>_volume` | TTS/announce volume | 0–100% → `STREAM_MUSIC`; playback is the HTTP `/play` contract below |
| `sensor.<panel>_illuminance` | ambient lux | standard `SensorManager` `TYPE_LIGHT`; published only if present |
| `binary_sensor.<panel>_proximity` | proximity (occupancy) | standard `SensorManager` `TYPE_PROXIMITY`; published only if present |
| `button.<panel>_reload` | reload dashboard | force-stop + relaunch the configured dashboard package (root helper, else `su`) |
| `button.<panel>_reboot` | reboot panel | root helper, else `su` |
| `button.<panel>_launcher` | bring a launcher to the foreground | fires `CATEGORY_HOME` at a non-default launcher (or configured `launcher_package`), leaving the boot/default home app unchanged |
| `button.<panel>_home` | bring the HA dashboard to the foreground | launches `dashboard_package` if set, else the default home app (the HA Companion) — the complement of the Launcher button |

The device's display name (`configuration_url` "Visit" link, friendly name) and the LED/screen
states are re-published on every (re)connect, and the MQTT client auto-reconnects, so HA stays in
sync after a panel reboot or broker blip.

## HTTP contract

```text
GET  /              panel info + config page (versions, hardware, status; panel_id,
                    friendly name, MQTT broker/creds, dashboard package). This is the
                    device's configuration_url, so HA shows a "Visit" link.
POST /config        form-encoded settings from the page; persists + live-reconfigures
GET  /perf          live performance JSON (CPU % overall + per core, load avg,
                    per-core MHz, temperature, memory) — polled by the info page
GET  /diag          copy-paste diagnostics dump (build, SELinux, su probe, /dev +
                    /sys node listings, packages, capability assessment)
GET  /health        -> 200 "ha-paneld <version> panel=<id>"
POST /play          body contains an audio URL (raw or {"url":"…"})
                    -> 200 "playing"  (download + play happen in the background)
                    -> 400 "no-url"   (no URL found in body)
```

The web page at `/` is how a user sets the **MQTT broker** without adb — find the panel's IP (mDNS
`_ha-paneld._tcp`, or the router), open `http://<ip>:8888/`, fill in the broker + credentials, Save.

The agent listens on **:8888**. Self-signed HTTPS sources are accepted (panels live on a trusted
LAN). This is the same contract as the reference shell receiver it replaces, so HA-side automation
needs no change when a panel migrates from the shell receiver to ha-paneld.

## Pairing

The agent advertises `_ha-paneld._tcp.local.` with TXT records (`ver`, `caps`, `path`). If an MQTT
broker is configured it publishes Home Assistant MQTT-discovery configs so panel entities appear
without YAML. With no broker configured, the HTTP surface still works standalone.

## Provisioning (no device UI on rooted/userdebug panels)

All permissions are granted over adb — there is no per-device tap-through. Run the same script on
every panel (config-as-code):

```bash
scripts/provision.sh <panel-ip:5555> [path-to.apk]
```

It installs the APK and grants: `POST_NOTIFICATIONS`, `WRITE_SETTINGS` (brightness, via `appops`),
device-admin force-lock (`dpm set-active-admin`, for sleep), and optionally the accessibility
key-filter (buttons). None of these need a UI on a panel with `su`/adb-root.

Non-root panels: use the in-app setup screen, which fires the standard system permission intents.

**Permission → why:**

| Permission | For | Grant |
|------------|-----|-------|
| `POST_NOTIFICATIONS` | foreground-service notification | runtime / `pm grant` |
| `WRITE_SETTINGS` | screen brightness | `appops set <pkg> WRITE_SETTINGS allow` |
| Device admin (force-lock) | screen sleep (`lockNow`) | `dpm set-active-admin <pkg>/.control.PanelAdminReceiver` |
| Accessibility (key filter) | button events (optional) | `settings put secure enabled_accessibility_services …` |

Device-admin uses **active-admin**, not device-owner (device-owner needs an account-free device and
would conflict with the logged-in Companion).

## RGB LED — clean-room NDK, no vendor library

On the rk3576 panel (Electron WF1589T) the front RGB LED is reached via the char device
`/dev/ledjni`, which is world-rwx and labelled app-accessible (SELinux `device` domain), so a
normal app drives it **without root or a helper**. ha-paneld ships its **own** ~70-line NDK driver
([`app/src/main/cpp/led_jni.c`](app/src/main/cpp/led_jni.c)) doing the ioctls directly. The
protocol (request numbers, value range, open flags) was reverse-engineered clean-room from a
hardware sample — an interop fact, not vendor code — so **no vendor library is bundled or required**
(`libjnielc.so` is no longer used). The LED entity is published only on panels where `/dev/ledjni`
is openable; it is simply absent elsewhere.

Other panels (e.g. Tuya TPA10) expose their LED only through root-only `/sys/class/leds/*`. A
sandboxed app cannot reach those (SELinux `untrusted_app` cannot exec `su` nor write `sysfs_leds`),
so those panels need a small **root helper daemon** that ha-paneld talks to over a localhost socket
— planned, not yet shipped.

## Supported hardware

ha-paneld needs no system-signed install. Standard-Android capabilities (brightness, sleep,
navigate, TTS) work on any panel; LED/buttons depend on a per-panel HAL.

| Panel class | SoC | Android | ABI | Notes |
|-------------|-----|---------|-----|-------|
| Sonoff NSPanelPro / Pro120 | Rockchip PX30 | 8.1 (API 27) | arm64-v8a | toolbox `su` |
| Tuya TPA10 | Rockchip rk3566 | 11 (API 30) | armeabi-v7a | 32-bit userspace |
| Electron WF1589T | Rockchip rk3576 | userdebug (`adb root`) | arm64-v8a | RGB LED via clean-room NDK ioctl on `/dev/ledjni` (no vendor lib) |

Other Android panels are welcome — contribute a HAL adapter for your hardware.

**minSdk is 26.** API < 26 is unsupported (the MQTT client cannot connect below API 26).

## Build

### Option A — Docker (no toolchain, no CI access needed)

Only Docker is required. The script builds a version-pinned image (JDK 17 + Android SDK 35 + NDK +
CMake, matching CI) and runs Gradle inside it; the APK lands in your working tree.

```sh
./tools/build/build.sh                       # debug APK -> app/build/outputs/apk/debug/
./tools/build/build.sh :app:assembleRelease  # any Gradle task(s) instead
```

The image is built once and cached; Gradle caches persist in a named Docker volume, so repeat
builds are fast. See [`tools/build/`](tools/build/) (and the `HOST_WORKDIR` note in `build.sh` if
you run from inside a container talking to an outer Docker daemon).

### Option B — local toolchain

```sh
./gradlew :app:assembleDebug      # debug APK -> app/build/outputs/apk/debug/
./gradlew :app:assembleRelease    # release APK (unsigned in CI unless signing is configured)
```

Requires **JDK 17** and an Android SDK with **NDK 27.0.12077973 + CMake 3.22.1** (for the native
`/dev/ledjni` LED driver). The Gradle wrapper pins the Gradle version; nothing else needs installing.

### Toolchain note

The build is pinned to a conservative AGP 8.7 / Kotlin 2.0 / Gradle 8.10 combo for reliable
first-run CI. Newer AGP/Kotlin is fine to adopt during the v0.x line — versions live in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml).

### Signing — what forkers need to know

You don't need to configure signing to build and run ha-paneld. Two cases:

- **Dev / fork builds** are signed with the **committed `debug.keystore`** (password `android`). It's
  in the repo on purpose — not a secret — so every build (yours, mine, CI's) shares one signature.
  That's what lets `install -r` update a panel in place without uninstalling. Just build and install.
- **Official releases** are signed with a private key held in GitHub Actions secrets
  (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`).
  A fork won't have those, so a tagged release in your fork falls back to a **debug-signed** APK —
  fine for personal use.

> [!IMPORTANT]
> Android refuses to update an installed app with an APK signed by a **different** key. So you cannot
> install your own debug-signed build over an installed *official* (release-signed) build, or vice
> versa — `adb`/the installer rejects it with a signature mismatch. Uninstall first
> (`adb uninstall io.github.maxlyth.hapaneld`), then install the other build. Uninstalling clears the
> panel's saved config, so re-run provisioning afterwards. This is the one thing that trips people up.

**Signing your own fork's releases (optional):**

```sh
keytool -genkeypair -storetype PKCS12 -keystore release.jks -alias ha-paneld \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ha-paneld"
base64 -w0 release.jks      # paste the output into the ANDROID_KEYSTORE_BASE64 repo secret
```

Use one password for both `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_PASSWORD`, and `ha-paneld`
(your alias) for `ANDROID_KEY_ALIAS`. Back up `release.jks` and the password safely — losing them
means you can never publish an in-place update again. Never commit the keystore (`*.jks` is gitignored).

## Status & roadmap

**v0.4.1 (preview)** — validated across the panel fleet: Sonoff NSPanel Pro (PX30, Android 8.1),
Tuya TPA10 (rk3566, Android 11), Electron WF1589T (rk3576, Android 14).

Shipped in 0.4.x:

- Uniform MQTT control — screen (brightness + **true** backlight-off), RGB LED, navigate, volume,
  TTS, hardware buttons, reload, reboot, **launcher**, **home (HA)**.
- Per-hardware LED HAL — rk3576 clean-room NDK `/dev/ledjni` (app-direct); sysfs panels via a root
  helper daemon with a boot-persistent `init` service.
- Lock-free screen-off — daemon `bl_power`, or `su bl_power` on PX30, else a brightness fallback;
  never a keyguard/PIN.
- Panel web UI (`GET /`) — versions + hardware (CPU/RAM/storage/firmware/Device ID), a **live
  CPU/GPU/RAM history chart** (server-side FIFO), and a config form (panel id, friendly name, MQTT
  broker/creds, manufacturer/model, dashboard + launcher packages).
- HA device card — manufacturer/model, firmware (`hw_version`), serial; `configuration_url` "Visit"
  link. MQTT auto-reconnect + retained-state restore on (re)connect.
- Self-diagnostics — a **Capabilities** matrix (what works on this firmware + how to fix shortfalls)
  and a `/diag` dump for bug reports. See **[docs/performance.md](docs/performance.md)** for tuning
  panels (the WebSocket-event-volume problem and how to fix it).

Planned 0.5.0:

- **DevTools/CDP relay** — an on-device bridge from the dashboard WebView's debug socket
  (`webview_devtools_remote_<pid>`) to a TCP port, plus an info-page link, for browser-based
  rendering/latency analysis **without adb** (requires WebView debugging enabled on the dashboard
  app). Exposes render latency (frame times, main-thread long tasks), data latency (WebSocket
  timing) and input latency via CDP; `dumpsys gfxinfo` jank as a coarse no-CDP fallback.
- Daemon boot-persistence on su-only (PX30) panels, if true-off is wanted without relying on `su`
  at runtime.

## Stack

- **HTTP** — Ktor CIO engine (coroutine I/O, no thread-per-connection).
- **MQTT** — HiveMQ MQTT 5 client (NIO transport; ABI-agnostic).
- **mDNS** — JmDNS (chosen over `NsdManager` for reliable TXT records across API levels).

## Licence

Apache-2.0. See [LICENSE](LICENSE).
