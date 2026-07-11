# Tuya TPA10 (Rockchip rk3566)

A roomy **10" 1920×1200** rk3566 panel with a single front RGB LED, a monochrome button backlight, a rich sensor stack (ToF proximity, temperature + humidity, ambient light) and five physical buttons — no Zigbee, NFC or IR. Reverse-engineered on a live unit (Android 11, rooted, `su` present).

> [!TIP]
> Most-needed facts: adb is **password-protected** — use the USB diagnostics-app backdoor; LED and the root-only sensors need the **`hapaneld-helper` root helper daemon**; the front LED's `custom_animation` write can **reboot the panel** (see caution below). Update the **WebView first** — see [WebView — update this first](#webview--update-this-first).

| | |
|---|---|
| SoC | Rockchip **rk3566** |
| Display | **1920×1200** (16:10 landscape), ~10", 240 dpi (override 200; well-matched to ~226 physical ppi), 56 Hz → ≈**1280×800 dp** canvas |
| Android | 11 (API 30) |
| ABI | armeabi-v7a (32-bit userspace) |
| Radios | Wi-Fi, Bluetooth + BLE, plus a vendor `com.smartos.xinch.platform.ethernet` feature (wired/PoE). **No zigbee, no NFC, no IR, no cellular.** |
| Root | `su` available; the LED/sysfs sensors are `system:system`, so a **root helper daemon is required** (see below). |

> [!TIP]
> Changing firmware on a button-less panel? Read [Firmware backup & restore](../firmware-backup-restore.md) first — the TPA10 (rk3566, Android 11, 7.28 GB eMMC `mmcblk2`) uses `adb reboot loader` + `rkdeveloptool`, with the recessed [pin-hole button](#buttons) as the maskrom/recovery route.

## Gaining adb + root access

The TPA10 ships with adb **password-protected** and network adb off. The reliable route to first access is the USB diagnostics-app backdoor (no password maths). `su` is already present, so once adb is in you have root. Distilled from [seaky/nspanel_pro_tools_apk#123](https://github.com/seaky/nspanel_pro_tools_apk/issues/123).

**1. Enable Developer options** — Settings → *About* → tap the build/version number 7×.

**2. First access — USB diagnostics-app backdoor (recommended).** Developer options exposes a Tuya engineering **diagnostics app** (Chinese-only UI). While that app is open, adb over the **USB** port is allowed *without* the password, and the session is already rooted. Connect USB and:

```bash
adb devices          # the panel appears
adb shell su 0 id    # uid=0 → root confirmed
```

The TPA10's adb-root is more dependable over the USB port than over the network.

**3. Make adb persist (root, password-free — the reliable route).** With the diagnostics app foreground you have a rooted adb session (`su` is present; `adb root` also works — it's a `userdebug` build). Use it to persist adb so you never need the test app or a password again. This survives the diagnostics app closing **and** a full reboot — verified on a live unit. Push + run as root:

```bash
# persist-adb.sh — run via the diagnostics-app backdoor:
#   adb push persist-adb.sh /data/local/tmp/ && adb shell su 0 sh /data/local/tmp/persist-adb.sh
settings put global adb_enabled 1                   # USB debugging, persisted in /data
settings put global development_settings_enabled 1  # keep Developer options visible
setprop persist.adb.tcp.port 5555                   # network adb on :5555 — persist.* survives reboot

# Pre-authorise each controlling machine so NO on-screen "Allow USB debugging" is needed after reboot.
# Append the contents of every workstation's ~/.android/adbkey.pub (one key per line):
mkdir -p /data/misc/adb
cat >> /data/misc/adb/adb_keys <<'KEYS'
PASTE-EACH-adbkey.pub-LINE-HERE
KEYS
chmod 640 /data/misc/adb/adb_keys
chown system:shell /data/misc/adb/adb_keys 2>/dev/null
restorecon /data/misc/adb/adb_keys 2>/dev/null

setprop ctl.restart adbd                            # apply now (and it auto-starts every boot)
echo "adb persisted: adb_enabled=$(settings get global adb_enabled) tcp=$(getprop persist.adb.tcp.port)"
```

The panel must be on Wi-Fi for the network route; thereafter `adb connect <panel-ip>:5555` works from any pre-authorised machine, across reboots, with the vendor apps closed. A `/data` wipe / factory reset clears `adb_keys` + `adb_enabled`, so re-run this after one.

> [!WARNING]
> **The Developer-options "Enable ADB" *password* is not a usable path — do not try to compute it.** Contrary to the [#123](https://github.com/seaky/nspanel_pro_tools_apk/issues/123) community recipe, it does not reproduce. Decompiling `checkDevPassword` in `com.smartos.xinch.setting` confirms the *shape*: `base64(takeLast(ro.tuya.uuid,3) + takeLast(deviceId,3))` then `takeLast(6)`, case-insensitive (or `takeLast(ro.tuya.uuid,6)` when `deviceId` is empty). But the `deviceId` field it uses could not be matched to any readable identifier — `ro.serialno`, `android_id` and `ro.tuya.key` were all rejected on a live unit. The app's logger is **not** logcat, so the expected value can't be read on-device either. The #123 worked example also has a typo (`11a`+`xia` written as `11xia`; it must be `11axia`). Use the root method above — it makes the password irrelevant.

> [!CAUTION]
> Disable the vendor `com.smartos.xinch.*` packages only as the **very last step**, after confirming adb is solid *and* you have a replacement for the hardware buttons. Disabling the hardware/setting apps before adb is reliable can lock you out. ha-paneld's remote nav actions (Back/Recents) and the button-backlight/LED entities replace the vendor app's functions.

> [!NOTE]
> The vendor's on-device apps were *not* a useful RE source: `com.tuya.devicetest` is odex'd (no dex in the APK), and `com.smartos.xinch.hardware` bundles the Tuya **AVS (Alexa) SDK** (`libLibSampleApp.so`, 17 MB) plus a key-reader (`libjnimain.so`). The authoritative source is the device's own self-documenting sysfs nodes.

## WebView — update this first

The stock WebView is **Chrome 83** — far too old for a current HA frontend, so the Companion app shows a blank/broken dashboard until you replace it. The verified-working build is **Cromite SystemWebView 147.0.7727.56** (armeabi-v7a). It is the *last* 32-bit Cromite; newer builds are arm64-only, so keep this exact version.

It is packaged as `com.android.webview`, so it must **replace** the stock system WebView — and the two "obvious" routes don't work on this Android-11 panel:

- A plain `adb install -r` is rejected: `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match` (Cromite is `CN=CromiteOrg`, not the Tuya platform key).
- The ROM's provider allowlist accepts **only** `com.android.webview`, so the `com.google.android.webview` ("…Google") Cromite variant installs but can't be selected.

**Working method (root; verified) — replace the file + clear the signature lock:**

```bash
adb root && adb disable-verity && adb reboot     # one-time: enable the rw overlay
adb root && adb remount                           # re-run each session (/system is RO again after a reboot)
# 1. replace the stock system WebView APK with the Cromite SystemWebView:
adb push cromite-147-SystemWebView-armv7.apk /data/local/tmp/wv.apk
adb shell 'cp /product/app/webview/webview.apk /data/local/tmp/webview-stock.bak;
           cp /data/local/tmp/wv.apk /product/app/webview/webview.apk;
           chmod 644 /product/app/webview/webview.apk; restorecon /product/app/webview/webview.apk'
# 2. clear the stock's leftover packages.xml entry so PM re-registers the Cromite APK FRESH. This is the
#    key step: it sidesteps the signature lock AND stops the lower-versioned stock being auto-selected
#    (remove only the single <package name="com.android.webview" …> element with a real XML parser):
adb pull /data/system/packages.xml ./packages.xml      # edit out that element, then:
adb push packages.xml /data/system/packages.xml
adb shell 'chown system:system /data/system/packages.xml; chmod 660 /data/system/packages.xml;
           restorecon /data/system/packages.xml'
adb reboot                                        # PM registers Cromite fresh → engine is Cromite 147
```

> [!CAUTION]
> **The reported WebView version is wrong with this method — don't trust it.** `Settings → WebView` and `adb shell dumpsys webviewupdate` both show **`83.0.4103.120`**, because Cromite SystemWebView deliberately **stamps the OEM stock `versionName`/`versionCode`** so it clears the webview min-version gate and gets selected. The *actual* rendering engine is Cromite 147. Verify the real version by:
> - **User-Agent** — the engine HA Companion actually uses: open any "what's my user-agent" page on the panel; the UA contains `Chrome/147.0.7727.56`.
> - **From the APK** — `unzip -p webview.apk lib/armeabi-v7a/libwebviewchromium.so | strings | grep -m1 -oE '[0-9]+\.0\.7[0-9]{3}\.[0-9]+'` → `147.0.7727.56`. (The on-device libs aren't extracted — `extractNativeLibs=false` — so read it from the APK, not from disk.)

A clean adb sideload does **not** work on this signature-locked panel.

## LED

### RGB LED — `avsux` driver (root helper daemon)

The front RGB LED is a **single** LED (`avsux_info` → `led type:[single] nums:[1]`) on the `leds_pwm_avs` platform driver (device `avsux`), exposed at `/sys/class/leds/avs-pwm-led/`.

> [!CAUTION]
> Writing `custom_animation` to `avsux_select` has been observed to **reboot the panel**. Use `avsux_animation` for colour; treat `avsux_select`/`custom_animation` as read-only unless testing.

There is **no app-accessible `/dev` node** for the LED (contrast the [WF1589T](wf1589t.md)'s `/dev/ledjni`), and the sysfs attributes are `system:system` — an `untrusted_app` cannot write them. ha-paneld therefore ships a small **root helper daemon** (`/system/bin/hapaneld-helper`, root, unix socket) that the app talks to; `SocketLedController` is the client. See [`helper/README.md`](../../helper/README.md).

<details>
<summary>`avs-pwm-led` sysfs attributes</summary>

| Attribute | Perm | Use |
|---|---|---|
| `brightness` | `system:system` rw | overall level 0–255 |
| `avsux_animation` | `system:system` rw | safe colour/animation write |
| `avsux_select` | `system:system` rw | `custom_animation[][0][0]:<dur_ms>:<RRGGBB>[,…≤12 slots]` |
| `avsux_firmware` | r | lists named animations (`bootanime`, `idle`) |
| `avsux_info` | r | metadata (LED count/type) |

</details>

### Button backlight

`/sys/class/leds/button-backlight/brightness` — **monochrome** PWM, 0–255 (standard `leds_pwm` driver, device `pwmleds`). `system:system` 0664, so driven through the same `hapaneld-helper` daemon.

## Sensors

Proximity is app-direct via `SensorManager`; temperature, humidity and ambient light are root-only (input subsystem / i2c) and need the helper daemon.

> [!TIP]
> The CHT8305 makes this panel a viable **room temperature/humidity sensor** for Home Assistant. The helper daemon reads it with the `CHT8305` verb (an `EVIOCGABS` point-read of the driver's `ABS_THROTTLE` input axis, matching the `temperature`/`humidity` input devices by name). ha-paneld then exposes two opt-in **Room temperature** / **Room humidity** sensors over MQTT (Configure → Diagnostics; off by default). An Advanced **Room temperature offset** setting (or the profile's `roomTempOffsetC`) corrects for panel self-heating.

The TPA10 ToF means proximity is genuinely distance-based, but the Android HAL quantises it; ha-paneld calibrates the reported value (near/far capture) rather than trusting a fixed cutoff.

<details>
<summary>Sensor chips + access paths</summary>

| Sensor | Chip | Access |
|---|---|---|
| Proximity (ToF) | Vishay **VI5300** (i2c-3 `0x6c`, `proximity_vi5300`, 30 ms poll) | Android `SensorManager` `TYPE_PROXIMITY` (no root). Raw mm distance on the driver's i2c node (`…/i2c-3/3-006c`) needs root. |
| Temperature + humidity | **CHT8305** (`temperature_cht8305` @3-0040, `humidity_cht8305` @3-0040-1) | **Not** in `SensorManager`; reports via the **input subsystem** on i2c — root only. |
| Ambient light | **CG5256** (`light_cg5256`) | Not in `SensorManager` (root). |

</details>

## Buttons

The TPA10 has **three classes** of physical button, confirmed on-device with `getevent`:

- **The four side buttons** — `adc-keys`, standard KeyEvents mapped to `F1`–`F4`, captured by ha-paneld's accessibility key-filter (no special path).
- **The 5th (orange) button** — an `EV_SW` *switch*, not a key; instrumented through the root helper daemon's evdev reader and emitted as an HA event.
- **The pin-hole button** (recessed, beside the USB-C port) — recovery / reflash only, **not** HA-instrumentable.

<details>
<summary>Per-button detail (scancodes, evdev, recovery role)</summary>

**1. The four side buttons — `adc-keys`, standard KeyEvents.** On the rk3566 **SARADC** (`fe720000.saradc`), device `adc-keys1` (`/dev/input/event7`), scancodes `59`–`62`. Stock `Generic.kl` maps these to **`F1`–`F4`** → Android `KEYCODE_F1`–`F4`, which ha-paneld captures via its accessibility key-filter (no special path). They can be remapped by editing `/system/usr/keylayout/Generic.kl` (e.g. to `BRIGHTNESS_*` / `VOLUME_*`) on an su-capable unit.

**2. The 5th (orange) button — a *switch*, not a key.** On `gpio-keys` (`/dev/input/event8`) it reports **`EV_SW` `SW_MUTE_DEVICE`** (switch code `14`), a *latching* event — **not** an `EV_KEY`. This is why no keylayout entry exists for it and Android/a11y never surface it (so the stock firmware leaves it dead). ha-paneld instruments it through the root helper daemon's evdev reader (`WATCH /dev/input/event8`, `sw=true`) and emits an HA event (`KEYCODE_MUTE`) on each toggle — validated end-to-end. This is **stock** behaviour (undocumented elsewhere as of this writing).

**3. The pin-hole button (recessed, beside the USB-C port) — recovery / reflash, not input.** Not wired to the Linux input subsystem (absent from `getevent`, `gpio-keys`, and `dmesg`), so it is **not HA-instrumentable**. By placement and platform (Rockchip rk3566) it serves the usual recessed-pin roles: a **factory-reset / default** trigger (paperclip or SIM tool, hold ~5–10 s) and the Rockchip **MASKROM/loader** pin — grounding it forces the SoC into USB flashing mode for low-level recovery with `rkdeveloptool` (see [Firmware backup & restore](../firmware-backup-restore.md)). Use it for un-bricking / reflashing, not for automation.

</details>

## Other silicon

Camera GalaxyCore **GC05A2 / GC5035**; audio codec **ES7202**; Goodix touch; `rk808`/`rk860` PMIC.

## Access model summary

- **LED + button backlight**: root only (`system:system` sysfs) → via `hapaneld-helper`.
- **Proximity**: app-direct (`SensorManager`).
- **Temp / humidity / light**: root only (input subsystem / i2c) → would need the daemon.
- **Buttons**: 4 side buttons app-direct (KeyEvents via a11y); 5th orange button is an `EV_SW` switch → via `hapaneld-helper` evdev watch; pin-hole button is recovery/maskrom (not input).

---

See the [panel hardware index](README.md) for the cross-panel comparison and method, and the [NSPanel Pro](nspanel-pro.md) / [WF1589T](wf1589t.md) / [S9E](s9e.md) references for the other panels.
