# Provisioning & fleet updates

Headless setup for rooted / userdebug panels — all permissions granted over adb, no per-device
tap-through. For the quick single-panel install see the [README](../README.md#install); this page
covers the scriptable path and rolling a whole fleet.

## One panel

Run the same script on every panel:

```bash
scripts/provision.sh <panel-ip:5555> \
    [--id NAME] [--mqtt tcp://host:1883] [--latest] [--force]
```

With no `--apk` and no local build it downloads the **latest signed release** from GitHub (`--latest`
forces that even when a local build exists). It connects, installs, grants the permissions below,
starts the agent, optionally sets the panel id / MQTT, and ends with a self-verify checklist. It is
**idempotent** — re-run the same command to finish after any interruption — and warns before
reinstalling the same or an older version (`--force` skips that). `scripts/provision.sh <ip> --verify`
re-checks a panel without changing anything.

Non-root panels: use the in-app setup screen, which fires the standard system permission intents.

## Updating a whole fleet

Use [`scripts/update-fleet.sh`](../scripts/update-fleet.sh). The fleet script downloads the release
once and runs `provision.sh` per panel, so every one is installed **and launched and verified**:

```bash
scripts/update-fleet.sh --latest -- 192.168.1.10 192.168.1.11:5555
# or pipe a host list:  printf '%s\n' 192.168.1.10 192.168.1.11 | scripts/update-fleet.sh --latest
```

## Bootstrapping adb (Tuya TPA10 / Smatek panels)

adb — and `adb root` — is often only available, or only rooted, on the **USB port**. Plug in, enable
network adb, then provision as normal:

```bash
adb devices                   # accept the on-screen RSA prompt if shown
adb root                      # if supported (needed for the sysfs-LED helper daemon)
adb tcpip 5555                # expose adb on the network (resets on reboot)
adb connect <panel-ip>:5555
```

**No adb at all.** With a browser or file manager on the panel, download the
[release APK](https://github.com/maxlyth/ha-paneld/releases/latest), enable "install unknown apps"
and tap to install — then **grant permissions by hand** (Settings → Apps → ha-paneld → *Modify
system settings*; Accessibility → enable the service), which the app's setup screen guides. Not
possible on locked-down panels with no browser/file manager.

## Permission → why

| Permission | For | Grant |
|------------|-----|-------|
| `POST_NOTIFICATIONS` | foreground-service notification | runtime / `pm grant` |
| `WRITE_SETTINGS` | screen brightness | `appops set <pkg> WRITE_SETTINGS allow` |
| Accessibility (key filter) | hardware-button events | `settings put secure enabled_accessibility_services …` |

Screen-off needs **no device admin** — ha-paneld powers the backlight off via the root helper daemon
or `su` (`bl_power`), falling back to brightness-0, so it never raises a keyguard/PIN and never blocks
its own uninstall. (Builds ≤ 0.5.0 shipped an optional device admin; 0.5.1 removed it — see the
[build & signing notes](local-builds.md) if you're upgrading from one where you'd activated it.)
