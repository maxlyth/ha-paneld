# Provisioning & fleet updates

Unattended setup for rooted or userdebug panels grants permissions over adb without per-device tap-through. For the quick single-panel install see the [README](../README.md#install); this page covers the scriptable path and rolling a whole fleet.

> [!NOTE]
> These are `bash` + `adb` commands. On **Windows**, run them in **Git Bash** (from [Git for Windows](https://gitforwindows.org/)) or **WSL** — not PowerShell — with `adb` on `PATH` (`winget install Google.PlatformTools`). macOS and Linux run them as-is.

## One panel, without downloading the repository

The downloadable installer can pass provisioning options to one panel. Replace the example address with the panel's address, then paste the complete command into Git Bash, WSL, macOS Terminal or a Linux terminal:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555
```

Add the required options after the address. For example, this assigns a panel name and MQTT broker:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --id kitchen --mqtt tcp://192.168.1.10:1883
```

Use `--prerelease` before `--provision` to install the newest release candidate instead of the latest stable release. Other routine provisioning options include `--force`, `--builtin`, `--ha-url`, `--ha-token-file`, `--ha-user` and `--ha-pass-file`.

Pass credentials through owner-only files so they do not appear in shell history or get copied into child-process command lines. The file must contain one credential line; a conventional trailing line ending is accepted, but embedded line breaks are rejected. For example, create a Home Assistant password file without echoing the password:

```bash
umask 077
read -rsp "Home Assistant password: " HA_PASSWORD; echo
printf '%s' "$HA_PASSWORD" > ha-password.txt
unset HA_PASSWORD
```

Use `--ha-pass-file ha-password.txt`, `--ha-token-file ha-token.txt`, or `--mqtt-pass-file mqtt-password.txt` as appropriate, then remove the file when provisioning finishes. The older `--ha-pass`, `--ha-token` and `--mqtt-pass` value flags remain accepted for compatibility, but their literal values are visible in the original shell command and process list and should not be used on a shared computer.

These file options protect the credential on the provisioning computer; they do not add encryption to the panel's management API. ha-paneld's `http://<panel>:8888` API uses the project's trusted-LAN model, so MQTT credentials and Home Assistant tokens, whether supplied directly or minted by provisioning, cross that LAN connection as cleartext HTTP. Provision only from a trusted, segmented network. The Home Assistant password itself is sent from this computer to the configured Home Assistant login endpoint and never to the panel; use an `https://` Home Assistant URL, because an `http://` URL also sends that password without transport encryption.

The installer downloads and authenticates the matching **signed release** and provisioner. It connects to the panel, installs the ABI-matched root helper where the firmware permits it, installs the APK, grants the required permissions, starts ha-paneld and finishes with a self-check. The running app then reports guidance derived from the active hardware profile and live panel state.

Provisioning is **idempotent**, so paste the same command again after correcting an interrupted or failed step. Required helper, startup, profile activation, configuration, restore and verification failures return a nonzero result. Optional or manual recommendations remain visible without turning an otherwise successful installation into a failure.

Two read-only operations are safe to run independently:

```bash
# Secret-inclusive config/settings export. This does not download or install an APK.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --export panel-config.json

# Check the existing installation without changing it.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --verify
```

## Starting a panel over

`--reset-config` erases a panel's ha-paneld configuration so its next start is a genuine first run, then installs and hands over to guided setup:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --reset-config
```

It exports the existing configuration first and refuses to continue unless that backup was written and is non-empty, then asks you to type `RESET` before anything is erased. `--force` does not stand in for that confirmation. For an unattended reset, set `HAPANELD_RESET_CONFIRM=RESET`.

This erases more than settings: learned entity, proximity and ambient data and the panel's on-panel revision history go with them. The app itself, the root helper and every other app on the panel are untouched. Fleet updates refuse the option outright — reset panels one at a time, deliberately.

When `--export FILE` is combined with install or configuration options, the verified config export is written **before** any panel mutation. Protect that file like a credential. It is not a complete recovery backup: it omits the runtime profile catalog and selection, learned entity state and history, and Home Assistant Companion login. `--restore FILE` imports this config JSON only and requires Python 3 on the computer running the installer so the file can be validated before the panel is changed.

Before every upgrade the installer also captures the panel's database into the same owner-only directory, mode 600, next to the settings export. That file is the canonical store: configuration, the entity catalog, proximity and ambient history and the on-panel revision history all live in it, and a normal `adb install -r` preserves it. The copy matters when recovery needs an uninstall, which destroys it. The capture is a verified SQLite backup taken in one transaction on the panel itself — backed up with the panel's own `sqlite3`, integrity-checked, admitted against the schema versions the app has actually shipped, and hash-verified across the transfer — so the result is a single self-contained `.db` with no sidecar files, and a `…backup-receipt.txt` beside it records exactly what was verified and which app build wrote it. On a rooted panel this is fail-closed: if a verified capture cannot be produced, the upgrade stops before changing anything, and only the explicit `--allow-missing-db-snapshot` flag accepts proceeding without a database restore point. A panel with no root route cannot reach the database at all; it prints that only settings could be saved and continues.

Those files are named `…break-glass.db` because that is what they are. Nothing restores them automatically, and they are only valid on the same panel and the same version they came from — restoring a raw database onto a different version or a different panel is the hazard the panel's own backup format exists to avoid. Use them by hand, as a last resort. The supported restore path is the `.hpb` from the panel's Install page, which the installer cannot produce for you because creating one needs the app to be running and serving its web page — which is exactly the situation the break-glass copy is for.

Because that copy is staged on the panel before being pulled, an upgrade needs room for two copies of the database. When it can read the capacity, the installer requires 64 MB free on `/data` before downloading anything and stops with the exact numbers if the panel is short; otherwise it warns that the check could not be made and continues.

Before an uninstall or other destructive recovery, use **Install → Backup** on the panel's `:8888` page and verify the downloaded `.hpb` is non-empty. Restore that complete backup with **Install → Restore** on the same page. Do not pass an `.hpb` file to CLI `--restore`; the CLI endpoint accepts config exports, not complete backup archives.

Profile recommendations are report-only. Activating a hardware profile is not consent to disable packages, persist ADB, install privileged software or change display settings. The former `--no-tame` option remains accepted as a compatibility no-op, but recommendation-driven package taming is no longer automatic. Packages already present in the explicitly configured tame blocklist still reapply at boot.

The exceptional `--shizuku` setup remains available for a genuinely unrooted panel whose profile names a concrete supported use. Read the [advanced fallback guide](shizuku.md) before enabling it; the required approval is local to the panel and cannot be supplied by provisioning, the web UI, MQTT, backup/restore or a fleet push.

[Hardened mode](security-mode.md) is a separate, optional network-control policy. It requires physical access for selected high-impact remote actions: someone must approve them on the panel's screen, and they cannot be approved remotely. It is enabled only from the panel and is not copied by provisioning or a fleet update. When enabled, a protected HTTP export or import returns `202 approval-required`; approve it on the panel and repeat the identical command from the same computer within ten minutes. Network ADB cannot coexist with Hardened mode, so return the panel to Relaxed mode locally before an ADB-based installation or fleet update. Normal unattended provisioning retains Relaxed mode unless someone deliberately changes that panel-local setting.

**Root helper:** starting with v0.9.4, release assets include sealed `armeabi-v7a` and `arm64-v8a` helper binaries. The normal installer authenticates the selected binary with the release key, verifies it again after device staging, and atomically installs or upgrades it on every panel where vendor `su` or root ADB is available. Before replacing the APK it checks both the required protocol capabilities and the exact deterministic build identity derived from all helper source, headers and command definitions. The prior root-owned helper and service are retained until the APK installation succeeds, so an install, startup, identity or capability failure restores the previous working pair. If the ADB transport disappears while Android is installing the APK, the provisioner keeps the recovery journal rather than guessing; rerunning the same command authenticates the installed APK bytes and running helper identity, then safely commits or rolls back the interrupted upgrade. Recovery snapshots are root-owned, authenticated by their journaled digest and synchronized before live files are retired. The standalone `helper/install-daemon.sh` installer uses a separate helper-only journal; each installer refuses to overwrite the other's incomplete transaction and identifies the command that must be rerun to recover it. This applies equally to PX30, rk3576 and sandbox-walled rooted panels: the app may use direct `su` for compatible operations, while the helper supplies the fixed privileged protocol required by features such as Companion login backup/restore.

The provisioner uses a writable `/system` init service when enough space can be verified, or a verified Magisk, KernelSU or APatch `/data/adb/service.d` runner when `/system` is read-only. On panels where `/system` is writable but too full for a safe upgrade, it can instead keep the helper and recovery files under `/data/adb/hapaneld` while placing only the startup service in `/vendor/etc/init`. Once installed, this hybrid layout remains selected on later updates. If storage capacity, startup ownership or an existing transaction cannot be identified safely, provisioning stops before replacing the APK.

The APK also contains the matching helper as an in-app update migration backstop. When an older direct-su panel updates ha-paneld from its Install tab, MQTT button or automatic update setting before it is reprovisioned, first startup verifies the helper protocol and launches a root-owned `/data/local` copy if necessary. This preserves existing direct-su backup/restore capability across the transition. A sandbox-walled panel cannot safely make an old helper replace itself, so it remains fail-closed with an explicit reprovision prompt until the authenticated external provisioner installs the matching helper.

### Provisioning a local build

This is a developer workflow and **requires a source checkout**. From the repository root, build the matching local helper binaries first:

<!-- source-checkout-only -->
```bash
./helper/build.sh
scripts/provision.sh <panel-ip:5555> \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --allow-unsigned-helper
```

`--allow-unsigned-helper` is an explicit acknowledgement that the root helper embedded in a local APK is controlled by the local builder rather than authenticated as a published release. It is required whenever a local APK is provisioned to a panel with a usable root or helper path, including a first helper installation. A genuinely unrooted panel skips helper work. Official `--latest` and `--prerelease` installs continue to authenticate their release helper automatically and do not use this flag.

Local `--apk` provisioning also requires Android SDK Build-Tools containing `apksigner` and either `aapt` or `aapt2`. Before any upgrade backup or mutation, the provisioner verifies that the APK contains the ha-paneld package and exactly one valid signer. Self-built APKs may use the builder's consistent signing key; add `--require-release-signer` only when the local file is expected to carry the official ha-paneld release certificate.

The profile-aware plan reports when selected drivers require the helper. Many rk3576 / PX30 panels can also run `su` in-app, while sandbox-walled rooted panels use the helper as their privileged control path for features such as screen-off, density, CPU governor, screenshots, performance data, buttons and LEDs. A genuinely unrooted panel continues with its standard Android capabilities unless its profile declares a separately documented alternate for one exact feature.

## Provisioning the built-in dashboard renderer

Since 0.9, ha-paneld includes its own experimental dashboard renderer. In 0.9.6 it requires Home Assistant 2026.4.2+ and a compatible current Android System WebView; see the [built-in renderer requirements and the separate appearance/lock controls](built-in-renderer.md#requirements-and-compatibility). The easiest setup is on the panel's `:8888` **Configure** page: under **Home Assistant connection**, enter the Home Assistant URL and choose **Browser sign-in**. Complete the short-lived sign-in link in an administrator's browser, then select **Built-in renderer** in the Dashboard card. A long-lived access token remains available for automated or compatibility setup, but is not needed for the normal interactive flow.

For unattended provisioning, `--builtin` selects the renderer and provisions its Home Assistant sign-in **from this machine**, so nothing is typed on the panel:

```bash
# Username/password: logs in HERE and mints a revocable refresh token; the password never reaches
# the panel. Create ha-password.txt as described above, then replace the URL and user.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.50:5555 --builtin \
  --ha-url https://homeassistant.example.com --ha-user your-user --ha-pass-file ha-password.txt

# or a long-lived access token instead of a login:
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.50:5555 --builtin \
  --ha-url https://homeassistant.example.com --ha-token-file ha-token.txt
```

For an interactive installation, omit the Home Assistant credential arguments. After verification the installer prints the one thing the panel is actually waiting for, and the address to do it at — usually guided setup at `http://<panel>:8888/setup`, which walks the Home Assistant sign-in in the administrator's browser without typing credentials on the panel. You can equally tap **Set up** on the panel's own screen; both surfaces follow the same journey, so it does not matter which you use or whether you switch between them. Existing panels that previously imported a Companion session retain that login as a compatibility path.

Reverting to the Companion: set the dashboard app back to it in the Configure tab (or blank `dashboard_package`). The built-in renderer deliberately has **no Voice Assistant (Assist) and no notifications** — keep the Companion where those matter.

## Deploying shared settings to a fleet

To copy one configured panel's shared settings to other panels without downloading the repository, first export its config:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --export fleet-config.json
```

The export contains secrets, so store it like a credential. On each target, restore only its portable settings and supply that panel's identity and credentials explicitly:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.51:5555 \
  --id kitchen --restore-fleet fleet-config.json \
  --mqtt tcp://192.168.1.10:1883 --mqtt-user ha-paneld --mqtt-pass-file mqtt-password.txt \
  --builtin --ha-url https://homeassistant.example.com --ha-token-file ha-token.txt
```

Repeat the restore command for each target, changing its address and `--id`. Add `--prerelease` before `--provision` when testing the current release candidate.

`--restore-fleet` applies only **PORTABLE, non-secret** settings. It deliberately leaves device and identity settings, including the panel ID, and all credentials unchanged. If a bundle-only restore leaves MQTT reporting `auth-failed`, first check that `--mqtt-user` and `--mqtt-pass-file` were supplied; that result does not by itself mean the broker is down.

## Updating a whole fleet

Whole-fleet updates are an advanced administrator workflow and currently **require a source checkout**. From the repository root, use [`scripts/update-fleet.sh`](../scripts/update-fleet.sh). It downloads the release once and runs the provisioner per panel, so every panel is installed, launched and verified:

<!-- source-checkout-only -->
```bash
scripts/update-fleet.sh --latest -- 192.168.1.10 192.168.1.11:5555
# At most four panels run at once by default; reduce or increase the bounded pool with --jobs (1..32).
scripts/update-fleet.sh --jobs 2 --latest -- 192.168.1.10 192.168.1.11:5555
# --prerelease rolls the newest release-candidate instead of the latest stable.
# or pipe a host list:  printf '%s\n' 192.168.1.10 192.168.1.11 | scripts/update-fleet.sh --latest
```

Fleet runs print each panel's provisioning guidance but never accept profile recommendations automatically. `HAPANELD_FLEET_JOBS` sets the default concurrency when `--jobs` is omitted; the command-line option takes precedence.

Fleet updates require Android SDK Build-Tools containing `apksigner` and either `aapt` or `aapt2`. The fleet wrapper authenticates one APK package, signer and SHA-256 digest before starting any panel worker. An APK downloaded through `--latest` or `--prerelease` must carry the official release certificate; a supplied self-built APK may use its builder's consistent signer unless `--require-release-signer` is requested.

## Bootstrapping adb (Tuya TPA10 / Smatek panels)

adb is often only available on the **USB port**, and `adb root` often works only there. Plug in, enable network adb, then provision as normal:

```bash
adb devices                   # accept the on-screen RSA prompt if shown
adb root                      # if supported (needed for the sysfs-LED helper daemon)
adb tcpip 5555                # expose adb on the network (resets on reboot)
adb connect 192.168.1.50:5555 # replace this example address with the panel's address
```

**No adb at all.** With a browser or file manager on the panel, download the [release APK](https://github.com/maxlyth/ha-paneld/releases/latest), enable "install unknown apps" and tap to install — then **grant permissions by hand** (Settings → Apps → ha-paneld → *Modify system settings*; Accessibility → enable the service); the app's setup screen guides you through this. Not possible on locked-down panels with no browser/file manager.

## Permission → why

| Permission | For | Grant |
|------------|-----|-------|
| `POST_NOTIFICATIONS` | foreground-service notification | runtime / `pm grant` |
| `WRITE_SETTINGS` | screen brightness | `appops set <pkg> WRITE_SETTINGS allow` |
| Accessibility (key filter) | hardware-button events | `settings put secure enabled_accessibility_services …` |

Screen-off needs **no device admin** — ha-paneld powers the backlight off via the root helper daemon or `su` (`bl_power`), falling back to brightness-0, so it never raises a keyguard/PIN and never blocks its own uninstall. (Builds ≤ 0.5.0 shipped an optional device admin; 0.5.1 removed it — see the [build & signing notes](local-builds.md) if you're upgrading from one where you'd activated it.)
