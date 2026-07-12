# Panel hardware references

Reverse-engineered hardware fact sheets for the wall panels ha-paneld targets — SoC, LED control, sensors, buttons, NFC, Zigbee/IR, relays, adb/root access. These devices ship with almost no public documentation, so these notes record what is physically on each board and how to drive it, gathered from live units (rooted / userdebug `adb root`).

| Panel | SoC | LED control | Notable sensors | NFC | Zigbee/IR | Reference |
|---|---|---|---|---|---|---|
| Tuya TPA10 | rk3566 | `avsux` sysfs (root daemon) | ToF VI5300, CHT8305 temp+humidity, CG5256 light | no | no | [tpa10.md](tpa10.md) |
| Electron WF1589T | rk3576 | `/dev/ledjni` (app-direct) | 6-axis IMU (KXTJ9 + BMA2xx) | yes — NXP, but Android-NFC disabled | no | [wf1589t.md](wf1589t.md) |
| Sonoff NSPanel Pro | rk3326 / PX30 | none (no RGB node) | STK3A5x light + proximity (app-direct) | no | **Zigbee** (Silabs EFR32, UART); no IR | [nspanel-pro.md](nspanel-pro.md) |
| Smatek S9E † | rk3566 | per-button GPIO LEDs (root) | radar proximity, light, temp+humidity; **2 mains relays** (`st_relay`); RS485 + Ethernet | no | **Zigbee** | [s9e.md](s9e.md) |
| ZHICAI SMT1019 ‡ | rk3576 | none (LED ioctl root-locked, no su) | none Android-exposed reported | no | no | [smt1019.md](smt1019.md) |
| ZX-SMT156 / RK3566_T ‡ | rk3566 | `/dev/ledjni` (app-direct) | binary proximity, ambient light; vendor climate path unknown | unknown | vendor relays reported, control path unknown | [zx-smt156.md](zx-smt156.md) |
| Amazon Echo Show 5 Gen 2 ‡ | MediaTek MT8163 | none | ambient light; no proximity reported | no | no | [echo-show-5-gen2.md](echo-show-5-gen2.md) |
| Shelly Wall Display § | MT6580 (legacy) / **PX30** (modern, Jenna — Smatek-built, like the S9E) | none (no RGB node) | STK3A5x light + proximity (Jenna, like NSPanel Pro); reported LD2410 radar (Blake/XL, app-only); on-board GPIO relays (1–2/model, via Stargate RPC) | no | no | [shelly-wall-display.md](shelly-wall-display.md) |

† S9E specs are from Smatek's listing; control paths are from [#98](https://github.com/seaky/nspanel_pro_tools_apk/issues/98) + the HA community thread, **not** validated on a unit here — relay/button support is implemented but untested.

‡ SMT1019 facts are from a reporter's `/diag` + the retail listing ([#8](https://github.com/maxlyth/ha-paneld/issues/8)), **not** validated on a unit here.

§ Shelly Wall Display facts are from firmware OTA analysis (incl. a device-tree parse of the modern partition image), the official changelog, and community/KB sources — **not** validated on a unit here. Both firmware tracks are *userdebug* builds, so `adb root` may be reachable if an adb foothold exists. `ShellyWallDisplay` + `ShellyWallDisplayV2` profiles are implemented but speculative.

> [!TIP]
> Before modifying firmware on a **button-less** panel, read [Firmware backup & restore](../firmware-backup-restore.md) — these are all Rockchip devices, so the usual button-combo fastboot/recovery advice does not apply; backup/restore goes via `adb reboot loader` + `rkdeveloptool` (open-source, Linux), with maskrom as the un-brickable fallback.

## Method

- **Real silicon**: bound i2c devices via `/sys/bus/i2c/devices/*/name` — *not* `…/drivers/`, because Rockchip BSPs compile in hundreds of optional drivers and the `drivers/` listing over-reports badly.
- **Radios**: `pm list features` (`nfc`, `consumerir`, `bluetooth`, `ethernet`, …) + `/dev` nodes.
- **Android-exposed sensors**: `dumpsys sensorservice`.
- **Control surfaces**: `/sys/class/leds`, `/dev`, and each LED node's own attributes (some panels self-document, e.g. the TPA10's `avsux_info` / `avsux_firmware`).

Corrections and additions for other panels are welcome.

## Gaining adb + root access

Each panel reaches adb/root differently; the per-panel pages have the full, firmware-specific steps:

- **Sonoff NSPanel Pro** — `userdebug`/test-keys, **no adb password**; the only hurdle is reaching developer mode (varies by eWeLink firmware). `adb root` + remount + a SuperSU `su`. → [nspanel-pro.md](nspanel-pro.md#gaining-adb--root-access).
- **Tuya TPA10** — adb is **password-protected**; the reliable route is the USB diagnostics-app backdoor (`su` already present). → [tpa10.md](tpa10.md#gaining-adb--root-access).
- **Electron WF1589T** — `userdebug` with Google Play; `adb root` works directly (LED is app-direct, so root is rarely needed). → [wf1589t.md](wf1589t.md).

## Performance comparison & practical deployment

The three panel classes form a clear ladder: **NSPanel Pro (PX30)** entry-level, **TPA10 (rk3566)** mid, **WF1589T (rk3576)** high. Screen geometry is the first design constraint; on the 2 GB panels RAM is the binding one. Figures are from ha-paneld's own `/perf` endpoint + device specs.

<details>
<summary>Spec ladder (CPU / RAM / GPU / display)</summary>

| | NSPanel Pro (PX30) | TPA10 (rk3566) | WF1589T (rk3576) |
|---|---|---|---|
| CPU | 4× Cortex-A35 @1.5 GHz | 4× Cortex-A55 @1.8 GHz | 4× A72 @2.1 GHz + 4× A53 @1.9 GHz |
| RAM | 2 GB | 2 GB | 4 GB |
| GPU | Mali-G31 | Mali-G52 (2EE) | Mali-G52 (MC3) |
| Display | 480×480 **square**, ~4" | 1920×1200 16:10, ~10" | 1920×1200 16:10, ~5.5"/~400 ppi |
| Refresh | 60 Hz | 56 Hz | 60 Hz |
| Layout (dp) | 160 dpi → 480×480 dp | 240 dpi (ovr 200) → ~1280×800 dp | 160 dpi (ovr 186) → 1920×1200 dp — UI tiny, [raise density](wf1589t.md#display-density--raise-it) |
| Class | entry-level | mid | high |

</details>

<details>
<summary>Live `/perf` snapshot (illustrative, not a controlled benchmark)</summary>

Each panel under its own real workload:

| | PX30 (mostly idle) | WF1589T (active dashboard) |
|---|---|---|
| CPU | 9 % | 29 % |
| Clock | 408 MHz (of 1512) | big cores 1608 MHz (of 2112) |
| RAM used | 508 / 1960 MB | 2265 / 3897 MB |
| Temp | 49 °C | 63 °C |
| Responsiveness | smooth, main-thread 3.6 % | smooth, main-thread 25.9 % |

(TPA10 sits between the two on CPU.)

</details>

**What this means for a real dashboard deployment:**

- **Screen geometry is the first design constraint.** The NSPanel Pro's 480×480 **square** (480 dp) only fits a single narrow column; the TPA10's 10" 1920×1200 (≈1280×800 dp) is genuinely roomy for multi-column dashboards; the WF1589T is sharp (~400 ppi) but ships at a low logical density so the UI is tiny until raised. Design the dashboard to the panel's **dp canvas + aspect ratio**, not its raw pixel count.
- **2 GB panels (PX30, TPA10): RAM is the binding constraint.** The dashboard WebView, the HA Companion app and Android itself share ~2 GB; heavy dashboards (many cards, large images, long history graphs, heavy custom cards) trigger WebView reloads and jank. The WF1589T's 4 GB largely removes this pressure.
- **The NSPanel Pro — the most common panel — has the slowest CPU** (A35), so transitions and animations are visibly slower than on A55/A72 units. Keep its dashboards the leanest.
- **The biggest cross-panel win is cutting WebSocket event volume** reaching the panel — see [../performance.md](../performance.md) (the split-instance approach).
- **ha-paneld is the diagnostic for all of this**: CPU clock vs max (throttling), the responsiveness metric, top-5 processes and the 1-click WebView DevTools relay tell you whether the *hardware*, the *dashboard* or the *data feed* is the bottleneck on your specific unit — rather than guessing.

## Updating the system WebView

**Read this before anything else** — it's the single most common first-run failure on these panels.

The HA Companion app renders the Lovelace dashboard in Android's **system WebView**, and most of these panels ship with one far too old to run a current Home Assistant frontend — so out of the box you get a **blank or broken dashboard, missing cards, or "browser not supported"**. Panels **without** Google Play (NSPanel Pro, TPA10) can't auto-update it, so you must **sideload** a current WebView (below). The **WF1589T has Google Play**, so just update *Android System WebView* from the Play Store (or the Play WebView dev channel) — no sideload needed.

The clean way is a direct adb sideload of the standard Android System WebView (package **`com.android.webview`**), matched to the panel's Android version and ABI — **no F-Droid, no third-party app store** (the workarounds the NSPanel-Pro community threads resort to). Per-panel known-working builds and the full sideload/verify steps are below.

> [!TIP]
> The package name must be `com.android.webview` for the system to select it automatically. Mind the distinction: the **SystemWebView** builds from Cromite and LineageOS use `com.android.webview` and *do* register as the provider — but the regular **Cromite / Bromite *browser*** app uses a different package and does **not**. Use the SystemWebView build, not the browser APK.

### Per-panel stock versions and known-working replacements

"Stock" = what the vendor firmware ships from factory, verified from firmware OTA inspection or a live device. "Replacement" = what is confirmed working after sideload. Redistributable builds are mirrored as ha-paneld Release assets; sideload with `adb install -r <file>`.

| Panel | ABI | Stock (vendor firmware) | Replacement (`com.android.webview`) | Download |
|---|---|---|---|---|
| NSPanel Pro (PX30) | arm64-v8a | **unknown** — Android 8.1 AOSP base; confirmed too old for current HA frontend | **LineageOS** 138.0.7204.63 — last build for Android **8.1** | [release asset](https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-138.0.7204.63.apk) · [APKMirror](https://www.apkmirror.com/apk/lineageos/android-system-webview-2/android-system-webview-138-0-7204-63-2-release/android-system-webview-138-0-7204-63-8-android-apk-download/download) |
| TPA10 (rk3566) | armeabi-v7a | **Chrome 83** (`com.android.webview`) — too old for current HA frontend | **LineageOS** SystemWebView 150.0.7871.63 — vanilla Chromium, allows camera autoplay (Cromite 147 blocks it, kept as fallback). **Signature-locked — needs the root swap in [tpa10.md](tpa10.md#webview--update-this-first), not a plain sideload.** | [arm asset](https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm.apk) |
| WF1589T (rk3576) | arm64-v8a | Google Play WebView (auto-updates) | update via Play Store — no sideload needed | — |
| S9E (rk3566) | armeabi-v7a | **Chromium 83** (`com.android.webview`) — too old for current HA frontend | **LineageOS** 150.0.7871.63 (arm) — *provisional, unverified hardware*; likely signature-locked like the TPA10 | [arm asset](https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm.apk) |
| SMT1019 (rk3576) | arm64-v8a | **unknown** — Android 14, no GMS; likely needs sideload | **LineageOS** 150.0.7871.63 (arm64) — *provisional, unverified hardware* | [arm64 asset](https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm64.apk) |
| ZX-SMT156 / RK3566_T | arm64-v8a | Google WebView **149.0.7827.164** (reporter firmware) | Google WebView is current; no replacement needed | — |
| Echo Show 5 Gen 2 (`cronos`) | armeabi-v7a | LineageOS WebView **146.0.7680.153** | LineageOS build is current; no replacement needed | — |
| Shelly WD legacy (MT6580) | armeabi-v7a | **unknown** (Android 7 base ROM) | Atlantis only: `com.google.android.webview` **119.0.6045.194** via [official Shelly ZIP](https://repo.shelly.cloud/firmware/SAWD-0A1XX10EU1/stable/SAWD-0A1XX10EU1-WebViewUpdate.zip) — see [shelly-wall-display.md](shelly-wall-display.md#webview) | — |
| Shelly WD V2 (arm64 / PX30) | arm64-v8a | **unknown** (Android 11 base ROM; not present in Shelly OTA) | not established — check `adb shell dumpsys webviewupdate` | — |

All mirrored builds live in the [**Panel WebView mirror** release](https://github.com/maxlyth/ha-paneld/releases/tag/webview-mirror) — intended as a living, community list of known-working versions. Got one working on another panel or version? Contributions welcome.

> [!NOTE]
> - **Pick the newest WebView your panel's Android version supports.** The NSPanel Pro's Android 8.1 caps at 138 (the last Chromium for Android 8/9); newer builds won't install. Android 10+ (the TPA10's 11) runs current **LineageOS** WebView (150).
> - **APKMirror's *direct* download links are short-lived presigned URLs that expire within the hour** — use the page, or the ha-paneld Release assets above (durable). The mirror exists precisely because panels lack Play and ship years-old firmware, and a working build can otherwise take days to find.

<details>
<summary>Sideload + verify steps</summary>

1. Download a current **Android System WebView** APK — package **`com.android.webview`**. **LineageOS** System WebView is the recommended build across Android versions: 138 is the last for Android 8.1, and 150 covers Android 10+ (both in the mirror). It's vanilla Chromium, so it doesn't carry Cromite's autoplay block that stops HA camera streams. It uses the `com.android.webview` package, so it's picked as the provider automatically (no allowlist editing, no extra app), and it's open / freely redistributable. Match your panel's ABI; per-panel downloads are above.

> [!IMPORTANT]
> The simple sideload below works on panels whose ROM waives the WebView signature check (e.g. the NSPanel Pro's userdebug build). **Signature-locked panels (the TPA10, and likely other vendor user builds) reject a plain sideload** — `signatures do not match`. Those need the one-time root swap (replace the system WebView file + clear its `packages.xml` entry); see [tpa10.md → WebView](tpa10.md#webview--update-this-first) for the exact procedure.
2. Sideload it (no root):

   ```sh
   adb install -r android-system-webview.apk
   ```

   It installs to `/data/app` and supersedes the stale stock WebView.
3. Verify the active provider + version:

   ```sh
   adb shell dumpsys webviewupdate | grep "Current WebView package"
   ```

If the panel lists more than one provider, select it explicitly:

```sh
adb shell cmd webviewupdate set-webview-implementation com.android.webview
```

or via Developer options → *WebView implementation*.

</details>

---

Per-panel fact sheets: [NSPanel Pro](nspanel-pro.md) · [TPA10](tpa10.md) · [WF1589T](wf1589t.md) · [S9E](s9e.md) · [ZX-SMT156](zx-smt156.md) · [Echo Show 5 Gen 2](echo-show-5-gen2.md).
