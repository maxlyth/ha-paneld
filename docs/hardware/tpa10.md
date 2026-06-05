# Tuya TPA10 (Rockchip rk3566)

Hardware reference for the **TPA10** wall panel, reverse-engineered on a live unit (Android 11,
rooted, `su` present) on 2026-06-05. These panels are essentially undocumented publicly; this records
what is on the board and how to drive it.

| | |
|---|---|
| SoC | Rockchip **rk3566** |
| Display | **1920×1200** (16:10 landscape), ~10", 240 dpi (override 200; well-matched to ~226 physical ppi), 56 Hz → ≈**1280×800 dp** canvas |
| Android | 11 (API 30) |
| ABI | armeabi-v7a (32-bit userspace) |
| Radios | Wi-Fi, Bluetooth + BLE, plus a vendor `com.smartos.xinch.platform.ethernet` feature (wired/PoE). **No zigbee, no NFC, no IR, no cellular.** |
| Root | `su` available; the LED/sysfs sensors are `system:system`, so a **root helper daemon is required** (see below). |

## Gaining adb + root access

The TPA10 ships with adb **password-protected** and network adb off. The reliable route to first
access is the USB diagnostics-app backdoor (no password maths). `su` is already present, so once adb
is in you have root. Distilled from [seaky/nspanel_pro_tools_apk#123](https://github.com/seaky/nspanel_pro_tools_apk/issues/123).

**1. Enable Developer options** — Settings → *About* → tap the build/version number 7×.

**2. First access — USB diagnostics-app backdoor (recommended).** Developer options exposes a Tuya
engineering **diagnostics app** (Chinese-only UI). While that app is open, adb over the **USB** port
is allowed *without* the password, and it is already rooted. Connect USB and:

```bash
adb devices          # the panel appears
adb shell su 0 id    # uid=0 → root confirmed
```

The TPA10's adb-root is more dependable over the USB port than over the network.

**3. Persist network adb** so you never need USB or the password again:

```bash
adb root
adb shell su 0 setprop persist.adb.tcp.port 5555
adb shell su 0 settings put global adb_enabled 1
adb tcpip 5555
# thereafter from any host: adb connect <panel-ip>:5555
```

**Alternative — the adb password (network, no USB).** The "Enable ADB" toggle in Developer options
asks for a password derived from `ro.tuya.uuid` and the device ID on the *About* page, by the
`checkDevPassword` logic inside `com.smartos.xinch.setting`. The community recipe in #123 (last 3
characters of each, base64, last 6 characters — e.g. uuid…`11a` + device…`xia` → `FheGlh`) does **not**
reproduce cleanly on every unit, so if it is rejected, recover the exact value by decompiling that app
or by grepping `logcat` for `checkDevPassword` while entering a guess. The USB backdoor above avoids
this step entirely.

> [!CAUTION]
> Disable the vendor `com.smartos.xinch.*` packages only as the **very last step**, after confirming
> adb is solid *and* you have a replacement for the hardware buttons. Disabling the hardware/setting
> apps before adb is reliable can lock you out. ha-paneld's remote nav actions (Back/Recents) and the
> button-backlight/LED entities replace the vendor app's functions.

## WebView — update this first

The stock WebView on the TPA10 is far too old for a current Home Assistant frontend, so the HA
Companion app shows a blank/broken dashboard until you update it. The verified-working build is
**Cromite SystemWebView 147.0.7727.56** (`com.android.webview`, armeabi-v7a — cert `CN=CromiteOrg`),
installed by a clean adb sideload — no root, no F-Droid. Download + method:
[Updating the system WebView](README.md#updating-the-system-webview).

The vendor's on-device apps were *not* a useful RE source: `com.tuya.devicetest` is odex'd (no dex in
the APK), and `com.smartos.xinch.hardware` bundles the Tuya **AVS (Alexa) SDK** (`libLibSampleApp.so`,
17 MB) plus a key-reader (`libjnimain.so`). The authoritative source is the device's own
self-documenting sysfs nodes.

## RGB LED — `avsux` driver (root helper daemon)

The front RGB LED is a **single** LED (`avsux_info` → `led type:[single] nums:[1]`) on the
`leds_pwm_avs` platform driver (device `avsux`), exposed at `/sys/class/leds/avs-pwm-led/`:

| Attribute | Perm | Use |
|---|---|---|
| `brightness` | `system:system` rw | overall level 0–255 |
| `avsux_animation` | `system:system` rw | safe colour/animation write |
| `avsux_select` | `system:system` rw | `custom_animation[][0][0]:<dur_ms>:<RRGGBB>[,…≤12 slots]` |
| `avsux_firmware` | r | lists named animations (`bootanime`, `idle`) |
| `avsux_info` | r | metadata (LED count/type) |

> [!CAUTION]
> Writing `custom_animation` to `avsux_select` has been observed to **reboot the panel**. Use
> `avsux_animation` for colour; treat `avsux_select`/`custom_animation` as read-only unless testing.

There is **no app-accessible `/dev` node** for the LED (contrast the [WF1589T](wf1589t.md)'s
`/dev/ledjni`), and the sysfs attributes are `system:system` — an `untrusted_app` cannot write them.
ha-paneld therefore ships a small **root helper daemon** (`/system/bin/hapaneld-ledd`, root, unix
socket) that the app talks to; `SocketLedController` is the client. See
[`helper/README.md`](../../helper/README.md).

## Button backlight

`/sys/class/leds/button-backlight/brightness` — **monochrome** PWM, 0–255 (standard `leds_pwm`
driver, device `pwmleds`). `system:system` 0664, so driven through the same `hapaneld-ledd` daemon.

## Buttons

Hardware buttons are `adc-keys` on the rk3566 **SARADC** (`fe720000.saradc`), delivering standard
Android KeyEvents (`KEY_MICMUTE`, `KEY_F`). ha-paneld captures these via its accessibility key-filter
— no special path.

## Sensors

| Sensor | Chip | Access |
|---|---|---|
| Proximity (ToF) | Vishay **VI5300** (i2c-3 `0x6c`, `proximity_vi5300`, 30 ms poll) | Android `SensorManager` `TYPE_PROXIMITY` (no root). Raw mm distance on the driver's i2c node (`…/i2c-3/3-006c`) needs root. |
| Temperature + humidity | **CHT8305** (`temperature_cht8305` @3-0040, `humidity_cht8305` @3-0040-1) | **Not** in `SensorManager`; reports via the **input subsystem** on i2c — root only. |
| Ambient light | **CG5256** (`light_cg5256`) | Not in `SensorManager` (root). |

> [!TIP]
> The CHT8305 makes this panel a viable **room temperature/humidity sensor** for Home Assistant —
> read it via the root helper daemon and publish over MQTT. Not yet implemented.

The TPA10 ToF means proximity is genuinely distance-based, but the Android HAL quantises it; ha-paneld
calibrates the reported value (near/far capture) rather than trusting a fixed cutoff.

## Other

Camera GalaxyCore **GC05A2 / GC5035**; audio codec **ES7202**; Goodix touch; `rk808`/`rk860` PMIC.

## Access model summary

- **LED + button backlight**: root only (`system:system` sysfs) → via `hapaneld-ledd`.
- **Proximity**: app-direct (`SensorManager`).
- **Temp / humidity / light**: root only (input subsystem / i2c) → would need the daemon.
- **Buttons**: app-direct (KeyEvents via a11y).
