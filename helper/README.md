# hapaneld-helper — root helper daemon for sandbox-walled panels

A tiny root daemon that gives ha-paneld a whitelisted, authenticated control surface for the things a sandboxed Android app can't reach itself: the RGB LED, screen-backlight power, hardware-button instrumentation, display density / CPU governor / screencap / perf snapshots, and app reload/start/ reboot. It began as an LED-only helper (the former `hapaneld-ledd`) and was **renamed `hapaneld-helper`** to match its broader role; the code is split by capability under [`src/`](#source-layout). Upgrading from an old `hapaneld-ledd` install is handled by `install-daemon.sh` (it removes the old binary + init service so both don't run) — see [Boot persistence](#boot-persistence-init-service).

## Why it exists

Panels expose their RGB LED one of two ways:

- **App-accessible char device** (e.g. rk3576 `/dev/ledjni`, world-rwx, SELinux `device` label). ha-paneld drives these **directly** from its own NDK — no helper, no root.
- **Root-only sysfs** (e.g. Tuya TPA10 `/sys/class/leds/avs-pwm-led/avsux_animation`, SELinux `sysfs_lights`, `system:system`). A normal Android app (`untrusted_app` domain) **cannot** write this node, and **cannot** exec `su` to escalate. On these panels the RGB capability lives behind the Android lights HAL / vendor system service and is reachable only by system/root code.

For the second class, `hapaneld-helper` runs **outside the app sandbox** in a root domain that *can* write the node, and exposes a minimal command surface on an **abstract-namespace UNIX socket** (`@hapaneld-helper`). ha-paneld connects to it and asks for colours. The app stays a single uniform API; the privilege is isolated here. The socket is authenticated by peer uid (see [Safety](#safety)), so no other app on the panel can reach it — unlike the earlier loopback-TCP listener.

## Protocol

Newline-terminated ASCII on the abstract UNIX socket `@hapaneld-helper`. One or more commands per connection.

| Command | Effect | Reply |
| --- | --- | --- |
| `RGB <r> <g> <b>` | set LED colour (each 0..255) | `OK` / `ERR` |
| `OFF` | LED off | `OK` / `ERR` |
| `BTN <0..255>` | button-backlight brightness | `OK` / `ERR` |
| `LEDPROBE` | which RGB-LED backend this panel has (so the app gates the LED entity on a reachable node) | `ledjni` / `sysfs` / `none` |
| `SCREEN ON` / `SCREEN OFF` | screen backlight power (`bl_power` 0/4) | `OK` / `ERR` |
| `SCREENCAP` | capture the screen as PNG | raw PNG bytes, then EOF |
| `RELOAD <pkg>` | force-stop + relaunch an app (dashboard reload) | `OK` / `ERR` |
| `START <pkg/cls>` | launch an activity by component (root, bypasses BAL limits) | `OK` / `ERR` |
| `SETHOME <pkg/cls>` | set the default home (launcher) to a component — re-asserts the dashboard app as home after a HOME-app install clears it | `OK` / `ERR` |
| `APPSTATE <pkg>` | app-watchdog probe: is the package alive and focused? (`pidof` + focused window) | `FG` / `BG` / `DEAD` / `ERR` |
| `WATCH <evdev> <0\|1>` | read an input node; `1` = `EVIOCGRAB` it (suppress the default Android action). Idempotent per node | `OK` / `ERR` |
| `SUBSCRIBE` | this connection then receives async `KEY <code> <value>` / `SW <code> <value>` lines for every event from `WATCH`ed nodes, until it disconnects | `OK` |
| `DENSITY` / `DENSITY <n>\|reset` | get display density / set it (`wm density`) | `PHYS=<n> OVER=<n\|->` (get) · `OK` / `ERR` (set) |
| `GOV <name>` | set the CPU scaling governor on all cores | `OK` / `ERR` |
| `PERFDUMP` | CPU/load/temp/gpu/process snapshot (for sandbox-walled apps) | marker-delimited stream, then EOF |
| `CHT8305` | room temp/humidity from the CHT8305 input devices (`EVIOCGABS` on the `temperature`/`humidity` nodes) | `T=<centi> H=<centi>` / `ERR` |
| `REBOOT` | reboot the panel | `OK` (then down) |
| `PING` | liveness probe | `OK` |
| anything else | — | `ERR` |

`WATCH`/`SUBSCRIBE` instrument physical buttons the Android input pipeline doesn't deliver to a sandboxed app — e.g. the WF1589T power key (grabbed so it no longer sleeps the panel) and the TPA10 orange button (an `EV_SW` switch, not a key). The **app** chooses which node to watch and whether to grab it, from its `DeviceProfile`; the daemon streams raw events and the app decides what each means.

`SCREEN OFF` powers the display backlight down at the hardware level (true off) while leaving the device Awake — no keyguard, so it wakes without a PIN. This is why a panel with a device PIN needs the daemon for screen-off: a sandboxed app can only dim Settings brightness (clamped to a minimum on many panels) and `lockNow()` would force the keyguard.

## Safety

- **Peer-uid authentication.** The transport is an abstract-namespace UNIX socket, so the daemon reads the connecting process's credentials (`SO_PEERCRED`) and accepts **only** ha-paneld's own uid, plus root and shell (for adb debugging). The app's uid is resolved live by `stat`-ing `/data/data/io.github.maxlyth.hapaneld`, because it changes on every reinstall. Every other local app is rejected and the connection closed before a single command runs. (The earlier `127.0.0.1:8889` TCP listener had no auth — any app with `INTERNET` could `REBOOT`/`SCREENCAP` it.)
- **Airtight parsing.** A bounded per-connection line buffer (commands split across reads still parse; overlong lines are dropped, not mis-split or overflowed); every argument `sscanf` is width-bounded; unknown verbs return `ERR`.
- **Resource limits.** Concurrent connections are capped, and an idle connection is dropped after a timeout — except long-lived `SUBSCRIBE` streams, which are meant to sit idle reading events. So a connection flood can't exhaust the thread-per-connection model.
- The commands that shell out (`RELOAD`, `START`, `REBOOT` via `am`/`svc`) sanitise their argument against a strict char-whitelist; the LED/backlight writes touch **only** the whitelisted nodes (`avs-pwm-led/avsux_animation`, `button-backlight/brightness`).

> [!CAUTION]
> The daemon never writes `avs-pwm-led/avsux_select` or `custom_animation`. Those are firmware-backed and **reliably reboot the TPA10**.

A set colour **holds** until the next command (no auto-revert); `OFF` writes black. Handing the LED back to the vendor's idle animation currently requires a reboot.

## Extending the helper (contributor guide)

ha-paneld's per-panel knowledge lives in **`DeviceProfile`** silos on the app side — one file per panel (`device/Tpa10.kt`, `device/Wf1589t.kt`, …). The intent is that adding a panel means writing **one profile file** and nothing else. The daemon is designed to honour that same separation: it should be **panel-blind**, exposing *generic, parameterised primitives* while the profile decides which to use and with what targets.

How well each capability meets that today:

| Capability | Where panel specifics live | New-panel change |
| --- | --- | --- |
| **Buttons** (`WATCH`/`SUBSCRIBE`) | the app passes the evdev node + grab flag from its profile; daemon streams raw events | **profile only** — no daemon change |
| **Screen** (`SCREEN`) | daemon auto-discovers `/sys/class/backlight` | **none** (optionally accept an app-supplied path for multi-backlight panels) |
| **Reboot / reload / start** | generic `am`/`svc` | **none** |
| **LED** (`RGB`/`OFF`/`BTN`) | **hardcoded** to the TPA10 sysfs node + its `avsux` write format | **core change** — see below |

> [!NOTE]
> **Buttons are the reference pattern.** `WATCH` takes the target *as a parameter from the profile*, constrained to a `/dev/input/` prefix. A new panel with an unusual physical button needs **zero** daemon edits — just an `evdevButtons` entry in its `DeviceProfile`. New capabilities should copy this shape.

### The one current seam: LED

`RGB`/`OFF`/`BTN` hardcode the TPA10 node paths (`avs-pwm-led/avsux_animation`, `button-backlight/brightness`) and that panel's `HOLD_MS:RRGGBB` write format. A panel with a root-only LED at a *different* path or format can't be added without editing the shared `set_rgb`/`write_node` core. The intended fix is to **parameterise it like `WATCH`** (the TPA10 is the only daemon-LED panel; rk3576/px30 drive LEDs app-direct):

```text
LED <node> <payload>        # app supplies the target + payload from its DeviceProfile
```

constrained to a `/sys/class/leds/` prefix with bounded values. That moves per-panel LED specifics into the profile and returns the daemon to panel-blind. Until then, **LED-via-daemon is the one path that requires a core PR** — a contributor adding it should expect to touch shared C and have it reviewed for the safety model below.

### Adding a new device class (i2c, IR, haptics, …)

The daemon is the right home for any capability that needs **root or system privilege a sandboxed app can't reach** — e.g. an i2c sensor whose sysfs is `system`-owned, an IR blaster `/dev` node, a PWM/haptic. The extension recipe:

1. Add a small, self-contained handler in the module that owns the capability (a new `src/<name>.c` for a new device class), then **register the verb** with one row in the `COMMANDS` table in `src/dispatch.c`. That's the whole wiring — `dispatch()` matches the verb *exactly* and hands your handler the argument string.
2. **Take the target as a parameter** from the app (don't hardcode a panel's path), and **whitelist it by class prefix** — the way `WATCH` restricts to `/dev/input/` and a future `LED` would restrict to `/sys/class/leds/`. For a reader (i2c/sensor), stream values to `SUBSCRIBE`rs using the existing async-line mechanism in `src/input.c`.
3. Keep the safety model intact: the peer-uid auth gates the whole socket, but still bound every buffer, validate/clamp inputs (the `src/util.c` validators), width-bound every `sscanf`, route any shell-out through `sysexec_run()` (the one exec seam) with a validated argument, and never write firmware-backed nodes (see the CAUTION above).
4. Publish the matching HA entity from the app per `DeviceProfile` capability — the daemon stays the privileged mechanism, the profile stays the policy.

A genuinely new *primitive* is the one acceptable reason to change the core daemon; per-panel *selection* of existing primitives must stay in the `DeviceProfile`. That boundary keeps a unique panel's contribution to a single profile file wherever the underlying primitive already exists.

## Source layout

The daemon is split by capability under `helper/src/` (the binary, `@hapaneld-helper` socket, and init service keep their historical names — only the source is modular):

| File | Responsibility |
| --- | --- |
| `main.c` | accept loop, abstract-socket bind, `SO_PEERCRED` peer-auth, connection cap |
| `server.c` | the bounded line accumulator (`server_serve`) + idle timeout |
| `dispatch.c` | the verb→handler **table** + exact-match `dispatch()` |
| `led.c` / `screen.c` / `input.c` | LED (sysfs + ledjni), backlight power, evdev buttons |
| `sysctl.c` | density / governor / reload / start / reboot / screencap (the shell-out verbs) |
| `perf.c` | `PERFDUMP` `/proc` snapshot |
| `cht8305.c` | `CHT8305` room temp/humidity read (input-subsystem `EVIOCGABS`, no exec) |
| `util.c` | clamp, node IO, the argument validators |
| `sysexec.c` | **the only file that execs / pipes / spawns / reboots** — every shell-out funnels here |

Isolating `sysexec` keeps the entire privilege/injection surface in one auditable file, and lets the fuzz + unit-test builds swap it for a stub so the real parser runs on the host with no side effects.

## Build, test, fuzz

```bash
./helper/build.sh        # cross-compile both ABIs -> helper/dist/<abi>/hapaneld-helper (Docker; reuses tools/build)

make -C helper           # host -Werror compile check (the same src/*.c set)
make -C helper test      # host-native unit tests (validators, clamp, /proc parser, dispatch, accumulator)
make -C helper fuzz      # ASan+UBSan parser fuzz — see fuzz/README.md
```

Or compile by hand with any Android NDK: `*-clang -O2 -s -Ihelper/src -o hapaneld-helper helper/src/*.c`.

## Provision (per panel, root/adb)

Install the daemon on **every sandbox-walled panel** (any profile with `appCanSu = false`, e.g. TPA10, SMT1019), not just ones whose LED is daemon-driven. On those panels the app can't exec `su`, so the daemon is the privileged path for screen-off, display density, CPU governor, screenshot, perf sampling, hardware buttons *and* the LED; without it those controls are present but silently empty (and `/diag` flags "Helper daemon = NEEDED but not running"). Panels that can `su` directly don't need it.

```bash
ABI=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')      # e.g. armeabi-v7a
adb push helper/dist/$ABI/hapaneld-helper /data/local/tmp/
adb shell su 0 'chmod 755 /data/local/tmp/hapaneld-helper'
adb shell su 0 '/data/local/tmp/hapaneld-helper &'             # run in the su domain (can write sysfs_lights)
```

ha-paneld auto-detects the daemon (a `PING` on the abstract socket `@hapaneld-helper`) and routes its root-only controls through it — screen-off, display density, CPU governor, screenshot, perf sampling, hardware buttons, and (where present) the LED entity — enabling each as the daemon answers.

## Boot persistence (init service)

The daemon must (re)start in a root domain after every reboot, and the app can't start it (no `su` from `untrusted_app`). On a userdebug panel this is an `init` service with `seclabel u:r:su:s0` so it runs in the `su` domain (the only one that can write the root-only nodes). `helper/install-daemon.sh` installs the binary to `/system/bin` and `helper/hapaneld-helper.rc` to `/system/etc/init/`.

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

Verified on a WF1589T (rk3576, Android 14): after a cold boot the daemon runs with SELinux context `u:r:su:s0` and answers `PING`. Trade-off: this disables dm-verity and writes `/system` (an OTA/factory-reset would remove it) — acceptable on an already-rooted panel.
