# Panel hardware references

Reverse-engineered hardware notes for the wall panels ha-paneld targets. These devices ship with
almost no public documentation, so these notes record what is physically on each board and how to
drive it — gathered from live units (rooted / userdebug `adb root`) on 2026-06-05.

| Panel | SoC | LED control | Notable sensors | NFC | Zigbee/IR | Reference |
|---|---|---|---|---|---|---|
| Tuya TPA10 | rk3566 | `avsux` sysfs (root daemon) | ToF VI5300, CHT8305 temp+humidity, CG5256 light | no | no | [tpa10.md](tpa10.md) |
| Electron WF1589T | rk3576 | `/dev/ledjni` (app-direct) | 6-axis IMU (KXTJ9 + BMA2xx) | yes — NXP, but Android-NFC disabled | no | [wf1589t.md](wf1589t.md) |
| Sonoff NSPanel Pro | rk3326 / PX30 | none (no RGB node) | STK3A5x light + proximity (app-direct) | no | **Zigbee** (Silabs EFR32, UART); no IR | [nspanel-pro.md](nspanel-pro.md) |

## Method

- **Real silicon**: bound i2c devices via `/sys/bus/i2c/devices/*/name` — *not* `…/drivers/`, because
  Rockchip BSPs compile in hundreds of optional drivers and the `drivers/` listing over-reports badly.
- **Radios**: `pm list features` (`nfc`, `consumerir`, `bluetooth`, `ethernet`, …) + `/dev` nodes.
- **Android-exposed sensors**: `dumpsys sensorservice`.
- **Control surfaces**: `/sys/class/leds`, `/dev`, and each LED node's own attributes (some panels
  self-document, e.g. the TPA10's `avsux_info` / `avsux_firmware`).

Corrections and additions for other panels are welcome.

## Performance comparison & practical deployment

Performance is the whole reason this project exists, so it's worth stating plainly: the three panel
classes form a clear ladder. Figures below are from ha-paneld's own `/perf` endpoint + device specs.

| | NSPanel Pro (PX30) | TPA10 (rk3566) | WF1589T (rk3576) |
|---|---|---|---|
| CPU | 4× Cortex-A35 @1.5 GHz | 4× Cortex-A55 @1.8 GHz | 4× A72 @2.1 GHz + 4× A53 @1.9 GHz |
| RAM | 2 GB | 2 GB | 4 GB |
| GPU | Mali-G31 | Mali-G52 (2EE) | Mali-G52 (MC3) |
| Display | 480×480 **square**, ~4" | 1920×1200 16:10, ~10" | 1920×1200 16:10, ~5.5"/~400 ppi |
| Refresh | 60 Hz | 56 Hz | 60 Hz |
| Layout (dp) | 160 dpi → 480×480 dp | 240 dpi (ovr 200) → ~1280×800 dp | 160 dpi (ovr 186) → 1920×1200 dp — UI tiny, [raise density](wf1589t.md#display-density--raise-it) |
| Class | entry-level | mid | high |

Live `/perf` snapshot (each panel under its own real workload — illustrative, not a controlled
benchmark):

| | PX30 (mostly idle) | WF1589T (active dashboard) |
|---|---|---|
| CPU | 9 % | 29 % |
| Clock | 408 MHz (of 1512) | big cores 1608 MHz (of 2112) |
| RAM used | 508 / 1960 MB | 2265 / 3897 MB |
| Temp | 49 °C | 63 °C |
| Responsiveness | smooth, main-thread 3.6 % | smooth, main-thread 25.9 % |

(TPA10 sits between the two on CPU; its sampler was off during capture.)

**What this means for a real dashboard deployment:**

- **Screen geometry is the first design constraint.** The NSPanel Pro's 480×480 **square** (480 dp)
  only fits a single narrow column; the TPA10's 10" 1920×1200 (≈1280×800 dp) is genuinely roomy for
  multi-column dashboards; the WF1589T is sharp (~400 ppi) but ships at a low logical density so the UI
  is tiny until raised. Design the dashboard to the panel's **dp canvas + aspect ratio**, not its raw
  pixel count.
- **2 GB panels (PX30, TPA10): RAM is the binding constraint.** The dashboard WebView, the HA
  Companion app and Android itself share ~2 GB; heavy dashboards (many cards, large images, long
  history graphs, heavy custom cards) trigger WebView reloads and jank. The WF1589T's 4 GB largely
  removes this pressure.
- **The NSPanel Pro — the most common panel — has the slowest CPU** (A35), so transitions and
  animations are visibly slower than on A55/A72 units. Keep its dashboards the leanest.
- **The biggest cross-panel win is cutting WebSocket event volume** reaching the panel — see
  [../performance.md](../performance.md) (the split-instance / HADS approach).
- **ha-paneld is the diagnostic for all of this**: CPU clock vs max (throttling), the responsiveness
  metric, top-5 processes and the 1-click WebView DevTools relay tell you whether the *hardware*, the
  *dashboard* or the *data feed* is the bottleneck on your specific unit — rather than guessing.

## Updating the system WebView

**Read this before anything else** — it's the single most common first-run failure on these panels.

The HA Companion app renders the Lovelace dashboard in Android's **system WebView**. Every panel here
ships with a WebView/Chromium far too old to run a current Home Assistant frontend, so out of the box
you get a **blank or broken dashboard, missing cards, or "browser not supported"**. You must update
the system WebView.

The clean way — **no F-Droid, no third-party app store** (the workarounds the NSPanel-Pro community
threads resort to) — is a direct adb sideload of the standard Android System WebView:

1. Download a current **Android System WebView** APK (package **`com.android.webview`**) from a
   trusted mirror (e.g. APKMirror). `com.android.webview` is the panel's *default* WebView provider,
   so matching that package name means it's selected automatically — no provider-allowlist editing,
   no extra app needed.
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

> [!TIP]
> Match the package name `com.android.webview`. Installing an *arbitrary* Chromium fork (Cromite,
> Bromite, …) does **not** auto-register as a WebView provider — it must be in the system's WebView
> allowlist or selected manually, which is exactly the friction that sends people to F-Droid. Sticking
> to `com.android.webview` sidesteps all of it.

Verified on the fleet (2026-06-05): TPA10 runs Chromium **147**, NSPanel Pro **138**, WF1589T **150**
after this update — all sideloaded to `/data/app`, no root.
