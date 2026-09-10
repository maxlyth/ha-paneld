> [!IMPORTANT]
> 本文档由机器生成并经过自动交叉核验，但尚未由中文使用者进行系统审阅。英文文档为权威版本。[阅读英文原文](../../hardware/tpa10.md)，或[创建翻译更正议题](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml)。

# Tuya TPA10（Rockchip rk3566）

这是一款大尺寸的 **10 英寸 1920×1200** rk3566 面板，配有一颗前置 RGB LED、单色按钮背光、丰富的传感器阵列（ToF 接近传感器、温湿度传感器和环境光传感器）以及五个实体按钮，但不具备 Zigbee、NFC 或红外功能。本文基于实体设备进行逆向分析（Android 11、已取得 root 权限且 `su` 可用）。

> [!TIP]
> Most-needed facts: adb is **password-protected** — use the USB diagnostics-app backdoor; LED and the root-only sensors need the **`hapaneld-helper` root helper daemon**; the front LED's `custom_animation` write can **reboot the panel** (see caution below). Update the **WebView first** — see [WebView — update this first](#webview--请先更新).

| | |
|---|---|
| SoC | Rockchip **rk3566** |
| 显示 | **1920×1200**（16:10 横向），约 10.1 英寸 / **约 226 物理 ppi**，56 Hz。Android 出厂/基准逻辑密度为 **240 dpi**（通常会为调整仪表板尺寸而覆盖；ha-paneld 建议设为 212） |
| Android | 11 (API 30) |
| ABI | armeabi-v7a （ 32位用户空间） |
| 无线通信 | Wi-Fi、蓝牙和 BLE，以及供应商的 `com.smartos.xinch.platform.ethernet` 功能（有线网络/PoE）。**无 Zigbee、NFC、红外或蜂窝网络。** |
| Root 权限 | `su` available; the LED/sysfs sensors are `system:system`, so a **root helper daemon is required** (see below). |

> [!TIP]
> Changing firmware on a button-less panel? Read [Firmware backup & restore](../../firmware-backup-restore.md) first. The TPA10 (rk3566, Android 11, 7.28 GB eMMC `mmcblk2`) has a verified software-entered Loader route through `adb reboot loader` and `rkdeveloptool`. The recessed [pin-hole button](#按钮) is not a Linux input, but its factory-reset or Maskrom behavior has not been safely confirmed; do not rely on it as a recovery route.

## 获取 adb 和 root 权限

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
> 供应商的设备端应用*并非*有用的逆向工程资料来源：`com.tuya.devicetest` 已经 odex 化（APK 中没有 dex），而 `com.smartos.xinch.hardware` 捆绑了 Tuya **AVS（Alexa）SDK**（`libLibSampleApp.so`，17 MB）以及按键读取器（`libjnimain.so`）。权威依据是设备自身具备自描述能力的 sysfs 节点。

## WebView — 请先更新

The stock WebView is **Chrome 83** — far too old for a current HA frontend, so the dashboard shows blank or broken until you replace it. The recommended build is **LineageOS System WebView 150** (`armeabi-v7a`) — a current, maintained, vanilla-Chromium engine. Prefer it over Cromite: Cromite patches Chromium's autoplay content-setting to *block*, which stops Home Assistant camera-card (WebRTC) streams from starting without a tap; LineageOS leaves autoplay allowed, so camera streams start on their own.

TPA10 在 WebView 中的视频解码余量有限。即使单路视频流工作正常，同时显示多个 720p WebRTC 卡片也可能丢帧，或导致其中一路一直等待首帧。如果出现这种情况，请使用较低分辨率的子码流、减少同时自动播放的卡片数量，或使用点击后再切换为实时视频的快照。

Download it from the ha-paneld mirror (stable URL):

```
https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm.apk
```

### 为什么普通安装不起作用

The WebView is packaged as `com.android.webview`, so it must **replace** the system provider, and the two obvious routes both fail on this Android-11 panel:

- `adb install -r` is rejected — `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`. Android only lets you update `com.android.webview` with an APK signed by the **same key** as what's already installed, and each WebView vendor uses a different key. This is also why **ha-paneld's built-in "Update WebView" / auto-update can't do this first swap** — it installs over `pm install`, which the panel blocks. The swap below is a **one-time** manual step; once LineageOS is in place, ha-paneld *can* auto-apply future LineageOS updates (same signer).
- The ROM's allowlist accepts only `com.android.webview` (not the `com.google.android.webview` variant), so a "…Google" build installs but is never selected.

### 可行方法（root）— 替换文件并解除签名锁定

The panel is signature-locked, but it is rootable: the app can't `su`, but a shell can (`adb shell su root <cmd>`). The trick is to drop the new APK into the *system* WebView slot and delete its entry from the package database so PackageManager re-registers it **fresh** on reboot (which reads the new APK's own signature — no conflict).

```bash
IP=<panel-ip>:5555                                    # e.g. 192.168.1.50:5555
adb connect $IP
adb -s $IP shell su root id                           # confirm it prints uid=0(root)

# 1. push the new WebView, and back up the current WebView + package database first:
adb -s $IP push lineageos-webview-150.0.7871.63-arm.apk /data/local/tmp/wv-new.apk
adb -s $IP shell su root sh -c 'cp /product/app/webview/webview.apk /data/local/tmp/webview.bak;
                                cp /data/system/packages.xml /data/local/tmp/packages.xml.bak'

# 2. replace the system WebView APK (remount /product read-write first — verity must already be off;
#    a never-modified panel needs a one-time `adb root && adb disable-verity && adb reboot` beforehand):
adb -s $IP shell su root sh -c 'mount -o rw,remount /product;
    cp /data/local/tmp/wv-new.apk /product/app/webview/webview.apk;
    chmod 644 /product/app/webview/webview.apk; chown root:root /product/app/webview/webview.apk;
    restorecon /product/app/webview/webview.apk'

# 3. remove the single <package name="com.android.webview" …>…</package> element from packages.xml.
#    Do NOT hand-edit it — pull it, let a parser remove exactly that element, then push it back:
adb -s $IP shell su root sh -c 'cp /data/system/packages.xml /data/local/tmp/pkgs.xml; chmod 644 /data/local/tmp/pkgs.xml'
adb -s $IP pull /data/local/tmp/pkgs.xml packages.xml
python3 - <<'PY'
import xml.etree.ElementTree as ET
d = open('packages.xml', encoding='utf-8').read()
m = '<package name="com.android.webview"'
assert d.count(m) == 1, 'expected exactly one com.android.webview package'
s  = d.find(m); ls = d.rfind('\n', 0, s) + 1                      # start of that line
e  = d.find('</package>', s) + len('</package>'); le = d.find('\n', e) + 1  # end of its closing line
new = d[:ls] + d[le:]
ET.fromstring(new)                                                # abort if the result isn't valid XML
open('packages.xml', 'w', encoding='utf-8').write(new)
print('removed com.android.webview; XML still valid')
PY
adb -s $IP push packages.xml /data/local/tmp/pkgs.new
adb -s $IP shell su root sh -c 'cp /data/local/tmp/pkgs.new /data/system/packages.xml;
    chown system:system /data/system/packages.xml; chmod 660 /data/system/packages.xml;
    restorecon /data/system/packages.xml'

# 4. reboot — PackageManager registers the new WebView fresh:
adb -s $IP reboot
```

**If anything goes wrong**, revert with the backups from step 1: copy `/data/local/tmp/webview.bak` back over `/product/app/webview/webview.apk` and `/data/local/tmp/packages.xml.bak` back over `/data/system/packages.xml` (same `chown`/`chmod`/`restorecon`), then reboot.

> [!CAUTION]
> **The reported WebView version is wrong with this method — don't trust it.** `Settings → WebView` and `adb shell dumpsys webviewupdate` still show **`83.0.4103.120`**, because a sideloaded SystemWebView **stamps the OEM stock `versionName`/`versionCode`** to clear the panel's min-version gate and get selected. The *actual* engine is 150. Verify it by:
> - **User-Agent** — open any "what's my user agent" page on the panel; the UA contains `Chrome/150.0.7871.63`.
> - **ha-paneld** — the `:8888` info page / `/api/v1/diag` shows `engine Chromium 150.0.7871.63` (it reads the UA, not the stamped package version).

(This supersedes the earlier "clean adb sideload" note, which does **not** work on this signature-locked panel. Cromite 147 remains available in the mirror as a fallback — same procedure, different APK — if you ever need it.)

## LED

### RGB LED — `avsux` 驱动程序（ root 辅助守护进程）

前置 RGB LED 是**单颗** RGB LED（`avsux_info` → `led type:[single] nums:[1]`），位于 `leds_pwm_avs` 平台驱动程序（设备 `avsux`）上，通过 `/sys/class/leds/avs-pwm-led/` 暴露。

> [!CAUTION]
> Writing `custom_animation` to `avsux_select` has been observed to **reboot the panel**. Use `avsux_animation` for colour; treat `avsux_select`/`custom_animation` as read-only unless testing.

There is **no app-accessible `/dev` node** for the LED (contrast the [WF1589T](wf1589t.md)'s `/dev/ledjni`), and the sysfs attributes are `system:system` — an `untrusted_app` cannot write them. ha-paneld therefore ships a small **root helper daemon** (`/system/bin/hapaneld-helper`, root, unix socket) that the app talks to; `SocketLedController` is the client. See [`helper/README.md`](../../../helper/README.md).

<details>
<summary>`avs-pwm-led` sysfs属性</summary>

| 属性 | 权限 | 用途 |
|---|---|---|
| `brightness` | `system:system` rw | overall level 0–255 |
| `avsux_animation` | `system:system` rw | safe colour/animation write |
| `avsux_select` | `system:system` rw | `custom_animation[][0][0]:<dur_ms>:<RRGGBB>[,…≤12 slots]` |
| `avsux_firmware` | r | 列出命名动画(`bootanime`, `idle`) |
| `avsux_info` | r | 元数据（LED 数量/类型） |

</details>

### 按钮背光

`/sys/class/leds/button-backlight/brightness` — **monochrome** PWM, 0–255 (standard `leds_pwm` driver, device `pwmleds`). `system:system` 0664, so driven through the same `hapaneld-helper` daemon.

## 传感器

Proximity is app-direct via `SensorManager`; temperature, humidity and ambient light are root-only (input subsystem / i2c) and need the helper daemon.

> [!TIP]
> CHT8305 使这块面板可用作 Home Assistant 的**室温/湿度传感器**。辅助守护进程使用 `CHT8305` 命令读取它（即通过 `EVIOCGABS` 对驱动程序的 `ABS_THROTTLE` 输入轴进行单次读取，并按名称匹配 `temperature`/`humidity` 输入设备）。随后，ha-paneld 通过 MQTT 暴露两个可选的**室温**/**室内湿度**传感器（配置 → 诊断；默认关闭）。高级设置中的**室温偏移**（或配置文件中的 `sensors.room_temp_offset_c`）可校正面板自身发热造成的偏差。

TPA10 的 ToF 传感器意味着接近检测确实基于距离，但 Android HAL 会对读数进行量化。ha-paneld 会学习当前传感器的端点和方向，然后报告所有受支持面板通用的归一化接近度刻度，而不是依赖固定的设备特定阈值。

<details>
<summary>传感器芯片+访问路径</summary>

| 传感器 | 芯片 | 访问方式 |
|---|---|---|
| 接近度(ToF) | Vishay **VI5300** (i2c-3 `0x6c`, `proximity_vi5300`, 30 ms poll) | Android `SensorManager` `TYPE_PROXIMITY` (no root). Raw mm distance on the driver's i2c node (`…/i2c-3/3-006c`) needs root. |
| 温度+湿度 | **CHT8305**（`temperature_cht8305` @3-0040，`humidity_cht8305` @3-0040-1） | **Not** in `SensorManager`; reports via the **input subsystem** on i2c — root only. |
| 环境光 | **CG5256** (`light_cg5256`) | Not in `SensorManager` (root). |

</details>

## 按钮

TPA10 有 **三类**实体按钮，已在设备上通过 `getevent` 确认：

- **四个侧边按钮** — `adc-keys`，标准KeyEvents映射到 `F1`–`F4`，由ha-paneld的辅助功能键过滤器捕获（无特殊路径）。
- **第 5 个（橙色）按钮**——一个 `EV_SW` *开关*，而不是按键；通过 root 辅助守护进程的 evdev 读取器进行检测，并作为 HA 事件发出。
- **The pin-hole button** (recessed, beside the USB-C port) — recovery / reflash only, **not** HA-instrumentable.

<details>
<summary>各按钮详情（扫描码、evdev、恢复用途）</summary>

**1. The four side buttons — `adc-keys`, standard KeyEvents.** On the rk3566 **SARADC** (`fe720000.saradc`), device `adc-keys1` (`/dev/input/event7`), scancodes `59`–`62`. Stock `Generic.kl` maps these to **`F1`–`F4`** → Android `KEYCODE_F1`–`F4`, which ha-paneld captures via its accessibility key-filter (no special path). They can be remapped by editing `/system/usr/keylayout/Generic.kl` (e.g. to `BRIGHTNESS_*` / `VOLUME_*`) on an su-capable unit.

**2. 第 5 个（橙色）按钮——一个 *开关*，而不是按键。** 在 `gpio-keys` （`/dev/input/event8`）上，它报告 **`EV_SW` `SW_MUTE_DEVICE`** （开关代码 `14`），这是一个 *锁存式* 事件—— **并非** `EV_KEY`。因此它没有按键布局条目，Android/a11y 也永远不会呈现它（所以原厂固件未使用它）。ha-paneld 通过 root 辅助守护进程的 evdev 读取器（`WATCH /dev/input/event8`、 `sw=true`）对其进行检测，并在每次切换时发出 HA 事件（`KEYCODE_MUTE`）——已经过端到端验证。这是 **原厂** 行为（截至本文撰写时，其他资料均未记载）。

**3. The pin-hole button (recessed, beside the USB-C port) — not an Android input.** It is absent from `getevent`, `gpio-keys` and `dmesg`, so it is **not HA-instrumentable**. Its electrical role has not been safely confirmed: placement and the rk3566 platform suggest a reset or boot-mode function, but there is no verified hold duration, factory-reset behavior or Maskrom entry procedure. Do not press or hold it on the assumption that it provides a recoverable reflash path; use the verified software-entered Loader route while Android still boots and follow the evidence boundary in [Firmware backup & restore](../../firmware-backup-restore.md).

</details>

## 相机

GalaxyCore **GC05A2 / GC5035** 是一颗 2592×1944 传感器。Android 报告一个处于 `LIMITED` 硬件级别、仅支持 `BACKWARD_COMPATIBLE`、`Facing: Back` 且无闪光灯和自动对焦的摄像头，由 `legacy/0` provider（`device@3.3`）提供。

> [!NOTE]
> The firmware's own feature flags are wrong about it: the panel declares `android.hardware.camera.front` while the HAL reports `Facing: Back`. Gate on the device profile and on what `CameraManager` actually enumerates, never on `hasSystemFeature`.

1280x720 提供三种格式：`IMPLEMENTATION_DEFINED`、`YUV_420_888` 和 `BLOB`，因此预览和静态捕获格式均可用。HAL 标称以 30 fps 进行 720p `BLOB` 捕获时会停顿一帧，但 ha-paneld 捕获 `YUV_420_888` 并执行软件 JPEG 压缩，因此该数字并不衡量应用的快照开销。`android.control.aeAvailableTargetFpsRanges` 仅提供 `[15 30]` 和 `[30 30]`：不存在锁定为 15 fps 的传感器模式，因此 15 fps 视频流来自对编码器进行帧率控制，而不是请求传感器以该帧率工作。

唯一的硬件 H.264 编码器是 `OMX.rk.video_encoder.avc`，由 OMX IL HAL 提供（此面板有一个 `media.codec` 进程，没有 Codec2 供应商服务）。它声明支持 `176x144`-`1920x1088`，按 `16x8` 对齐；报告的码率范围为 1 bps 至 10 Mbps，并支持四个并发实例。设备**没有硬件 HEVC 编码器**；唯一的 HEVC 条目是软件实现 `c2.android.hevc.encoder`，随系统提供但设置为 `enabled="false"`。

> [!IMPORTANT]
> 应将供应商在 `media_codecs_performance.xml` 中提供的数字视为样板数据，而不是实测结果：硬件和软件 AVC 编码器在 720x480 和 1280x720 下报告了完全相同的 `measured-frame-rate` 值，这不可能是真实测量值。在自己的面板上测得其他结果之前，应按 720p 能力规划。

显示摄像头卡片的面板并非处于空闲状态：在实测的设备群仪表板上，ha-paneld 同时运行了两个 `OMX.rk.video_decoder.avc` 实例，因此编码会话会与同一 Rockchip VPU 上现有的解码工作争用资源。

ha-paneld includes an **experimental camera-serving feature that is off by default**. It is enabled per panel by the **Camera** switch on the Camera card in Configure, which appears because this panel has a camera - here because the device profile declares `hardware.camera`, though a board whose profile says nothing offers the card too whenever Android enumerates a camera (see [enabling the camera on a panel whose profile does not declare one](../../profiles/unofficial/README.md#enabling-the-camera-on-a-panel-whose-profile-does-not-declare-one)), alongside **Resolution**, **Frame rate** and **Bitrate** (720p / 15 fps / 2000 kbps) and an **Exposure** bias in stops for a camera that reads the room darker or brighter than it looks. Those three are what a stream gets when its URL asks for nothing, and a stream URL can override any of them in either direction; they are defaults, not ceilings. Several viewers share one encode session, bound by the first one to connect, so a second viewer costs packetisation and network rather than a second encode. With the switch on it serves a video-only H.264 stream at `rtsp://<panel>:8554/live` and a still at `GET /api/v1/camera/snapshot.jpg`. Enabling the switch from Home Assistant asks for approval on the panel first, in every security mode. The RTSP URL is intended for Home Assistant Generic Camera, go2rtc and Frigate ingestion. Whenever the camera is open the panel draws its own red indicator that page content cannot cover, and if that indicator cannot be drawn the camera does not open. It flashes about once a second when the camera opens and backs off over a couple of minutes to a flash about once a minute; the half-second flash never gets shorter or fades away, settling at 62% opacity as it becomes rarer. A new stream, a recovery after a fault or the screen coming back on returns it to a flash every second, and no setting turns it off or changes the schedule. See [`GET /api/v1/status`](../../api.md) for the `camera` object that reports the encoder, the delivered frame rate and bitrate, and any fault.

> [!WARNING]
> Do not put a Home Assistant camera card for **this** panel on **this** panel's own dashboard. The panel would decode its own encode in a loop, on the same video engine, for no benefit.


## 其他芯片

摄像头：GalaxyCore **GC05A2 / GC5035**；音频编解码器：**ES7202**；Goodix 触控；`rk808`/`rk860` PMIC。

## 访问模式摘要

- **LED + button backlight**: root only (`system:system` sysfs) → via `hapaneld-helper`.
- **接近传感器**：应用可直接访问（`SensorManager`）。
- **Temp / humidity / light**: root only (input subsystem / i2c) → would need the daemon.
- **Buttons**: 4 side buttons app-direct (KeyEvents via a11y); 5th orange button is an `EV_SW` switch → via `hapaneld-helper` evdev watch; pin-hole button is recovery/maskrom (not input).

---

跨面板比较和方法参见 [面板硬件索引](README.md) ，其他面板参见 [NSPanel Pro](nspanel-pro.md) / [WF1589T](wf1589t.md) / [S9E](../../hardware/s9e.md) 参考。
