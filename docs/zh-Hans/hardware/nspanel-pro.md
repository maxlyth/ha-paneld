> [!IMPORTANT]
> 本文档由机器生成并经过自动交叉核验，但尚未由中文使用者进行系统审阅。英文文档为权威版本。[阅读英文原文](../../hardware/nspanel-pro.md)，或[创建翻译更正议题](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml)。

# Sonoff NSPanel Pro （ Rockchip PX30/rk3326 ）

最初的NSPanel Pro是一个小型 **480 × 480方形** PX30面板，内置 **Zigbee 3.0协调器**，没有NFC/IR ，并且是此处记录的面板的最低功耗CPU。其 **86P** 和 **120P** 变体使用不同的显示器和电路板—请参阅 [变体](#变体-86p与120p)。本页面主要在实时 **86P** （ Android 8.1 ， 已取得 root 权限 ，工具箱 `su`）上进行了反向工程，并涵盖了这些原始变体，除非另有明确说明。

> [!TIP]
> Most-needed facts: ships **`userdebug` with no adb password** (`adb root` just works); **LED is not characterised** (no controllable RGB node found); light + proximity are **app-direct**; the on-board **EFR32 Zigbee radio** is managed over a local broker, not by reflashing. Update the **WebView first** — see [WebView — update this first](#webview请先更新它).

| | |
|---|---|
| SoC | Rockchip **PX30/rk3326** |
| CPU | 4× **Cortex-A35**，最高 **1.512 GHz**（空闲时为 408 MHz） |
| GPU | **Mali-G31**（设备确认） |
| 显示 | **480×480 方形屏幕**（1:1），约 4 英寸，160 dpi（mdpi，与约 170 的物理 ppi 很匹配），60 Hz → **480×480 dp** 画布 |
| RAM | **2 GB** （ ≈ 1960 MB可用） |
| 存储 | eMMC ； `/data` ≈ 3.5 GB |
| Android | 8.1 (API 27) |
| ABI | arm64-v8a |
| 无线通信 | **Zigbee 3.0** （ UART上的Silicon Labs EFR32协调器 `ttyS5` —见下文）、Wi-Fi、蓝牙。无NFC、IR、以太网、蜂窝。 |

> [!NOTE]
> Cortex-A35是一种效率核心，每时钟吞吐量明显低于A55 （ TPA10 ）或A72 （ WF1589T ）。结合2 GB内存， NSPanel Pro是此处记录的三个面板中的 **入门级** —请参阅 [性能比较](README.md#性能比较与实际部署)。

> [!TIP]
> Changing firmware on a button-less panel? Read [Firmware backup & restore](../../firmware-backup-restore.md) first — the NSPanel Pro (PX30) uses [seaky's roottool/tools](../../firmware-backup-restore.md#per-panel-notes) rather than `rkdeveloptool`.

## 变体— 86P与120P

最初的 NSPanel Pro 系列有两种外形不同的面板，按欧盟墙盒的 **86 mm** 和 **120 mm** 规格命名。上面的规格表以及本页大部分内容取自 **86P**；**120P** 使用不同的电路板：

| | NSPanel Pro **86P** | NSPanel Pro **120P** |
|---|---|---|
| SoC | Rockchip **PX30** | Rockchip **RK3326-S** （相同PX30/RK3326系列； `ro.board.platform=rk3326`，设备树 `rockchip,px30`） |
| 显示 | **480 × 480** 正方形，约160 DPI ，仅纵向 | **750×1334** 纵向，**240 dpi**（可覆盖为 250）；支持横向显示；比 86P 窄约 1 厘米且更长 |
| 构建ID | 两者都报告 `ro.product.model/device/name = px30_evb` （共享Rockchip板名称— *不是* 可靠的变体鉴别器） | 为86P |
| `ro.product.version` | `s6_android_x.y.z`类 | `NSPanelXXXP_x.y.z` （ OTA通道 `nspanel-pro-ver120`，完整ROM `SN_3326S_750X1334_…`） |
| OTA 形式 | 截至 **4.0.12** 均为完整 ROM；此后索引中的版本以差分包或仅 APK 形式发布（参见[固件索引](../../hardware/nspanel-pro-firmware.md)） | 为86P |
| 接近传感器固件 | **4.0.12 恢复了距离**读数 | 在 4.x 中仍保持**二进制**读数（各型号内核存在差异——参见[传感器](#传感器环境光和接近传感器可由应用直接访问)） |

两者共享EFR32 Zigbee无线电、Android 8.1 (AOSP)、arm64-v8a以及下面的root/恢复方式。在120P （ fw `NSPanel120P_3.7.1`）上实时验证： `wm size`= 750 × 1334 ，密度240 ， `ro.board.platform=rk3326`。

> [!NOTE]
> This page does not crown a firmware version in prose — the generated [complete index](../../hardware/nspanel-pro-firmware-archive.md) is the authority, and it goes stale less often. The flashing procedure is hardware-verified through **4.4.0**; releases indexed past that are CDN-verified only, never live-flash verified here. As of 2026-08-14, for the most recently added of them no vendor documentation was found: [Sonoff's public changelog](https://sonoff.tech/en-us/blogs/news/sonoff-nspanel-pro-version-update-information-and-faq) documents up to **4.6.0**, 4.6.2 and 4.8.0 were located only by probing the CDN, and 4.7.0 is discussed only in an [eWeLink user feedback thread](https://forum.ewelink.cc/t/nspanel-pro-v4-7-0-feeback/208789) — a discussion thread, not a release announcement. The **4.5.3** release is a ROM diff on 120P but an APK-only update on 86P, and **4.6.2** is an app-only update with no ROM diff on either channel, so an upgrade is not always a single hop. Absence from the index means not-found-by-probe; the CDN cannot be listed, so it is never proof a build does not exist. The CoolKit CDN scheme and the full flashing how-to are on the [firmware & flashing page](../../hardware/nspanel-pro-firmware.md); every verified OTA URL is in the [complete index](../../hardware/nspanel-pro-firmware-archive.md), and the community-facing subset is the Discussion linked from there, which is regenerated from this repo's data files and can lag them.
>
> **⚠ Community reports describe restart loops on 4.5.1 / 4.5.2** (~10–60 min intervals on both models). For 4.7.0, the user feedback thread contains reports of sub-device connectivity trouble after updating, some resolved by a reboot and others described as continuing; this project has not reproduced or quantified them, so treat them as unverified user reports rather than a known regression. Verify any newer release on one panel before deploying widely; **4.0.12** remains the conservative full-ROM checkpoint to pin. The firmware Discussion carries the current community evidence, regenerated from this repo's index whenever the scheduled monitor next runs.

### 按版本划分的固件差异

Behaviour that changes across eWeLink firmware versions, oldest first. `ro.product.version` is the **internal** id (`s6_android_x.y.z` on the 86P / `NSPanelXXXP_x.y.z` on the 120P) — *not* the marketing/OTA number the eWeLink app shows (4.0.12, 4.5.x). Detection and any version-keyed logic must read `ro.product.version`, not the marketing string.

| 固件 | 特殊情况/行为 | 影响—该怎么做 |
|---|---|---|
| **较旧（ 1.3.2之前）** | 没有应用内adb切换；无法从UI访问开发者选项 | Enable adb via the internal **OTG port** (open the case) — [Gaining adb + root](#获得adb--root访问权限). |
| **v1.3.2+** | 启用 adb 的选项已移至 eWeLink 应用 | eWeLink → *Device Settings* → tap **Device ID ×8** → developer mode → adb. |
| **v1.4+** | 开发者模式已被**移除**，不再出现在 UI 中 | Enable adb via the **5× power-cycle** at the Sonoff boot animation — [Gaining adb + root](#获得adb--root访问权限). |
| **3.5.1 （ 86P ，已验证）** | 原厂系统WebView为 **Chromium 107.0.5304.105** —太旧，无法呈现现代HA仪表板；其他固件可能有所不同 | Check and update the WebView **first** — [WebView — update this first](#webview请先更新它). ha-paneld's panel-health banner also flags outdated versions (min Chromium 110). |
| **v3.7.1**（120P，实机验证） | 基线参考构建 | `wm size`= 750 × 1334 ，密度240 ， `ro.board.platform=rk3326`。 |
| **v4.0.0** （ 2025-09-19推出） | 原厂固件**内置 F-Droid**，并鼓励安装 FOSS/HA 应用；界面速度明显提升 | On-device install path opens — [Firmware v4.0.0](#固件v400-官方f-droid应用安装). Confirm **APP** *and* **OS** version both read ≥ 4.0.0. |
| **v4.0.12** | 接近度**范围读数**已在 **86P** 上恢复；**120P 仍为二进制读数**（源于各机型的内核差异） | Recommended stable pin for HA-only panels. The panel's raw input shape is model- and firmware-specific, but ha-paneld learns and normalizes either form — see [Sensors](#传感器环境光和接近传感器可由应用直接访问). |
| **v4.5.1/v4.5.2** | **Widespread community restart-loop reports** (~10–60 min, both models); 4.5.2 is an APK-only layer on 4.5.1 | **Superseded by later releases.** Pin at **4.0.12** for maximum stability, or test a newer release on one panel first. Check the firmware Discussion for current evidence. |
| **v4.5.3** | Matter 自动发现和屏幕管理优化；120P 使用 ROM 差分包，86P 仅更新 APK | No 4.5.3-specific restart-loop evidence found; superseded by later releases. |
| **v4.6.0** （ 2026年6月） | **本地 Web 门户**（`nspanelpro.local`——用于局域网设置、将 MQTT Discovery 导出到 HA，以及 Matter Bridge）；项目 CDN 检查发现来自 4.0.12 / 4.4.0 / 4.5.1 的差异 | 记录在Sonoff的公共更新日志中。 **4.6.2** 被索引为仅应用程序更新，两个通道上都没有ROM差异，并且未找到4.6.1。 |
| **v4.7.0** （ 2026年7月） | eWeLink 用户反馈主题中讨论了该版本，但 **Sonoff 的公开更新日志中没有该版本**；覆盖第一代和第二代面板；用户报告新增对 Basic 第五代继电器（BASIC-1GS）的支持；项目 CDN 检查发现两种型号均可从 4.0.12 / 4.4.0 / 4.5.1 / 4.6.0 获取入站差分包，此外仅 120P 可从 4.5.3 获取 | Community reports of sub-device connectivity trouble, some reboot-resolved and some described as continuing; unverified by this project. Verify on one panel before deploying widely. |
| **v4.8.0** （ 2026年8月） | **没有发布公告，也没有发现更新日志** —通过探测CDN定位。2026年7月16日， [路线图线程](https://forum.ewelink.cc/t/nspanel-pro-roadmap-and-co-created-future/206240) 中的一篇eWeLink员工帖子将其安排在8月，并确认了一项功能：通过eWeLink应用程序自动更新面板的选项。项目CDN检查发现，两种型号的入站差异均为4.0.12/4.4.0/4.5.1/4.6.0/4.7.0 ，仅120P的入站差异为4.5.3 | Contents otherwise unknown, and no community feedback thread has been found, so there is no report either way on stability. Treat it as unassessed rather than clean. **If the auto-update option ships enabled, a panel could take firmware unattended** — check that setting before relying on a pinned version. |

> [!NOTE]
> These are original 86P/120P quirks. The NSPanel Pro **Gen2** (RK3326-**S**, dual relays, EFR32**MG24**) is a different hardware target. Sonoff ships Gen1 and Gen2 on the same firmware version line (4.7.0 covers both), so do not infer a separate firmware line or assume every original-model note carries over.

Sibling Tuya-family boards — **S6E/T6E** (relay variants; S6E = T6E + 2 relays), [**S9E**](../../hardware/s9e.md) (Smatek), [**TPA10**](tpa10.md) (RK3566, Cortex-A55, Android 11) — are separate targets, not NSPanel Pro firmware.

> [!CAUTION]
> Detection can't rely on `ro.product.model` (both are `px30_evb`). Use `ro.product.version` / display metrics / `ro.board.platform` to tell 86P from 120P. Proximity behavior also differs between models and firmware, so ha-paneld learns from the live readings instead of selecting a firmware-specific classifier.

## 获得adb + root访问权限

Unlike the TPA10, the NSPanel Pro has **no adb password** — it ships as a `userdebug` / test-keys build (`ro.debuggable=1`), so `adb root` works and `/system` is remountable. The hard part is only *reaching* developer options, which the eWeLink firmware hides differently per version. Distilled from blakadder's guides ([sideload](https://blakadder.com/nspanel-pro-sideload/), [secrets](https://blakadder.com/nspanel-pro-secrets/)).

**1. Enable adb** — the route depends on firmware:

- **Older firmware** — open the case (back screws, disconnect the touch connector) to expose the OTG USB port and connect a host; adb works directly over USB.
- **Firmware v1.3.2+** — in the **eWeLink app** → the panel's *Device Settings*, tap the **Device ID 8×** to enable developer mode, which restores adb.
- **Firmware v1.4+** (developer mode removed) — power-cycle the panel **5×** during the Sonoff boot animation to force a recovery boot, and in that window `adb install ultra-small-launcher.apk`; after reboot set that launcher as default, then *Settings → System → About tablet → Build number* ×7 to re-enable developer options and turn on USB debugging.

**2. Go to network adb** (so you don't need the case open):

```bash
adb tcpip 5555
adb shell ip -o a            # find the panel IP
adb connect <panel-ip>:5555
adb shell su 0 setprop persist.adb.tcp.port 5555   # survive reboot (service prop resets)
```

**3. Root.** Because the build is `userdebug`, `adb root` gives a root adbd shell immediately. ha-paneld calls `su` from the app sandbox, so install a persistent `su` into `/system` (this fleet's panels carry **SuperSU `su` 2.76** at `/system/xbin/su`):

```bash
adb root
adb disable-verity          # only if remount is refused; this reboots the panel
adb remount                 # or: adb shell mount -o remount,rw /system
adb push su /system/xbin/su
adb shell chmod 06755 /system/xbin/su
```

> [!CAUTION]
> Disable the eWeLink apps (`com.eWeLinkNSPro.dev`, `com.eWeLinkControlPanel`) only **after** adb + `su` are solid and you have a home/back alternative — ha-paneld's nav actions cover the latter. Note the eWeLink **Zigbee gateway** stack is independent of these apps and keeps running; manage it with ha-paneld's [Zigbee router switch](#zigbee网关) rather than removing it.

## 固件v4.0.0 —官方F-Droid应用安装

From **v4.0.0** (phased roll-out from 19 September 2025) the stock eWeLink firmware **officially bundles [F-Droid](https://f-droid.org/)** and promotes installing FOSS apps on the panel — Home Assistant's own Companion app is the headline example. Update via the panel (top drop-down → *Settings → About → Software update*) or the eWeLink app, then confirm both **APP Version** and **OS Version** read ≥ 4.0.0. Sonoff states F-Droid apps "will not affect NSPanel Pro's original features" (existing setups/automations stay intact) and that an app's F-Droid build "may differ slightly from the latest release". The update also markedly speeds up screen-swipe/UI responsiveness. Source: [Sonoff — NSPanel Pro V4.0.0 update](https://sonoff.tech/en-us/blogs/news/nspanel-pro-v4-0-0-update-now-supports-f-droid-and-home-assistant-app-install).

> [!NOTE]
> **Why this matters for ha-paneld.** F-Droid is a sanctioned, **on-device** install channel, so an APK can reach a panel with **no PC/adb** and F-Droid handles update notifications. **But F-Droid solves distribution, not privilege:** the headline features (overlay navbar, screen on/off, relays, button LEDs, Zigbee control) still need `su`, so the adb/root setup above stays a prerequisite for full function — only the non-privileged surface (MQTT discovery, sensors, brightness, HTTP UI) works on a stock unrooted panel.

## WebView——请先更新它

An 86P freshly flashed to firmware `3.5.1` (build `164637`) was verified with `com.android.webview` **107.0.5304.105** (Chromium 107), which is too old to render a current Home Assistant dashboard. Other firmware and models may differ, so check the installed provider before deciding whether to update. The archived OTA diff packages do not include a WebView APK, so this version was read from the live unit with `dumpsys webviewupdate`. That unit runs Chromium **138** after a clean adb update. See [Updating the system WebView](README.md#更新系统webview).

## LED

本设备上未发现 `/sys/class/leds` RGB节点和 `/dev/ledjni` ，因此NSPanel Pro上没有 **应用程序/sysfs可控RGB LED** （对比TPA10的 `avsux` 节点和WF1589T的 `/dev/ledjni`）。屏幕亮度/背光使用标准Android路径。

## 传感器——环境光和接近传感器可由应用直接访问

与TPA10 （光照/温度仅可通过 root 访问）不同， NSPanel Pro通过标准 `SensorManager`： `android.sensor.light`， `android.sensor.proximity`和 `android.sensor.accelerometer` 暴露其Sensortek组合—所有这些均可由普通应用程序读取，无需 root。ha-paneld在此处直接读取光照和接近度。未安装温度/湿度传感器。

> [!NOTE]
> **接近读数取决于固件和型号。** 该传感器是 Sensortek STK3A5x ToF，位于盖板玻璃后方的顶部 PCB 开口处。各设备的静止基线差异很大（一台约 1000，另一台约 4000）；只有 *相对* 变化才重要，因此较高的空闲基线是正常现象，并非故障。截至约 **3.3** 版固件，它报告距离读数（采样间隔约 50 ms）；从约**3.3–3.4** 起，内核驱动将其改为二进制 0/1。 **4.0.12 仅在 86P 上恢复了距离读数** —— **120P 仍保持二进制读数**。ha-paneld 根据实时行为处理两种形式，并在整个设备群中归一化有效范围；配置文件不再编码各固件的阈值或距离/二进制分类器。（来源：seaky tools #142/#144/#171/#262。）

<details>
<summary>已绑定的 I2C 设备（实际硬件）</summary>

| i2c地址 | 驱动程序/名称 | 它是什么 |
|---|---|---|
| `0-0020` | `rk809` | PMIC |
| `1-001a` / `1-005a` | `CST226` / `CST226SE` | Hynitron电容式触摸控制器 |
| `2-003c` | `tp` | 触摸屏 |
| `2-0046` | `ls_stk3a5x` + `ps_stk3a5x` | Sensortek **STK3A5x** 环境光+接近组合 |
| `2-0047` | `ls_stk3x3x` + `ps_stk3x3x` | Sensortek **STK3x3x** 光+接近（替代变体） |

</details>

## Zigbee网关

NSPanel Pro 内置**Silicon Labs EFR32 Zigbee 3.0 无线电模块**，通过 UART `/dev/ttyS5` 连接，并由制造商的主机协议栈（`/vendor/bin/siliconlabs_host/zgateway`）经本地 MQTT broker 驱动——eWeLink 应用也使用同一协议栈，因此该面板出厂时可作为 eWeLink Zigbee 网关。

ha-paneld manages it directly (v0.6.1+): `switch.<panel>_zigbee_router` turns the panel into a Zigbee **router/repeater** that extends your existing mesh (it starts the gateway and ensures the Repeater role), and turns it back off again (stopping the gateway, freeing the radio). It works over the local broker — credential-free, no `ttyS5` handling. The panel then appears as a normal router in your ZHA / Zigbee2MQTT coordinator.

> [!NOTE]
> Switching role is **not a reflash** — there is no `.gbl`/bootloader step; it just sets the EZSP node type. For partition-level firmware work see [Firmware backup & restore](../../firmware-backup-restore.md).

> [!NOTE]
> **没有任何文档记录或实测确认的 Thread 边界路由器。** 已安装的厂商协议栈将 EFR32 用作 Zigbee NCP。尽管 EFR32MG21 芯片支持多协议，但这不能证明面板上存在 Thread 固件或边界路由器实现；Sonoff 记录的是 Matter Bridge。
>
> **4.x reworked the Zigbee stack** — community inspection found a forked Zigbee2MQTT, a changed on-device MQTT password and a different boot sequence. [Sonoff documents coordinator↔router switching](https://sonoff.tech/blogs/news/nspanel-pro-v4-3-0-central-heating-redefining-whole-home-temperature-automation) in current firmware, but ha-paneld's private local-broker control path was built against ≤3.x and **may need adapting on 4.x**. (Community sources: seaky tools #244/#241/#255 and roottool#3.)

> [!WARNING]
> **A legacy vendor-native Zigbee-watchdog defect is confirmed by the reporter on NSPanel Pro 120 stock 3.8.0.** Firmware containing the recursive `LD_LIBRARY_PATH` assignment described in [Issue #34](https://github.com/maxlyth/ha-paneld/issues/34) can eventually make every external command launched by the watchdog fail with `E2BIG`, consume one CPU core and stop recovering a dead `zgateway`. A reboot resets the problem only temporarily. See [Performance tuning](../performance.md#排除旧版原厂-nspanel-pro-的-zigbee-看门狗缺陷) for the evidence boundary and repair-safety requirements. The reporter-provided workaround has not yet been independently validated by the project. Community inspection of 4.0.12 and 4.6.0 did not find the vulnerable assignment.

### 要求—固件≥ v2.2.0

The host stack is the **manufacturer's own** (eWeLink/Sonoff) gateway, versioned to match the panel firmware (e.g. `sonoff-v3.5.4`). Zigbee **router mode** was added in **NSPanel Pro firmware v2.2.0** (2023 — eWeLink app → *Device Settings → Pilot Features → Zigbee Mode*); local host-stack repeater support landed in gateway package v1.1.9. In practice:

- **Gateway present** (firmware ≥ v2.2.0, or side-loaded) → ha-paneld detects it and publishes `switch.<panel>_zigbee_router`. Toggle ON and the panel joins your coordinator as a router.
- **No gateway** (very old firmware, never provisioned) → the switch **doesn't appear** — it's gated on the gateway's launch script existing. Update firmware (≥ v2.2.0), or see migration below.

ha-paneld **drives** the gateway; it doesn't ship or install it (it's eWeLink's binary). Recent firmware (4.x) adds a Matter bridge and can export Zigbee devices to Home Assistant through MQTT Discovery — alternatives to the router role.

### 网关健康和自动隔离

On a Zigbee-capable panel, `sensor.<panel>_zigbee_gateway_health` reports the vendor stack independently of the router switch. This means an unconfigured stock gateway is still visible without granting ha-paneld permission to stop it.

When the router switch has explicitly been turned ON, ha-paneld allows a 15-minute startup and pairing grace, then watches once per minute for two runaway signatures: an explicitly invalid/unjoined network combined with more than 50% of one CPU core for five consecutive samples, or at least three gateway PID changes within ten minutes. A joined router with sustained high CPU is warning-only and remains running. Unknown 4.x layouts or missing firmware-specific join evidence fail safe to `unknown`.

Turning the Zigbee router switch ON explicitly requests Repeater mode even when the vendor gateway process is already running, so an ON command sent while your ZHA/Zigbee2MQTT coordinator permits joining acts as a fresh join retry without spawning a second gateway supervisor.

The Configure tab shows a **Request join** action directly beneath the Zigbee router switch. The existing switch remains the only on/off control. Enable permit-join on ZHA/Zigbee2MQTT, then request joining and confirm that permit-join is open. The action reasserts Repeater mode, starts a fresh 15-minute grace and polls the bounded health status; it does not reboot or restart the panel. The button is unavailable while the router is disabled, already joined or cooling down after a recent request.

After the pairing grace, an enabled gateway that is still unjoined produces a persistent dashboard, Install-tab and status-API warning linked to that Configure action. Do not leave it in that state: repeated join retries can consume substantial CPU. Either join the panel as a router or turn off the Zigbee router switch.

If a configured legacy gateway meets a runaway rule, ha-paneld persists the router switch OFF and attempts one bounded containment. Vendor-native containment can target only the Sonoff guard, `zgateway`, and the matching local broker. If a process cannot be stopped, the respawner is removed where possible and surviving gateway work is demoted to nice 19 and Android's background cpuset. Turning the router switch ON later explicitly starts one fresh grace period and retry.

The health attributes include firmware/product version, gateway layout/package version, joined/role status, rounded gateway and guard CPU, recent restart count and containment result. They never include the Zigbee network key, raw local-broker credentials, radio MAC or raw gateway `netinfo`.

### 从NSPanelTools迁移

[NSPanelTools (NSPPT)](https://github.com/seaky/nspanel_pro_tools_apk) side-loads the official Sonoff gateway package onto firmware that didn't ship it; many users run it today. ha-paneld coexists and can take over the gateway:

- **Side-by-side is fine.** ha-paneld's router control is idempotent — it **defers** to whatever already runs the gateway (won't double-start or fight NSPPT); auto-brightness is opt-in/off. Nothing conflicts by default.
- **Handing the gateway to ha-paneld:** the host stack lives in `/vendor` and **survives uninstalling the NSPPT app** (verified — a persistent hook even keeps boot-starting it). Remove the NSPPT APK and ha-paneld keeps driving the gateway; if the boot hook is also stripped, ha-paneld's boot-restore starts it when the switch was left ON.

> [!NOTE]
> Both tools touch the screen/sensors. Coexistence is benign today, but enabling overlapping features (e.g. wake-on-wave alongside an NSPPT equivalent) can cause redundant actions — remove NSPPT once ha-paneld covers your needs.

<details>
<summary>EZSP 主机协议栈内部机制（MQTT 代理主题、监管进程、角色持久化）</summary>

On the legacy vendor-native ≤3.x stack, the radio runs **EZSP NCP firmware** (EFR32MG21, EZSP v8); `zgateway` is an EZSP *host* binary in `/vendor/bin/siliconlabs_host/`, kept alive by its own `guard_process.sh` supervisor (a 5-second loop, boot-started) and controlled over a **local mosquitto broker** on `127.0.0.1:1883` (anonymous — the `password_file` line is commented out in `mosquitto.conf`). The 4.x stack differs as described above.

- role status: `zigbee/system/network-role/information` → `{"role":"Repeater"|"Coordinator"}`
- role switch: `zigbee/system/network-role/switch` ← `{"role":"Repeater"}`

"Repeater" is router mode (extends an existing mesh — the supported sweet spot); the role persists in the NCP's NVM. The vendor `zgateway` survives removal of the eWeLink *apps* (it lives in `/vendor`, not in an APK).

For a full standalone Zigbee2MQTT/ZHA coordinator *on the panel* instead, see [seaky/nspanel_pro_zigbee](https://github.com/seaky/nspanel_pro_zigbee), which swaps the host stack (heavier; not what ha-paneld does).

</details>

## 访问模式摘要

- **环境光/接近/加速度计**：应用可直接访问（`SensorManager`）。
- **Screen brightness / sleep / navigate / TTS**: standard Android paths (`su` for true backlight-off).
- **LED**：尚未确认任何可控 LED。
- **Zigbee**: EFR32 radio managed via the on-device gateway's local broker (`switch.<panel>_zigbee_router`).
- **无线电**： Zigbee 3.0 + Wi-Fi/BT。

## 性能预期

对于内容丰富的仪表板，NSPanel Pro 会受到 **CPU 和 RAM 的限制**：

- 空闲时 CPU 频率为 408 MHz，RAM 占用约 500 MB；高负载的 Lovelace 仪表板会给两者带来很大压力。
- **2 GB RAM 是主要瓶颈**——仪表板 WebView、Android 和后台应用会争用内存；包含大量卡片、大图片、长时间范围历史图表或计算开销较大的自定义卡片的大型仪表板会导致 WebView 重新加载和卡顿。
- A35 CPU 核心会使页面切换和动画明显慢于 A55/A72 面板。

使用 ha-paneld 的内置渲染器时，请先启用[自动仪表板实体筛选器](../performance.md#1-筛选内置渲染器的实体订阅)，这样面板就不会处理仪表板从未显示的状态。然后在精简仪表板之前，使用“仪表板”选项卡中的性能卡片找出其余高负载视图、内存压力或散热限制。Companion 渲染器不支持该筛选器；对它而言，仍应采用源端更新调优和更精简的仪表板。

---

有关跨面板比较和方法，请参阅[面板硬件索引](README.md)；有关其他面板，请参阅 [TPA10](tpa10.md) / [WF1589T](wf1589t.md) / [S9E](../../hardware/s9e.md) 参考页面。
