# Provisioning & fleet updates

Unattended setup for rooted or userdebug panels grants permissions over adb without per-device tap-through. For the quick single-panel install see the [README](../README.md#install); this page covers the scriptable path and rolling a whole fleet.

> [!NOTE]
> These are `bash` + `adb` commands. On **Windows**, run them in **Git Bash** (from [Git for Windows](https://gitforwindows.org/)) or **WSL** — not PowerShell — with `adb` on `PATH` (`winget install Google.PlatformTools`). macOS and Linux run them as-is.

## One panel

Run the same script on every panel:

```bash
scripts/provision.sh <panel-ip:5555> \
    [--id NAME] [--mqtt tcp://host:1883] [--latest] [--prerelease] [--force] \
    [--builtin --ha-url URL {--ha-token LLAT | --ha-user U --ha-pass P}]
```

It downloads the **latest signed release** from GitHub when no `--apk` is given and no local build exists. `--latest` forces the stable download even when a local build exists; `--prerelease` fetches the newest release candidate instead. It connects, installs, grants the permissions below, starts the agent, optionally sets the panel id / MQTT, and ends with a self-verify checklist. It is **idempotent**, so re-run the same command to finish after any interruption. Required startup, configuration, restore, and verification failures return a nonzero result and are never counted as a successful fleet update. Run `scripts/provision.sh --help` for the concise command reference.

Two read-only operations are safe to run independently:

```bash
# Secret-inclusive recovery bundle. This does not resolve or install an APK.
scripts/provision.sh <panel-ip:5555> --export panel-config.json

# Check the existing installation without changing it.
scripts/provision.sh <panel-ip:5555> --verify
```

When `--export FILE` is combined with install or configuration options, the verified backup is written **before** any panel mutation. Protect that file like a credential. Provisioning also offers the profile's known vendor overlays and factory-test apps for reversible disabling; pass `--no-tame` to leave them unchanged.

Non-root panels: use the in-app setup screen, which fires the standard system permission intents.

**Sandbox-walled panels (TPA10, SMT1019, … — the app itself can't exec `su`):** also install the [root helper daemon](../helper/README.md), which is the privileged control path there (screen-off, density, CPU governor, screenshot, perf, buttons, LED):

```bash
./helper/build.sh && ./helper/install-daemon.sh <panel-ip:5555>
```

rk3576 / PX30 panels run `su` in-app and don't need it. The installer probes the panel's root path (vendor `su` variants or a root adbd) and picks a `/system` or systemless (Magisk-style `service.d`) install automatically; it is idempotent and safe to re-run.

## Provisioning the built-in dashboard renderer

Since 0.9, ha-paneld includes its own experimental dashboard renderer. `--builtin` selects it and provisions its Home Assistant sign-in **from this machine**, so nothing is typed on the panel:

```bash
# Username/password: logs in HERE and mints a revocable refresh token; the password never reaches
# the panel, but command-line values may remain in shell history. Prefer the Configure UI or a
# Companion-login import when practical.
scripts/provision.sh <panel-ip:5555> --builtin --ha-url https://ha.example --ha-user USER --ha-pass PASS

# or a long-lived access token instead of a login:
scripts/provision.sh <panel-ip:5555> --builtin --ha-url https://ha.example --ha-token LLAT
```

On a **rooted** panel already running a signed-in HA Companion, none of that is needed: set the dashboard app to **Built-in renderer** in the `:8888` Configure tab (or POST `dashboard_package=builtin`) and it borrows the Companion's sign-in automatically — the Companion keeps its own login, and switching back is the same picker change.

Reverting to the Companion: set the dashboard app back to it in the Configure tab (or blank `dashboard_package`). The built-in renderer deliberately has **no Voice Assistant (Assist) and no notifications** — keep the Companion where those matter.

## Updating a whole fleet

Use [`scripts/update-fleet.sh`](../scripts/update-fleet.sh). The fleet script downloads the release once and runs `provision.sh` per panel, so every one is installed **and launched and verified**:

```bash
scripts/update-fleet.sh --latest -- 192.168.1.10 192.168.1.11:5555
# --prerelease rolls the newest release-candidate instead of the latest stable.
# or pipe a host list:  printf '%s\n' 192.168.1.10 192.168.1.11 | scripts/update-fleet.sh --latest
```

## Bootstrapping adb (Tuya TPA10 / Smatek panels)

adb is often only available on the **USB port**, and `adb root` often works only there. Plug in, enable network adb, then provision as normal:

```bash
adb devices                   # accept the on-screen RSA prompt if shown
adb root                      # if supported (needed for the sysfs-LED helper daemon)
adb tcpip 5555                # expose adb on the network (resets on reboot)
adb connect <panel-ip>:5555
```

**No adb at all.** With a browser or file manager on the panel, download the [release APK](https://github.com/maxlyth/ha-paneld/releases/latest), enable "install unknown apps" and tap to install — then **grant permissions by hand** (Settings → Apps → ha-paneld → *Modify system settings*; Accessibility → enable the service); the app's setup screen guides you through this. Not possible on locked-down panels with no browser/file manager.

## Permission → why

| Permission | For | Grant |
|------------|-----|-------|
| `POST_NOTIFICATIONS` | foreground-service notification | runtime / `pm grant` |
| `WRITE_SETTINGS` | screen brightness | `appops set <pkg> WRITE_SETTINGS allow` |
| Accessibility (key filter) | hardware-button events | `settings put secure enabled_accessibility_services …` |

Screen-off needs **no device admin** — ha-paneld powers the backlight off via the root helper daemon or `su` (`bl_power`), falling back to brightness-0, so it never raises a keyguard/PIN and never blocks its own uninstall. (Builds ≤ 0.5.0 shipped an optional device admin; 0.5.1 removed it — see the [build & signing notes](local-builds.md) if you're upgrading from one where you'd activated it.)
