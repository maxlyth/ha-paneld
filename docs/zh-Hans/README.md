> [!IMPORTANT]
> This document is machine-generated and automatically cross-checked, but it has not been systematically reviewed by speakers of this language. The English documentation is authoritative. [Read the English source](../../README.md) or [open a translation correction issue](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="../../app/src/main/res/drawable-night-nodpi/wordmark.png">
  <img src="../../app/src/main/res/drawable-nodpi/wordmark.png" width="360" alt="ha-paneld">
</picture>

[![CI](https://github.com/maxlyth/ha-paneld/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/maxlyth/ha-paneld/actions/workflows/ci.yml)
[![版本](https://img.shields.io/github/v/release/maxlyth/ha-paneld?include_prereleases&sort=semver&style=flat-square&color=blue)](https://github.com/maxlyth/ha-paneld/releases)
[![许可证](https://assets.ha-paneld.com/docs/badge/license-apache-2-0-8aa187e4.svg)](../../LICENSE)

<!-- docs-i18n-language-picker:start -->
[English](../../README.md) · [Deutsch](../de/README.md) · [Français](../fr/README.md) · [Italiano](../it/README.md) · [Español](../es/README.md) · **简体中文**
<!-- docs-i18n-language-picker:end -->

**适用于 Android 壁挂面板的通用 Home Assistant 仪表盘应用。**

ha-paneld 让 Home Assistant 仪表盘能够在原本使用起来过慢或不便的面板上切实可用。低性能面板连接到大型 Home Assistant 实例后，可能会变得迟缓，或需要数秒才能响应。一个重要原因是，面板接收并处理的实体更新远多于其仪表盘所显示的实体。 **ha-paneld 的内置渲染器可以了解仪表盘使用了哪些实体，并要求 Home Assistant 仅发送这些实体的状态**。在实际使用中，这可以将实体负载减少 10–100 倍，让该仪表盘终于可用。

ha-paneld 还为不同品牌的壁挂面板提供一套一致的 Home Assistant 控件。根据硬件的不同，这些控件可能包括屏幕、LED、按钮、传感器、继电器和音频。MQTT 发现功能无需为每台设备编写 YAML 即可添加可用控件，安装程序则负责完成 Android 设置。

这是专用于壁挂面板的应用，并非用于个人手机。硬件支持通过常规 YAML 配置文件描述，因此用户和制造商无需重新构建应用即可添加其他面板。

Web 界面提供一个统一的位置，用于配置面板、安装软件以及查明问题所在。其性能工具可测量仪表盘响应时间、意外重新加载、CPU 和 GPU 负载、时钟频率、温度以及负载最高的进程。安装程序为混合使用的各种面板提供相同的设置和更新流程，而内置启动器和屏幕导航则让没有硬件按键的面板也能方便使用。

<picture>
  <source media="(prefers-color-scheme: light)" srcset="https://assets.ha-paneld.com/docs/screenshot/hero-light-a17f5f14.webp">
  <img src="https://assets.ha-paneld.com/docs/screenshot/hero-dark-aeb93099.webp" alt="显示实时面板状态、性能和显示控件的 ha-paneld 仪表盘">
</picture>

<details>
<summary><strong>更多屏幕截图</strong></summary>

| 仪表盘 | 配置 |
|---|---|
| <a href="../img/ui-dashboard-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-dashboard-light.png"><img src="../img/ui-dashboard-dark.png" alt="仪表盘选项卡" width="420"></picture></a> | <a href="../img/ui-configure-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-configure-light.png"><img src="../img/ui-configure-dark.png" alt="配置选项卡" width="420"></picture></a> |

| 实体 | 安装 |
|---|---|
| <a href="../img/ui-entities-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-entities-light.png"><img src="../img/ui-entities-dark.png" alt="实体选项卡" width="420"></picture></a> | <a href="../img/ui-install-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-install-light.png"><img src="../img/ui-install-dark.png" alt="安装选项卡" width="420"></picture></a> |

| 配置文件 | 日志 |
|---|---|
| <a href="../img/ui-profile-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-profile-light.png"><img src="../img/ui-profile-dark.png" alt="配置文件选项卡" width="420"></picture></a> | <a href="../img/ui-logs-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-logs-light.png"><img src="../img/ui-logs-dark.png" alt="日志标签页" width="420"></picture></a> |

| 待机屏幕 | REST API 探索器 |
|---|---|
| <img src="../img/standing-screen.png" alt="显示配置地址和二维码的 ha-paneld 待机屏幕" width="420"> | <picture><source media="(prefers-color-scheme: light)" srcset="../img/api-explorer-light.png"><img src="../img/api-explorer-dark.png" alt="REST API 探索器" width="420"></picture> |

</details>

## 安装

如果不确定 ha-paneld 能否在您的面板上运行，请在安装前查看 [面板和支持状态](#面板和支持状态) 。

首先让 ADB 可通过网络使用。在某些面板上，可在开发者选项中进行此设置；其他面板则需要通过 USB 连接一次并运行 `adb tcpip 5555`。 [配置指南](../provisioning.md) 和针对具体型号的 [硬件指南](../hardware/) 介绍了可用的方法。然后，在同一网络中装有 `adb` 的计算机上运行以下命令：

```sh
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash
```

> [!IMPORTANT]
> **在 Windows 上，请使用 Git Bash 或 WSL，而不要使用 PowerShell。** 安装程序是一个 `bash` 脚本。Git Bash 随 [Git for Windows](https://gitforwindows.org/)一起提供。安装 `adb` 时使用 `winget install Google.PlatformTools`，重新打开 shell，然后运行该命令。macOS 和 Linux 可以按原样运行它。

您无需克隆存储库或提供任何选项。安装程序会检查 `adb` 和 `curl` 是否可用，询问面板地址，并在进行每项更改前予以说明。它会下载最新的已签名稳定版本，进行安装，并检查 ha-paneld 是否已正确启动。

如果必需步骤失败，安装程序会指出问题并退出，且不会声称安装成功。修正问题后，再次运行同一命令。

> [!IMPORTANT]
> **首次加载仪表盘前，请检查 Home Assistant 和面板的系统 WebView。** 内置渲染器要求 Home Assistant 2026.4.2 或更高版本以及现代 WebView。即使是新面板，其中的 WebView 也可能过旧，无法显示当前的仪表盘。请参阅 [内置渲染器要求](../built-in-renderer.md#requirements-and-compatibility) 和 [更新系统 WebView](../hardware/README.md#updating-the-system-webview)。

要跟踪最新发布的版本（包括候选发布版本），请添加 `--prerelease`。如有更新的稳定版本，仍会优先使用该稳定版本：

```sh
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --prerelease
```

同一安装程序还支持无人值守的单面板配置。有关脚本化安装、USB 引导、无网络 ADB 的面板以及整个设备群的更新，请参阅 [配置和设备群更新](../provisioning.md) 。

ha-paneld 不通过 Google Play 分发，因此安装始终需要旁加载。对于本来可以访问 Play Store 的较新面板也是如此。

### 其他安装方式

- **面板上的 F-Droid：** 添加 [ha-paneld 的 F-Droid 存储库](../fdroid.md) ，无需计算机即可安装和更新稳定版本。有更新可用时，F-Droid 会通知您，并允许您在面板上安装；不包括候选发布版本。Sonoff NSPanel Pro 固件 4.0.0 及更高版本包含 F-Droid。此方式会安装应用，但需要 root 的功能仍需执行常规配置步骤。
- **手动旁加载或 USB 引导：** 使用 [最新版本](https://github.com/maxlyth/ha-paneld/releases) 中的 APK，并按照 [配置和设备群更新](../provisioning.md) 完成其余权限授予和设置。

## 选择仪表盘的运行方式

需要筛选仪表盘实体时，请使用内置渲染器。它还支持从其他浏览器登录、选择特定的仪表盘标签页，以及更快地启动和恢复。应用重启后，它可以重新打开上次验证的账户默认仪表盘，同时在后台刷新 Home Assistant 的仪表盘列表。

官方的 [Home Assistant Companion 应用](https://github.com/home-assistant/android) 也受支持。当面板需要连接多个 Home Assistant 服务器、使用 Assist 语音控制或原生通知时，请使用该应用。在没有 Google Play 且支持相应安装方式的面板上，请使用 ha-paneld 的“安装”选项卡。选择器会应用该面板的兼容性限制，而不会假定最新的 Companion 版本能够在其上运行。

这两种选择仍均受支持。仪表盘实体筛选仅适用于 ha-paneld 的内置渲染器。

<a id="panels-and-support-status"></a>

## 面板和支持状态

ha-paneld 无需作为系统应用安装。在兼容的面板上，亮度、导航和 TTS 等基本 Android 控制功能可以正常使用。LED、继电器、真正关闭屏幕以及某些传感器需要该型号在其 [面板配置文件](../profiles/README.md)中获得支持。硬件按钮事件需要通过 Android 无障碍功能捕获，或使用经过验证的配置文件方法。

| 面板 | 状态 | Android / ABI | 备注 |
|---|---|---|---|
| Sonoff NSPanel Pro / Pro 120 | 受支持 | Android 8.1, arm64-v8a | PX30 / rk3326-S；原厂固件提供 root ADB，正常配置流程会安装 ha-paneld 经过身份验证的 root 辅助程序 |
| Tuya TPA10 | 受支持 | Android 11, armeabi-v7a | 采用 32 位用户空间的 rk3566 |
| Electron WF1589T | 受支持 | Android 14, arm64-v8a | rk3576 userdebug 固件； `adb root`、原生 Android 导航栏和 RGB LED 控制 |
| ZHICAI SMT1019 | 经社区测试，部分功能仍处于实验阶段 | Android 14, arm64-v8a | rk3576；原厂固件没有应用可访问的 root 权限。安装经过身份验证的辅助程序后，它可以提供额外的硬件访问权限。气候功能的准确性和接近感应支持仍需进行更多硬件测试。 [问题 #8](https://github.com/maxlyth/ha-paneld/issues/8) |
| ZX-SMT156 / RK3566_T | 初步支持 | Android 13, arm64-v8a | RGB LED 和光线/接近感应功能无需 root 权限即可使用。气候功能支持是可选的；继电器和 root 访问权限仍在评估中。 [问题 #24](https://github.com/maxlyth/ha-paneld/issues/24) |
| Smatek S9E | 实验性支持 | Android 11, arm64-v8a | 适用于板载继电器、按钮 LED 和接近感应功能的配置文件。仍需在 S9E 硬件上进行实机确认。 |
| Shelly Wall Display（原版） | 原厂软件不兼容 | Android 7.0, armeabi-v7a | Android 版本低于 ha-paneld 的最低要求。 |
| Shelly Wall Display X2 | 仅供研究 | Android 8.1, armeabi-v7a | 尚无已确认的 ha-paneld 安装途径。 |
| Shelly Wall Display X1i / X2i / XL | 仅供研究 | Android 11, arm64-v8a | 仍需按型号拆分配置文件元数据。尚无已确认的 ha-paneld 安装路径。 |

有关特定型号的设置、已知限制和逆向工程获得的硬件详细信息，请参阅 [硬件文档](../hardware/) 。

## 硬件控制功能

每个面板仅发布其配置文件和检测到的硬件所支持的控制项。它们的名称和行为在各型号之间保持一致。

| 功能 | Home Assistant 或 API 控制 |
|---|---|
| 屏幕亮度 | `light.<panel>_screen` 亮度 |
| 屏幕开/关 | `light.<panel>_screen` 开/关；配置文件支持时可真正关闭屏幕，否则会安全地调暗亮度 |
| RGB LED | `light.<panel>_led` 适用于配备受支持 LED 硬件的面板 |
| 硬件按钮 | `event.<panel>_button` 当 Android 无障碍功能捕获或经验证的配置文件方法可用时 |
| 环境光和接近感应 | `sensor.<panel>_illuminance`、`binary_sensor.<panel>_proximity`，以及归一化的 `sensor.<panel>_proximity_level`，范围从 0（远）到 100（近） |
| 自适应亮度 | 可选择根据面板的光线传感器或 Home Assistant 照度实体进行为期七天的学习 |
| 打开 URL | `text.<panel>_navigate` |
| Dashboard 控制和重启 | Home Assistant 按钮，以及远程 Controls 面板中的 Dashboard、Reload 和导航操作 |
| TTS 和公告音频 | `POST /play` 和 `number.<panel>_volume`；请参阅 [TTS 指南](../tts.md) |
| Dashboard 屏幕截图和远程点按 | 支持屏幕截图方法的面板可以在 Dashboard 选项卡中显示和刷新屏幕；宽松模式还允许将点按操作发送回面板 |
| 面板信息和配置 | 打开 `http://<panel>:8888/`，也可以通过 Home Assistant 设备页面上的 **Visit** 链接打开 |

Home Assistant 通过 MQTT 发现这些控制项，无需 YAML。主要实体系列、HTTP API 和配对详情请参阅 [docs/api.md](../api.md)。你也可以在面板上的 `http://<panel>:8888/api`浏览和试用 HTTP API。

## 安全性和 root 访问权限

### 强化安全模式

宽松模式是默认模式，适用于可信的家庭网络。当可信度较低的设备共用该网络时，请使用 [强化安全模式](../security-mode.md) 。强化安全模式要求能够实际接触面板。必须有人在面板屏幕上批准高影响的远程操作；无法远程批准这些操作。屏幕截图仍可查看，但远程点按会被禁用。必须在每个面板上分别启用此设置，且备份、恢复或设备群预配不会复制此设置。

### 需要 root 的功能

普通 Android 应用无法访问某些面板硬件，因此需要 root 访问权限。root 是否可用取决于面板固件，而非 ha-paneld。某些面板提供 `su`；在其他面板上，安装程序可以添加 ha-paneld 的小型 root 辅助程序。该辅助程序不提供通用 shell 或不受限制的文件访问权限。

Web 界面会用锁标记不可用的控制项，并说明面板缺少什么。安装程序和诊断信息也会报告可用的访问权限级别。

**无需 root：** Home Assistant 配对、屏幕亮度和调暗、音频公告、两种 Dashboard 选择、Web 界面、REST API，以及配置备份和恢复。Back、Recents、挥手唤醒和软件导航栏取决于相应的 Android 或传感器功能，但本身并不需要 root。

**可能需要 root 或经过身份验证的辅助程序：** 物理关闭背光、配置文件选择的 Android 休眠、某些面板上的 RGB LED 控制、厂商应用控制、重启和 CPU 调速器。如果当前配置文件没有安全地完全关闭屏幕的方法，ha-paneld 会改为将其调暗。

**ha-paneld 内部仍需直接使用 `su` ：** 将 Android 锁定到 Dashboard、完整的系统日志、配置文件要求的继电器控制，以及旧版 Companion 会话导入路径。完整备份可以包含现有的 Companion 登录信息，该操作始终通过经过身份验证的辅助程序完成：即使在直接使用 root 的面板上，受描述符限制的协议也是唯一途径。

对于确实未取得 root 权限的面板，有一种受限的 [高级后备方案](../provisioning.md#shizuku-fallback-for-unrooted-panels) ，但它不属于常规的受支持硬件路径，也不提供仅限 root 权限的硬件功能。

## 指南和参考资料

### 使用 ha-paneld

- [配置部署和设备群更新](../provisioning.md)：无人值守安装、USB 和网络 ADB 设置、备份以及整个设备群的更新。
- [内置渲染器](../built-in-renderer.md)：要求、远程登录、仪表盘选择、恢复和有意设置的限制。
- [性能](../performance.md)：查明仪表盘运行缓慢的原因，并衡量实体筛选的效果。
- [自适应亮度](../adaptive-brightness.md)：选择光源、了解学习机制，以及移动面板后重置历史记录。
- [自适应接近感应和挥手唤醒](../adaptive-proximity.md)：配置接近检测并示教唤醒手势。
- [安全模式](../security-mode.md)：了解宽松模式和强化安全模式，包括哪些操作要求有人在面板旁。
- [TTS](../tts.md)：使用 Home Assistant TTS 引擎生成语音并将其发送到面板。

### 开发和扩展 ha-paneld

- [HTTP、MQTT 和 Home Assistant API](../api.md)：HTTP 端点、主要 MQTT 实体系列、配对和发现。机器可读的规范可通过面板上的 `/api/v1/openapi.json`获取。
- [面板配置文件](../profiles/)：无需重新构建应用，即可为其他面板创建、测试和共享支持。
- [硬件参考资料](../hardware/)：特定型号的设置、传感器、控制项、固件和逆向工程说明。
- [从源代码构建](../building.md) 和 [本地开发](../local-builds.md)：使用 Docker、开发容器或本地 Android 工具链进行构建。
- [路线图](../roadmap.md)：计划中的工作。已完成的工作记录在 [变更日志](../../CHANGELOG.md)中。

面板的 `GET /diag` 页面会生成用于错误报告的硬件、固件和功能报告。公开发布前，请检查并隐去其中的敏感信息。

## 其他信息亭应用

### Fully Kiosk

我不建议同时运行 [Fully Kiosk Browser](https://www.fully-kiosk.com/) 和 ha-paneld。两者都会尝试管理屏幕、信息亭行为和远程控制，导致需要在两个位置配置同一个面板。

<details>
<summary>为什么我不建议同时运行两者</summary>

- Fully Kiosk 是闭源商业软件。其远程管理功能要求 [为每台设备购买付费许可证](https://license.fully-kiosk.com/license/single)。
- 实体筛选是 ha-paneld 内置渲染器的一部分，因此单独的浏览器无法使用此功能。
- Fully Kiosk 需要在每台设备上单独配置。当多个不同品牌的面板需要保持一致的行为时，这会很麻烦。

面板上只使用一个仪表盘应用：ha-paneld 的内置渲染器、Companion，或者在其能提供前两者所不具备的功能时使用单独的自助终端浏览器。

</details>

### FreeKiosk

[FreeKiosk](https://github.com/RushB-fr/freekiosk) 虽然名称相似，但与 ha-paneld 无关。它免费且开源，但使用 React Native，因此会在 Home Assistant 仪表盘之外同时运行另一个 JavaScript 引擎。对于只有 1–2 GB RAM 的面板，这项额外负载可能相当显著。

## 社区聊天

我不想为一个单人项目搭建 Discord 服务器或 Slack 工作区，因此正在尝试使用 Matrix 和 Element。你可以在常用的 Matrix 客户端中加入 [#ha-paneld:matrix.org](https://matrix.to/#/#ha-paneld:matrix.org) ，也可以在 [Element Web](https://app.element.io/#/room/#ha-paneld:matrix.org)中无需账户直接查看。

除非你能接受配置或文件链接永久公开，否则不要将其发布到 GitHub issue 或讨论中。Matrix 房间也是公开的，任何人都可以阅读，但 Matrix 还支持私信，可用于提供不应成为永久公开记录的支持详情。在任何地方发布配置、日志或文件链接之前，请隐去凭据、私有 URL 和个人信息。

## 想让你的面板获得支持？

ha-paneld 没有捐赠按钮。它是免费的，而真正能推动项目发展的“回报”是支持更多面板。这需要用实际硬件进行研究。

请先阅读 [运行时配置文件指南](../profiles/README.md)。Generic 配置文件可以生成一个被动式草案，你无需构建应用即可对其进行验证、测试和分享。在配置文件能够随 ha-paneld 一起提供之前，我仍然需要来自真实设备的证据，尤其是关于按钮、LED、继电器和传感器的证据。

如果你愿意提供帮助：

- **创建并分享配置文件。** 打开 `http://<panel-ip>:8888/profiles`，下载 Generic 设备草案，然后按照 [测试](../profiles/testing.md) 和 [分享](../profiles/sharing.md) 指南操作。社区配置文件即使尚未达到随 ha-paneld 发布的要求，也可以发挥作用。
- **使用面板的诊断信息创建 issue。** 访问 `http://<panel-ip>:8888/diag`，检查报告并隐去敏感信息，然后将其粘贴到新的 issue 中。这些信息足以开始处理。对于任何需要有人在面板旁操作的按钮、LED、继电器或传感器，我会与你一起完成一组简短的测试。
- **把面板寄给我。** 我在英国，很乐意直接进行逆向工程。这是让硬件获得完整支持的最快途径。之后我会将面板寄还给你（我已经有太多面板了）；请先创建 issue，以便我们安排具体事宜。

成果始终是开放的：你的面板会成为所有人都能使用的配置文件。这就是捐赠。

## 开发

如果你想参与 ha-paneld 本身的开发，请先阅读 [CONTRIBUTING.md](../../CONTRIBUTING.md)。开发者文档涵盖 [从源代码构建](../building.md)、 [本地构建和开发容器构建](../local-builds.md)、 [HTTP 和 MQTT API](../api.md)、 [面板配置文件开发](../profiles/README.md)、 [浏览器测试工具](../../test/README.md)以及 [发布流程](../RELEASING.md)。

我特意提供了足够的信息，以便使用随附的开发容器并构建本地测试版本。请勿原样提交计算机生成的拉取请求或议题：请阅读并理解建议文本和代码的每个部分，然后用自己的语言重写。这是一个由我独自维护的项目，我没有时间审查未经筛选的计算机生成内容。请简明扼要，并以人类读者为对象；如果对某件事没有把握，请先询问。

<details>
<summary><strong>技术栈</strong></summary>

- **应用：** [Kotlin](https://github.com/JetBrains/kotlin)、 [AndroidX](https://github.com/androidx/androidx) 和 [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)。
- **HTTP 和 Home Assistant WebSocket：** [Ktor](https://github.com/ktorio/ktor) CIO 服务器、客户端和 WebSocket 模块。
- **MQTT：** [HiveMQ MQTT Client](https://github.com/hivemq/hivemq-mqtt-client)，使用其 MQTT 5 客户端和纯 Java NIO 传输层。
- **mDNS：** [JmDNS](https://github.com/jmdns/jmdns)，发布 `_ha-paneld._tcp` ，以便 ha-paneld 实例能够相互发现，供多面板切换器使用。ha-paneld 会在该发布停止且无法恢复时报告此情况。
- **运行时配置文件：** [SnakeYAML Engine](https://github.com/snakeyaml/snakeyaml-engine) 处理 YAML 1.2，并在配置文件编辑器中使用 [CodeMirror](https://codemirror.net/) 及其 [YAML 语言包](https://github.com/codemirror/lang-yaml) 。
- **二维码和日志：** [ZXing](https://github.com/zxing/zxing) 用于设置流程的二维码， [SLF4J](https://github.com/qos-ch/slf4j) 用于通过 Logcat 记录 Ktor 和 HiveMQ 日志。

依赖项的选择和更新遵循项目的 [依赖项和供应链政策](../../SECURITY.md#dependency-and-supply-chain-policy)。

</details>

## 翻译

翻译通过自动化方式生成并交叉检查。翻译尚未由各语言的使用者进行系统审阅，因此英文文本仍为权威版本。如果措辞不清楚或不正确，请 [提交翻译更正议题](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml)。

## 致谢

感谢 **Seaky** 开发的 [NSPanel Pro Tools](https://github.com/seaky/nspanel_pro_tools_apk)，它是启发我开始开发 ha-paneld 的项目之一。ha-paneld 并非 NSPanel Pro Tools 的开源重实现。它已发展为覆盖范围广得多的墙装面板平台，拥有自己的仪表盘渲染器、实体筛选、运行时硬件配置文件、诊断功能，并支持对多个品牌的面板进行配置。现在，这两个项目的功能集差异很大，不应将它们视为可以互换，即使是在 Sonoff 面板上也是如此。

## 许可证

Apache-2.0。请参阅 [LICENSE](../../LICENSE)。
