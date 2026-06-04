# hapaneld-ledd — root LED helper for sysfs-LED panels

A tiny root daemon that drives a panel's RGB LED on behalf of ha-paneld, for panels where the LED
is **not** reachable from an app sandbox.

## Why it exists

Panels expose their RGB LED one of two ways:

- **App-accessible char device** (e.g. rk3576 `/dev/ledjni`, world-rwx, SELinux `device` label).
  ha-paneld drives these **directly** from its own NDK — no helper, no root.
- **Root-only sysfs** (e.g. Tuya TPA10 `/sys/class/leds/avs-pwm-led/avsux_animation`, SELinux
  `sysfs_lights`, `system:system`). A normal Android app (`untrusted_app` domain) **cannot** write
  this node, and **cannot** exec `su` to escalate. On these panels the RGB capability lives behind
  the Android lights HAL / vendor system service and is reachable only by system/root code.

For the second class, `hapaneld-ledd` runs **outside the app sandbox** in a root domain that *can*
write the node, and exposes a minimal command surface on loopback TCP. ha-paneld (which has the
INTERNET permission) connects to `127.0.0.1:8889` and asks for colours. The app stays a single
uniform API; the privilege is isolated here.

## Protocol

Newline-terminated ASCII on `127.0.0.1:8889`. One or more commands per connection.

| Command | Effect | Reply |
| --- | --- | --- |
| `RGB <r> <g> <b>` | set LED colour (each 0..255) | `OK` / `ERR` |
| `OFF` | LED off | `OK` / `ERR` |
| `BTN <0..255>` | button-backlight brightness | `OK` / `ERR` |
| `SCREEN ON` / `SCREEN OFF` | screen backlight power (`bl_power` 0/4) | `OK` / `ERR` |
| `RELOAD <pkg>` | force-stop + relaunch an app (dashboard reload) | `OK` / `ERR` |
| `REBOOT` | reboot the panel | `OK` (then down) |
| `PING` | liveness probe | `OK` |
| anything else | — | `ERR` |

`SCREEN OFF` powers the display backlight down at the hardware level (true off) while leaving the
device Awake — no keyguard, so it wakes without a PIN. This is why a panel with a device PIN needs
the daemon for screen-off: a sandboxed app can only dim Settings brightness (clamped to a minimum
on many panels) and `lockNow()` would force the keyguard.

## Safety

- Binds **`127.0.0.1` only**; fixed, tiny command set; no shell exec.
- Writes **only** `avs-pwm-led/avsux_animation` and `button-backlight/brightness`.

> [!CAUTION]
> The daemon never writes `avs-pwm-led/avsux_select` or `custom_animation`. Those are
> firmware-backed and **reliably reboot the TPA10** (verified 2026-06-03).

A set colour **holds** until the next command (no auto-revert); `OFF` writes black. Handing the LED
back to the vendor's idle animation currently requires a reboot.

## Build

```bash
./helper/build.sh        # -> helper/dist/<abi>/hapaneld-ledd  (Docker only; reuses tools/build)
```

Or compile `ledd.c` with any Android NDK (`*-clang -O2 -s -o hapaneld-ledd ledd.c`).

## Provision (per panel, root/adb)

```bash
ABI=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')      # e.g. armeabi-v7a
adb push helper/dist/$ABI/hapaneld-ledd /data/local/tmp/
adb shell su 0 'chmod 755 /data/local/tmp/hapaneld-ledd'
adb shell su 0 '/data/local/tmp/hapaneld-ledd &'             # run in the su domain (can write sysfs_lights)
```

ha-paneld auto-detects the daemon (a `PING` on `127.0.0.1:8889`) and publishes the LED entity when
it answers.

## Boot persistence (init service)

The daemon must (re)start in a root domain after every reboot, and the app can't start it (no `su`
from `untrusted_app`). On a userdebug panel this is an `init` service with `seclabel u:r:su:s0` so
it runs in the `su` domain (the only one that can write the root-only nodes). `helper/install-daemon.sh`
installs the binary to `/system/bin` and `helper/hapaneld-ledd.rc` to `/system/etc/init/`.

`/system` is read-only (dm-verity), so make it writable once first:

```bash
adb -s <ip:5555> root
adb -s <ip:5555> disable-verity   # enables overlayfs
adb -s <ip:5555> reboot
# after boot:
adb -s <ip:5555> remount
./helper/install-daemon.sh <ip:5555>
adb -s <ip:5555> reboot           # confirm the init service auto-starts the daemon
```

Verified on office_dash (rk3576, Android 14) 2026-06-04: after a cold boot the daemon runs with
SELinux context `u:r:su:s0` and answers `PING`. Trade-off: this disables dm-verity and writes
`/system` (an OTA/factory-reset would remove it) — acceptable for a controlled, already-rooted fleet.
