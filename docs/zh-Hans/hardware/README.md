> [!IMPORTANT]
> 本文档由机器生成并经过自动交叉核验，但尚未由中文使用者进行系统审阅。英文文档为权威版本。[阅读英文原文](../../hardware/README.md)，或[创建翻译更正议题](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml)。

# 面板硬件参考

Reverse-engineered hardware fact sheets for the wall panels ha-paneld targets — SoC, LED control, sensors, buttons, NFC, Zigbee/IR, relays, adb/root access. These devices ship with almost no public documentation, so these notes record what is physically on each board and how to drive it, gathered from live units (rooted / userdebug `adb root`). If your panel is not listed, start with the no-build [runtime profile authoring workflow](../../profiles/README.md). If your panel has a camera the Camera card does not offer, see [enabling the camera on a panel whose profile does not declare one](../../profiles/unofficial/README.md#enabling-the-camera-on-a-panel-whose-profile-does-not-declare-one) and keep unverified hardware facts explicit.

| 面板 | SoC | LED控制 | 主要传感器 | NFC | Zigbee/IR | 参考资料 |
|---|---|---|---|---|---|---|
| Tuya TPA10 | rk3566 | `avsux` sysfs （root 守护进程） | ToF VI5300、CHT8305 温湿度传感器、CG5256 光照传感器；**[摄像头](tpa10.md#相机)**（GC05A2）和 ES7202 音频采集 ADC（麦克风是否可用尚未验证） | 否 | 否 | [tpa10.md](tpa10.md) |
| Electron WF1589T | rk3576 | `/dev/ledjni` (应用直接访问) | 6轴IMU （ KXTJ9 + BMA2xx ） ； **[摄像头](wf1589t.md#摄像头)** （ GC05A2 ）和ES7202麦克风 | 是（恩智浦，但Android-NFC已禁用） | 否 | [wf1589t.md](wf1589t.md) |
| Sonoff NSPanel Pro | rk3326/PX30 | 无（无RGB节点） | STK3A5x 光照和接近传感器（应用直接访问） | 否 | **Zigbee** （ Silabs EFR32 ， UART ） ；无IR | [nspanel-pro.md](nspanel-pro.md) |
| Smatek S9E † | rk3566 | 每个按钮的GPIO LED （root） | 雷达接近传感器、光照传感器、温湿度传感器；**2 个市电继电器**（`st_relay`）；RS485 和以太网 | 否 | **Zigbee** | [s9e.md](../../hardware/s9e.md) |
| 智彩SMT1019 ‡ | rk3576 | 供应商 `userdebug` 上提供 root 辅助程序；该程序在原厂固件上不可用 | GXHT30 温湿度传感器（精度未验证）；实验性 VI530x 接近传感器支持 | 否 | 否 | [smt1019.md](../../hardware/smt1019.md) |
| ZX-SMT156/RK3566_T ‡ | rk3566 | `/dev/ledjni` (应用直接访问) | 二值接近传感器、环境光传感器；GXHT30 温湿度传感器（通过辅助程序或固定的 shell 级替代方案） | 未知 | 据报告配有供应商继电器，控制路径未知 | [zx-smt156.md](../../hardware/zx-smt156.md) |
| Shelly Wall Display § | MT6580/SC7731E/RK3326-S/RK3566 （取决于型号） | 尚未确定 | original/X2/X1i/X2i/XL 配有环境光传感器；original 和 X2 配有温湿度传感器；X2/X1i/X2i 配有接近传感器；XL 配有运动传感器；继电器因型号和底座而异 | 尚未针对所有型号确认 | 尚未针对所有型号确认 | [shelly-wall-display.md](../../hardware/shelly-wall-display.md) |

† S9E 规格来自 Smatek 的产品页面；控制路径来自 [#98](https://github.com/seaky/nspanel_pro_tools_apk/issues/98) 和 HA 社区帖子，**未**在本地实体设备上验证——继电器和按钮支持已经实现，但尚未测试。

‡ SMT1019 和 ZX-SMT156 的信息来自报告者诊断及链接的 OEM 或零售证据（[#8](https://github.com/maxlyth/ha-paneld/issues/8)、[#24](https://github.com/maxlyth/ha-paneld/issues/24)）；本地均无这两款面板可供测试。SMT1019 辅助程序的持久性和温湿度原始轴数据已有报告者证据，但温湿度精度、端到端接近检测和完整配置文件仍需硬件测试。ZX 温湿度支持是可选的；USB 或供应商 root 访问方式以及持久解锁方式仍未测试。

§ Shelly Wall Display 的信息来自固件 OTA 分析（包括对新版分区映像的设备树解析）、官方更新日志以及社区和知识库来源——**未**在本地实体设备上验证。**旧版** OTA 声明了一个 `userdebug` 目标构建，因此若能取得 adb 切入点，或可在该版本上使用 `adb root`；**新版** OTA 未声明构建类型——有关各更新分支的证据及 Shelly 对当前硬件的说明，请参阅 [shelly-wall-display.md](../../hardware/shelly-wall-display.md)。随附的 `shelly-wall-display` 和 `shelly-wall-display-v2` YAML 配置文件已经实现，但仍属推测。

> [!TIP]
> Before modifying firmware on a rooted **TPA10 or WF1589T**, read [Firmware backup & restore](../../firmware-backup-restore.md). Those Rockchip panels use `adb reboot loader` and `rkdeveloptool` rather than the usual Android button combination. The guide does not apply to the MediaTek Shelly family, an unrooted panel or uncharacterised hardware, and it does not yet claim a write-ready Maskrom recovery path.

## 方法

- **实际芯片**：通过 `/sys/bus/i2c/devices/*/name` 绑定i2c设备— *而不是* `…/drivers/`，因为Rockchip BSP编译了数百个可选驱动程序， `drivers/` 列表过度报告。
- **无线模块**：`pm list features`（`nfc`、`consumerir`、`bluetooth`、`ethernet`……）和 `/dev` 节点。
- **Android 提供的传感器**：`dumpsys sensorservice`。
- **控制接口**：`/sys/class/leds`、`/dev`，以及每个 LED 节点自身的属性（某些面板的属性自带说明，例如 TPA10 的 `avsux_info` / `avsux_firmware`）。

欢迎对其他面板进行更正和添加。

The `Native` navbar mode is profile-gated, not specific to Electron panels. The bundled WF1589T profile currently declares it because that firmware's Android navbar has been verified. Other profiles can enable the same mode after their system bar has been confirmed.

## 获得adb + root访问权限

每个面板以不同的方式获得 adb/root 访问权限 ；每个面板页面具有完整的固件特定步骤：

- **Sonoff NSPanel Pro** — `userdebug`/test-keys, **no adb password**; the only hurdle is reaching developer mode (varies by eWeLink firmware). `adb root` + remount + a SuperSU `su`. → [nspanel-pro.md](nspanel-pro.md#获得adb--root访问权限).
- **Tuya TPA10** — adb is **password-protected**; the reliable route is the USB diagnostics-app backdoor (`su` already present). → [tpa10.md](tpa10.md#获取-adb-和-root-权限).
- **Electron WF1589T** — `userdebug` with Google Play; `adb root` works directly (LED is app-direct, so root is rarely needed). → [wf1589t.md](wf1589t.md).

## 性能比较与实际部署

这三类面板形成了清晰的性能梯级： **NSPanel Pro（PX30）** 属于入门级， **TPA10（rk3566）** 属于中端， **WF1589T（rk3576）** 属于高端。屏幕尺寸和长宽比是首要设计约束；对于 2 GB 面板，RAM 才是限制性能的主要瓶颈。数据来自 ha-paneld 自身的 `/perf` 端点和设备规格。

<details>
<summary>规格梯级（CPU / RAM / GPU / 显示屏）</summary>

| | NSPanel Pro (PX30) | TPA10 (rk3566) | WF1589T (rk3576) |
|---|---|---|---|
| CPU | 4 × Cortex-A35 @ 1.5 GHz | 4 × Cortex-A55 @ 1.8 GHz | 4 × A72 @ 2.1 GHz + 4 × A53 @ 1.9 GHz |
| RAM | 2 GB | 2 GB | 4 GB |
| GPU | Mali-G31 | Mali-G52 (2EE) | Mali-G52 (MC3) |
| 显示 | 480 × 480 **正方形**， ~ 4 " | 1920×1200 16:10，约 10.1 英寸/约 226 ppi | 1920×1200 16:10，约 10.1 英寸/约 226 ppi |
| 刷新率 | 60 Hz | 56 Hz | 60 Hz |
| 布局(dp) | 基准逻辑密度为 160 dpi → 480×480 dp | 基准逻辑密度为 240 dpi；ha-paneld 建议设为 212 | 基准逻辑密度为 160 dpi → 1920×1200 dp——界面过小，需[提高密度](wf1589t.md#显示密度调高) |
| 摄像头 | 无 | GC05A2，`Facing: Back`；H.264 编码使用 `OMX.rk.video_encoder.avc` | GC05A2，`Facing: Front`；H.264 编码使用 `c2.rk.avc.encoder` |
| 级别 | 入门级 | 中端 | 高端 |

</details>

<details>
<summary>实时 `/perf` 快照（仅作说明，并非受控基准测试）</summary>

每个面板在其自己的实际工作负载下：

| | PX30 （大部分空闲） | WF1589T （活动仪表板） |
|---|---|---|
| CPU | 9% | 29% |
| 时钟频率 | 408 MHz（最高 1512 MHz） | 大核 1608 MHz（最高 2112 MHz） |
| 使用的RAM | 508/1960 MB | 2265/3897 MB |
| 温度 | 49 °C | 63 °C |
| 响应性 | 流畅，主线程占用 3.6% | 流畅，主线程占用 25.9% |

（TPA10 的 CPU 性能介于两者之间。）

</details>

**这对真正的仪表板部署意味着什么：**

- **屏幕几何形状是第一个设计约束。** NSPanel Pro的480 × 480 **正方形** （ 480 dp ）仅适合单个窄列； TPA10的10.1英寸1920 × 1200显示屏真正适合多列仪表板； WF1589T以低基本逻辑密度出货，因此UI很小，直到升高。将仪表板设计为面板的 **dp画布+纵横比**，而不是其原始像素数。Android的基本逻辑DPI是布局设置，而不是物理PPI。
- **2 GB面板（ PX30、TPA10 ） ： RAM是主要限制。** 仪表板WebView、Android和任何后台应用程序共享约2 GB ；包含许多卡片、大图片、长历史图形或昂贵的自定义卡片的复杂仪表板会触发WebView重新加载和jank。WF1589T的4 GB在很大程度上消除了这种压力。
- **在此比较中， NSPanel Pro的CPU速度最慢** (A35) ，因此过渡和动画明显慢于A55/A72 设备。保持仪表板最精简。
- **对于内置渲染器，请在简化仪表板或更换面板之前筛选Home Assistant实体订阅。** 自动实体学习可以防止不相关的状态到达WebView ，同时保留面板的正常Home Assistant连接。请参阅 [性能调整](../performance.md)。
- **ha-paneld测量剩余的瓶颈**：仪表板响应时间、意外重新加载、CPU时钟和节流、内存压力、最繁忙的进程和WebView渲染指标有助于区分硬件限制与昂贵的仪表板或过多的数据。

## 更新系统WebView

**首先阅读** —这是这些面板上最常见的首次运行失败。

ha-paneld's built-in renderer and the HA Companion app both rely on Android's **system WebView**, and most of these panels ship with one far too old to run a current Home Assistant frontend. Out of the box this can produce a **blank or broken dashboard, missing cards, or "browser not supported"**. Panels **without** Google Play (NSPanel Pro, TPA10) cannot update it automatically through the Play Store, so install a current WebView using the appropriate method below. The **WF1589T and the SMT1019 have Google Play**, so update *Android System WebView* from the Play Store or use the Play WebView development channel.

The clean way is a direct adb sideload of the standard Android System WebView (package **`com.android.webview`**), matched to the panel's Android version and ABI — **no F-Droid, no third-party app store** (the workarounds the NSPanel-Pro community threads resort to). Per-panel known-working builds and the full sideload/verify steps are below.

> [!TIP]
> The package name must be `com.android.webview` for the system to select it automatically. Mind the distinction: the **SystemWebView** builds from Cromite and LineageOS use `com.android.webview` and *do* register as the provider — but the regular **Cromite / Bromite *browser*** app uses a different package and does **not**. Use the SystemWebView build, not the browser APK.

### 每个面板的原厂版本和已知可用的替代品

"Stock" = what the vendor firmware ships from factory, verified from firmware OTA inspection or a live device. "Replacement" = what is confirmed working after sideload. Redistributable builds are mirrored as ha-paneld Release assets; sideload with `adb install -r <file>`.

| 面板 | ABI | 原厂（供应商固件） | 替换(`com.android.webview`) | 下载 |
|---|---|---|---|---|
| NSPanel Pro 86P (PX30) | arm64-v8a | Chromium **107.0.5304.105** verified on firmware 3.5.1; check other firmware/models before updating | **LineageOS** 138.0.7204.63 — last build for Android **8.1** | [发布资源](https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-138.0.7204.63.apk) · [APKMirror](https://www.apkmirror.com/apk/lineageos/android-system-webview-2/android-system-webview-138-0-7204-63-2-release/android-system-webview-138-0-7204-63-8-android-apk-download/download) |
| TPA10 (rk3566) | armeabi-v7a | **Chrome 83** (`com.android.webview`) —对于当前HA前端来说太旧了 | **LineageOS** SystemWebView 150.0.7871.63 — vanilla Chromium, allows camera autoplay (Cromite 147 blocks it, kept as fallback). **Signature-locked — needs the root swap in [tpa10.md](tpa10.md#webview--请先更新), not a plain sideload.** | [ARM资产](https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm.apk) |
| WF1589T (rk3576) | arm64-v8a | Google Play WebView （自动更新） | update via Play Store — no sideload needed | — |
| S9E (rk3566) | **arm64-v8a** | **Firmware-dependent — check before replacing.** Chromium **83.0.4103.120** on the 2024-07 build (too old for a current HA frontend), but **131.0.6778.200** on the 2025-12 build | Only needed on the older firmware: **LineageOS** 150.0.7871.63 (**arm64**) — *provisional, unverified hardware*; may be signature-locked like the TPA10. On 2025-12 firmware the stock WebView is already current | [arm64资产](https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm64.apk) |
| SMT1019 (rk3576) | arm64-v8a | `com.google.android.webview` **124.0.6367.179** — Android 14 **带** Google Play | **Update *Android System WebView* from the Play Store** — no sideload needed. Do not sideload a `com.android.webview` build here: the panel's own provider list is supplied by a product overlay, so a sideloaded provider may not be selected | — |
| ZX-SMT156/RK3566_T | arm64-v8a | Google WebView **149.0.7827.164** （报告器固件） | Google WebView is current; no replacement needed | — |
| 雪莉壁挂原装(MT6580) | armeabi-v7a | **未知** （ Android 7 BASE ROM ） | `com.google.android.webview` **119.0.6045.194** via [official Shelly ZIP](https://repo.shelly.cloud/firmware/SAWD-0A1XX10EU1/stable/SAWD-0A1XX10EU1-WebViewUpdate.zip) — see [shelly-wall-display.md](../../hardware/shelly-wall-display.md#webview) | — |
| Shelly Wall Display X2 (SC7731E) | armeabi-v7a | **未知** （ Android 8.1 BASE ROM ） | 未建立—检查 `adb shell dumpsys webviewupdate` | — |
| Shelly壁挂显示器X1i/X2i/XL (arm64) | arm64-v8a | **未知** （ Android 11基础ROM ；未出现在Shelly OTA中） | 未建立—检查 `adb shell dumpsys webviewupdate` | — |

所有镜像版本都在 [**面板WebView镜像** 版本](https://github.com/maxlyth/ha-paneld/releases/tag/webview-mirror) 中实时发布—旨在作为已知工作版本的持续更新的社区列表。有一个在另一个面板或版本上工作？欢迎贡献。

> [!NOTE]
> - **Pick the newest WebView your panel's Android version supports.** The NSPanel Pro's Android 8.1 caps at 138 (the last Chromium for Android 8/9); newer builds won't install. Android 10+ (the TPA10's 11) runs current **LineageOS** WebView (150).
> - **APKMirror的 *直接* 下载链接是一小时内到期的短期预签名URL** —使用页面或上面的ha-paneld Release 资源（耐用）。镜像站之所以存在，正是因为面板缺乏Play和出货多年的固件，否则工作构建可能需要数天时间才能找到。

<details>
<summary>旁加载+验证步骤</summary>

1. Download a current **Android System WebView** APK — package **`com.android.webview`**. **LineageOS** System WebView is the recommended build across Android versions: 138 is the last for Android 8.1, and 150 covers Android 10+ (both in the mirror). It's vanilla Chromium, so it doesn't carry Cromite's autoplay block that stops HA camera streams. It uses the `com.android.webview` package, so it's picked as the provider automatically (no allowlist editing, no extra app), and it's open / freely redistributable. Match your panel's ABI; per-panel downloads are above.

> [!IMPORTANT]
> The simple sideload below works on panels whose ROM waives the WebView signature check (e.g. the NSPanel Pro's userdebug build). **Signature-locked panels (the TPA10, and likely other vendor user builds) reject a plain sideload** — `signatures do not match`. Those need the one-time root swap (replace the system WebView file + clear its `packages.xml` entry); see [tpa10.md → WebView](tpa10.md#webview--请先更新) for the exact procedure.
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

每面板概况介绍： [NSPanel Pro](nspanel-pro.md) · [TPA10](tpa10.md) · [WF1589T](wf1589t.md) · [S9E](../../hardware/s9e.md) · [SMT1019](../../hardware/smt1019.md) · [ZX-SMT156](../../hardware/zx-smt156.md) · [Shelly Wall Display](../../hardware/shelly-wall-display.md)。
