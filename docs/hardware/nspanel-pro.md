# Sonoff NSPanel Pro (Rockchip PX30 / rk3326)

Hardware reference for the **Sonoff NSPanel Pro** (and Pro120) — the most common Home-Assistant wall
panel on the market. Reverse-engineered on a live unit (Android 8.1, rooted, toolbox `su`) on
2026-06-05.

| | |
|---|---|
| SoC | Rockchip **PX30 / rk3326** |
| CPU | 4× **Cortex-A35** @ up to **1.512 GHz** (idles at 408 MHz) |
| GPU | **Mali-G31** (device-confirmed) |
| RAM | **2 GB** (≈1960 MB usable) |
| Storage | eMMC; `/data` ≈ 3.5 GB |
| Android | 8.1 (API 27) |
| ABI | arm64-v8a |
| Radios | Wi-Fi, Bluetooth. **No NFC, zigbee, IR, ethernet, cellular.** |

> [!NOTE]
> The Cortex-A35 is an efficiency core with markedly lower per-clock throughput than the A55 (TPA10)
> or A72 (WF1589T). Combined with 2 GB RAM, the NSPanel Pro is the **entry-level performer** of the
> three panels documented here — see the [performance comparison](README.md#performance-comparison--practical-deployment).

## WebView — update this first

The NSPanel Pro ships with a WebView/Chromium far too old to render a current Home Assistant dashboard
(blank/broken UI in the HA Companion app). Update it cleanly over adb — no root, no F-Droid; this unit
runs Chromium **138** afterwards. See [Updating the system WebView](README.md#updating-the-system-webview).

## Bound i2c devices (real hardware)

| i2c addr | driver / name | What it is |
|---|---|---|
| `0-0020` | `rk809` | PMIC |
| `1-001a` / `1-005a` | `CST226` / `CST226SE` | Hynitron capacitive touch controller |
| `2-003c` | `tp` | touch panel |
| `2-0046` | `ls_stk3a5x` + `ps_stk3a5x` | Sensortek **STK3A5x** ambient-light + proximity combo |
| `2-0047` | `ls_stk3x3x` + `ps_stk3x3x` | Sensortek **STK3x3x** light + proximity (alt variant) |

## Sensors — light + proximity are app-direct

Unlike the TPA10 (where light/temp are root-only), the NSPanel Pro exposes its Sensortek combo through
standard `SensorManager`: `android.sensor.light`, `android.sensor.proximity`, and
`android.sensor.accelerometer` — all readable by a normal app, no root. ha-paneld reads light +
proximity here directly. No temperature/humidity sensor is fitted.

## LED

No `/sys/class/leds` RGB node and no `/dev/ledjni` were found on this unit, so there is **no
app/​sysfs-controllable RGB LED characterised** on the NSPanel Pro (contrast the TPA10's `avsux` node
and the WF1589T's `/dev/ledjni`). Screen brightness/backlight use the standard Android paths.

## Access model summary

- **Light / proximity / accelerometer**: app-direct (`SensorManager`).
- **Screen brightness / sleep / navigate / TTS**: standard Android paths (`su` for true backlight-off).
- **LED**: none characterised.
- **Radios**: Wi-Fi/BT only.

## Performance expectations

The NSPanel Pro is **CPU- and RAM-constrained** for rich dashboards:

- Idle it sits at 408 MHz with ≈500 MB RAM in use; a heavy Lovelace dashboard pushes both hard.
- **2 GB RAM is the binding constraint** — the dashboard WebView, the HA Companion app, and Android
  itself compete for it; large dashboards (many cards, big images, long history graphs, heavy custom
  cards) cause WebView reloads and jank.
- The A35 cores make page transitions and animations visibly slower than on A55/A72 panels.

Mitigations (all covered by ha-paneld + [docs/performance.md](../performance.md)): keep dashboards
lean, prefer the split-instance (HADS) approach to cut WebSocket event volume, and use ha-paneld's
instrumentation (CPU clock/throttling, responsiveness, top-5 processes, 1-click WebView DevTools
relay) to find what's actually costing frames on *this* hardware.
