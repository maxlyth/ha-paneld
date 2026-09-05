> [!IMPORTANT]
> 本文档由机器生成并经过自动交叉核验，但尚未由中文使用者进行系统审阅。英文文档为权威版本。[阅读英文原文](../provisioning.md)，或[创建翻译更正议题](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml)。

# 配置与设备群更新

无需检出仓库，可下载的安装程序便能通过 adb 配置一台面板。它会安装或更新 ha-paneld、应用请求的设置、授予可通过 adb 授予的 Android 权限，并在完成前验证应用是否正在运行。本页先介绍常规的单面板操作流程，再介绍共享设置和整个设备群的更新。

有关最简短的交互式安装流程，请参阅 [README](README.md#安装)。有关这些命令背后的数据库、软件包和辅助程序防护措施，请参阅[配置安全与恢复](../provisioning-safety.md)。

> [!NOTE]
> 这些是 `bash` 和 `adb` 命令。在 **Windows** 上，请在 **Git Bash**（来自 [Git for Windows](https://gitforwindows.org/)）或 **WSL** 中运行，而不要使用 PowerShell，并确保 `adb` 位于 `PATH` 中（`winget install Google.PlatformTools`）。在 macOS 和 Linux 上可按原样运行这些命令。

## 安装或更新一台面板

将示例地址替换为面板地址，然后把完整命令粘贴到 Git Bash、WSL、macOS Terminal 或 Linux 终端中：

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555
```

The installer downloads and authenticates the matching signed release and provisioner. It connects to the panel, installs the ABI-matched root helper where the firmware permits it, installs the APK, grants the required permissions, starts ha-paneld and finishes with a self-check. The running app then reports any guidance derived from the active hardware profile and live panel state.

Provisioning is idempotent. If a step is interrupted or fails, correct the problem and run the same command again. Optional or manual recommendations remain visible without turning a successful installation into a failure.

### 设置面板标识和连接

在地址后添加配置选项。此示例会指定面板 ID 和 MQTT broker：

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --id kitchen --mqtt tcp://192.168.1.10:1883
```

请使用 `--prerelease`，并将其置于 `--provision` 之前，以跟踪最新发布的版本，包括候选版本。较新的稳定版本仍会优先选用。如果不使用此选项，安装程序将仅跟踪稳定版本。

常用选项包括 `--force`、`--builtin`、`--ha-url`、`--ha-token-file`、`--ha-user`、`--ha-pass-file`、`--home-dashboard` 和 `--entity-filter`。使用 `--help` 运行安装程序可查看用法和高级入口点。检出的源代码还提供完整的 `scripts/provision.sh --help` 参考信息。

### Keep credentials out of the command line

Pass credentials through owner-only files so they do not appear in shell history or child-process command lines. Each file must contain one credential line. A trailing line ending is accepted, but embedded line breaks are rejected.

Create a Home Assistant password file without echoing the password:

```bash
umask 077
read -rsp "Home Assistant password: " HA_PASSWORD; echo
printf '%s' "$HA_PASSWORD" > ha-password.txt
unset HA_PASSWORD
```

Use `--ha-pass-file ha-password.txt`, `--ha-token-file ha-token.txt` or `--mqtt-pass-file mqtt-password.txt`, then remove the file when provisioning finishes. The older `--ha-pass`, `--ha-token` and `--mqtt-pass` value flags remain available for compatibility, but their values are visible in the original shell command and process list. Do not use them on a shared computer.

These file options protect the credential on the computer running the installer. They do not encrypt the panel's management API. ha-paneld uses `http://<panel>:8888` on a trusted LAN, so MQTT credentials and Home Assistant tokens cross that connection as cleartext HTTP. Provision only from a trusted, segmented network. The Home Assistant password is sent from this computer to Home Assistant and never to the panel; use an `https://` Home Assistant URL so the login is encrypted in transit.

### 检查或导出现有安装

These read-only operations do not download or install an APK:

```bash
# Export secret-inclusive settings.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --export panel-config.json

# Check the existing installation without changing it.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --verify
```

Protect an exported config like a credential. It contains settings and secrets, but it is not a complete panel backup.

## 配置内置仪表盘渲染器

The easiest setup is on the panel's `:8888` **Configure** page. Under **Home Assistant connection**, enter the Home Assistant URL and choose **Browser sign-in**. Open the short-lived link in an administrator's browser and complete the sign-in, then select **Built-in renderer** in the Dashboard card. A long-lived access token remains available for automated or compatibility setup, but it is not needed for the normal interactive journey.

内置渲染器需要 Home Assistant 2026.4.2 或更高版本，以及兼容的最新 Android System WebView。请参阅[渲染器要求和外观控件](../built-in-renderer.md#requirements-and-compatibility)。

For unattended provisioning, `--builtin` selects the renderer and signs in to Home Assistant from this computer, so nothing is typed on the panel:

```bash
# Username/password: the login happens here and mints a revocable refresh token.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.50:5555 --builtin \
  --ha-url https://homeassistant.example.com --ha-user your-user --ha-pass-file ha-password.txt

# Or use a long-lived access token instead of a login.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.50:5555 --builtin \
  --ha-url https://homeassistant.example.com --ha-token-file ha-token.txt
```

### 选择仪表盘和实体筛选器

无人值守安装无法回答引导式设置中的问题。默认情况下，面板会打开 Home Assistant 账户的默认仪表盘。对于大型账户，这通常是可用仪表盘中最慢的一个，旧款面板可能需要很长时间才能将其绘制出来。`--home-dashboard` 和 `--entity-filter`会在首次渲染前回答这两个问题：

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.50:5555 --builtin \
  --ha-url https://homeassistant.example.com --ha-user your-user --ha-pass-file ha-password.txt \
  --home-dashboard /panel-dashboard/kitchen --entity-filter on
```

`--home-dashboard`接受仪表盘、特定的仪表盘选项卡（例如 `/panel-dashboard/kitchen`），或使用 `auto` 以遵循账户默认设置。`--entity-filter`仅接受 `on` 或 `off`。启用后，它会将 Home Assistant 的状态流限制为仪表盘所用的实体，这可能会为旧款面板带来最大的改善。这两个选项都适用于 ha-paneld 的内置渲染器，因此同一命令中需要包含 `--builtin`，或面板已在使用该渲染器。

提供任一选项也会回答相应的引导式设置问题。之后在面板上所做的更改优先，并且命令中指定的选项优先于同一命令中的 `--restore` 包。如果 Home Assistant 当前未列出指定的仪表盘，安装程序会保存它并通知你；这样便可在预期的仪表盘存在之前配置面板。Home Assistant 永远无法解析的路径会被拒绝，因此引导式设置仍会询问。

对于交互式安装，请省略 `--builtin` 和 Home Assistant 凭据参数。安装程序会显示面板正在等待的步骤地址，通常为 `http://<panel>:8888/setup`。你可以从计算机或手机在该地址继续，也可以在面板上点按**初始设置**。两种方式采用相同的流程。已导入 Companion 会话的现有面板会保留该登录方式作为兼容路径。

要返回 Companion 应用，请在“设置”选项卡中选择已安装的 Home Assistant Companion 软件包作为仪表盘应用。内置渲染器不提供 Voice Assistant 或通知，因此需要这些功能时请保留 Companion。

## 重新开始设置面板

`--reset-config` erases ha-paneld's data and starts guided setup as a genuine first run:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --reset-config
```

**Reset is irreversible and makes no backup.** If you may need the fullest supported recovery, stop and use the separate **Install → Backup** operation on the panel's `:8888` page. Verify that the downloaded `.hpb` is non-empty before continuing. Use `--export FILE` as a separate command first only when a settings-only export is sufficient.

Reset removes settings, learned entity data, proximity and ambient history, and the panel's on-panel revision history. It does not remove the app, root helper or any other app on the panel. The command asks you to type `RESET`; `--force` does not bypass that confirmation. Set `HAPANELD_RESET_CONFIRM=RESET` only when an unattended reset is deliberate.

Fleet updates refuse `--reset-config`. Reset panels one at a time.

The CLI `--restore FILE` and `--restore-fleet FILE` options import a config JSON export and require Python 3 on the computer running the installer. It does not accept an `.hpb` backup. Restore an `.hpb` through **Install → Restore** on the same panel page.

有关配置导出、受支持的 `.hpb` 备份与自动创建的紧急数据库副本之间的区别，请参阅[配置安全与恢复](../provisioning-safety.md#backups-and-recovery)。

## 向设备群部署共享设置

Use `--restore-fleet` when several panels should share the same portable settings. First export the configured source panel:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --export fleet-config.json
```

The export contains secrets, so store it like a credential. On each target, restore the portable settings and supply that panel's identity and credentials explicitly:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.51:5555 \
  --id kitchen --restore-fleet fleet-config.json \
  --mqtt tcp://192.168.1.10:1883 --mqtt-user ha-paneld --mqtt-pass-file mqtt-password.txt \
  --builtin --ha-url https://homeassistant.example.com --ha-token-file ha-token.txt
```

Repeat the command for each target, changing its address and `--id`. Add `--prerelease` before `--provision` when the panels should follow the newest published release, including a current release candidate.

`--restore-fleet` applies only **PORTABLE, non-secret** settings. It leaves the panel ID, other device-specific settings and every credential unchanged. If MQTT reports `auth-failed` after a bundle-only restore, check that `--mqtt-user` and `--mqtt-pass-file` were supplied. That result does not by itself mean the broker is down.

## 更新整个设备群

Whole-fleet updates currently require a source checkout. From the repository root, run [`scripts/update-fleet.sh`](../../scripts/update-fleet.sh) with the panel addresses. The script downloads the release once, authenticates one APK before any worker starts, then installs, launches and verifies each panel:

<!-- source-checkout-only -->
```bash
scripts/update-fleet.sh --latest -- 192.168.1.10 192.168.1.11:5555

# At most four panels run at once by default. Set a smaller bounded pool when required.
scripts/update-fleet.sh --jobs 2 --latest -- 192.168.1.10 192.168.1.11:5555

# Follow the newest published release, whether stable or a release candidate.
scripts/update-fleet.sh --prerelease -- 192.168.1.10 192.168.1.11:5555

# A host list can also come from standard input.
printf '%s\n' 192.168.1.10 192.168.1.11 | scripts/update-fleet.sh --latest
```

Four panels run concurrently by default. Set `--jobs 1..32` to change the bounded pool, or use `HAPANELD_FLEET_JOBS` as the default when `--jobs` is omitted. The command-line option takes precedence.

Each worker prints the panel's provisioning guidance, but fleet updates never accept hardware-profile recommendations automatically. Options that describe one panel are refused before any worker starts. Run `--reset-config`, `--export FILE`, `--id` and device-specific `--restore FILE` through `scripts/provision.sh` one panel at a time. `--restore-fleet FILE` is the supported way to apply portable settings across several panels.

Fleet updates require Android SDK Build-Tools containing `apksigner` and either `aapt` or `aapt2`. The wrapper checks the selected APK before the workers start, and each panel's provisioner verifies its input again before changing the panel. The [technical provisioning page](../provisioning-safety.md#fleet-update-boundaries) records the signer rules and remaining fleet safeguards.

## 例外访问方式和安全模式

Hardware-profile recommendations are report-only. Choosing a profile is not consent to disable packages, persist ADB, install privileged software or change display settings. The old `--no-tame` option remains as a compatibility no-op. Packages already present in the configured tame blocklist still reapply at boot.

### 未 root 面板的 Shizuku 回退方案

[Shizuku](https://shizuku.rikka.app/) is a separate open-source app whose service runs with Android's shell identity (UID 2000). ha-paneld can use it as a last resort on a genuinely unrooted panel whose profile names a concrete supported use. Shell is not root: operations that need genuine root still fail closed, and none of the root-only hardware features become available. Do not set it up on a panel that already has working `su` or the root helper.

`provision.sh --shizuku` downloads the curated Shizuku Manager, verifies its exact checksum and starts the service, but it cannot approve ha-paneld. Approval happens on the panel, in **Configure → toolbar overflow → Enhanced access → Enable**, and has no remote path through the installer, the web UI, MQTT, a backup restore or a fleet push. The consent is stored only on the panel and is never exported or restored; replacing the Manager, revoking the permission or stopping the service makes the dependent operations fail closed. A service started through ADB normally needs to be started again after a reboot.

[Hardened security mode](../security-mode.md) requires physical access for selected high-impact remote actions. Someone must approve them on the panel's screen, and they cannot be approved remotely. It is enabled only from the panel and is not copied by provisioning or a fleet update. Network ADB cannot coexist with Hardened security mode, so return the panel to Relaxed mode locally before an ADB-based installation or fleet update.

## 配置本地构建版本

此开发者工作流需要检出源代码。请先从存储库根目录构建 APK，然后将该文件提供给配置程序：

<!-- source-checkout-only -->
```bash
./gradlew :app:assembleDebug
scripts/provision.sh <panel-ip:5555> \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --allow-unsigned-helper
```

`--allow-unsigned-helper` acknowledges that the helper embedded in a local APK is controlled by the local builder instead of authenticated as a published release. It is required whenever a local APK is sent to a panel with a usable root or helper route, including the first helper installation. A genuinely unrooted panel skips helper work. Official `--latest` and `--prerelease` installs authenticate the release helper automatically and do not use this flag.

Android SDK Build-Tools containing `apksigner` are required to update a panel that already has ha-paneld installed, whatever the APK source, because the provisioner compares the installed and candidate signers before it changes anything. A first installation on a panel without ha-paneld does not need them. Local `--apk` provisioning additionally needs either `aapt` or `aapt2`. Before any upgrade backup or panel change, the provisioner verifies the package and exactly one valid signer. Self-built APKs may use the builder's consistent signing key. Add `--require-release-signer` only when the local file should carry the official release certificate.

可识别配置文件的方案会报告所选驱动程序何时需要 helper。许多 rk3576 和 PX30 面板可在应用内运行 `su`，而受沙盒限制的已 root 面板则使用 helper 执行特权操作。真正未 root 的面板会继续使用标准 Android 功能，除非其配置文件针对某一项明确功能声明了单独记录的替代方案。

## 引导启用 adb

On some Tuya TPA10 and Smatek panels, adb initially works only over USB. Connect the USB cable, then enable network adb before running the normal provisioning command:

```bash
adb devices                   # accept the on-screen RSA prompt if shown
adb root                      # if this firmware supports it
adb tcpip 5555                # expose adb on the network; this resets on reboot
adb connect 192.168.1.50:5555 # replace this address with the panel's address
```

If a panel has no adb but does have a browser or file manager, download the [release APK](https://github.com/maxlyth/ha-paneld/releases/latest), allow installation from that app and tap the APK. Grant the required permissions by hand under Android Settings, then follow ha-paneld's setup screen. This route is not available on a locked-down panel with no browser or file manager.

## Android 权限

| 权限 | 用途 | 配置授予方式 |
|------------|----------|--------------------|
| `POST_NOTIFICATIONS` | 前台服务通知 | 运行时权限或 `pm grant` |
| `WRITE_SETTINGS` | 屏幕亮度 | `appops set <pkg> WRITE_SETTINGS allow` |
| `SYSTEM_ALERT_WINDOW` | 软件导航栏 | `appops set <pkg> SYSTEM_ALERT_WINDOW allow` |
| 无障碍按键捕获 | 硬件按钮事件 | `settings put secure enabled_accessibility_services …` |

Screen-off does not use Android device administrator. The active hardware profile may power off a physical backlight, send Android sleep and wake key events, or fall back to brightness zero. The profile route determines whether root, the authenticated helper or standard Android access is required. Builds up to 0.5.0 included an optional device administrator; 0.5.1 removed it. See the [build and signing notes](../local-builds.md) if you are upgrading from a build where it was enabled.
