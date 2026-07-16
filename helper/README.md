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
| `VERSION` | read the helper identity without touching hardware | `HELPER version=1.0.0 proto=1.0` / `ERR` when arguments are supplied |
| `RGB <r> <g> <b>` | set LED colour (each 0..255) | `OK` / `ERR` |
| `OFF` | LED off | `OK` / `ERR` |
| `BTN <0..255>` | button-backlight brightness | `OK` / `ERR` |
| `LEDPROBE` | which RGB-LED backend this panel has (so the app gates the LED entity on a reachable node) | `ledjni` / `sysfs` / `none` |
| `SCREEN ON` / `SCREEN OFF` | screen backlight power (`bl_power` 0/4) | `OK` / `ERR` |
| `BLPOWER` | read physical screen-backlight power (`bl_power`) | `0`–`4` / `ERR` |
| `BLREAD` | read effective and maximum backlight brightness | `<actual> <max>` / `ERR` |
| `BLSET <n>` | set hardware backlight brightness, clamped to its maximum | `OK` / `ERR` |
| `SCREENCAP` | capture the screen as PNG | raw PNG bytes, then EOF |
| `RELOAD <pkg>` | force-stop + relaunch an app (dashboard reload); serialized with supported Companion data transactions | `OK` / `ERR` / `BUSY` |
| `START <pkg/cls>` | launch an activity by component (root, bypasses BAL limits); serialized with supported Companion data transactions | `OK` / `ERR` / `BUSY` |
| `SETHOME <pkg/cls>` | set the default home (launcher) to a component — re-asserts the dashboard app as home after a HOME-app install clears it | `OK` / `ERR` |
| `OVERLAY <pkg> [mode]` | read or set the package's `SYSTEM_ALERT_WINDOW` app-op; omit `mode` to query, or use `allow`, `deny`, `ignore`, `default`, or `foreground` to restore an exact prior state | `MODE=<mode>` / `OK` / `ERR` |
| `APPSTATE <pkg>` | app-watchdog probe: is the package alive and focused? (`pidof` + focused window) | `FG` / `BG` / `DEAD` / `ERR` |
| `COMPANIONCAPS` | exact Companion data-protocol compatibility probe | `COMPANIONCAPS 1 BACKUP RESTORE STATUS JOURNAL` |
| `COMPANIONSTATUS` | report whether the daemon-wide Companion transaction lane is available | `IDLE` / `BUSY` |
| `COMPANIONBACKUP <pkg>` | recover any interrupted transaction, force-stop a supported HA Companion package, descriptor-open and validate its fixed login files, stream a bounded raw snapshot including optional DB WAL/SHM, then relaunch only from a proven-safe state | `BACKUP <count> <bytes>` + framed `FILE <relative> <bytes>` payloads + `DONE [RELAUNCH_ERR]`, or `BUSY` / `ERR [RELAUNCH_ERR]` |
| `COMPANIONRESTORE <pkg> <db-bytes\|-> <session-bytes\|-> <integration-bytes\|->` | receive the fixed restore set into root-owned staging, recover any interrupted transaction, force-stop, replace the allowlisted files through descriptor-relative staging/rename with durable rollback/commit markers, remove stale DB WAL/SHM, then relaunch only after commit or confirmed rollback | `READY`, then `OK` / `COMMITTED RELAUNCH_ERR` / `ROLLED_BACK [RELAUNCH_ERR]` / `ROLLBACK_FAILED RELAUNCH_SUPPRESSED`; `BUSY` / `STREAMERR` before upload |
| `INPUTV2` | advertise truthful initial evdev open/grab acknowledgement semantics; older helpers return their normal unknown-verb `ERR` | `OK` |
| `WATCH <evdev> <0\|1>` | open a `/dev/input/eventN` node; `1` requires `EVIOCGRAB` (suppress the default Android action). Idempotent only for the same node and grab policy | `OK` only after the initial open, requested grab, and reader start succeed; otherwise `ERR` |
| `SUBSCRIBE` | acquire requested grabs and stream async `KEY <code> <value>` / `SW <code> <value>` lines from every `WATCH`ed node until disconnect | `OK` only after required grabs are active; `ERR` on grab failure or subscriber overflow |
| `DENSITY` / `DENSITY <n>\|reset` | get display density / set it (`wm density`) | `PHYS=<n> OVER=<n\|->` / `ERR` (get) · `OK` / `ERR` (set) |
| `FONTSCALE` / `FONTSCALE <n>\|reset` | get system font scale / set it (`settings system font_scale`) | `SCALE=<n\|null>` / `ERR` (get) · `OK` / `ERR` (set) |
| `GOV <name>` | set the CPU scaling governor on all cores | `OK` / `ERR` |
| `PERFDUMP` | CPU/load/temp/gpu/process snapshot (for sandbox-walled apps) | marker-delimited stream, then EOF |
| `CHT8305` | room temp/humidity from the CHT8305 input devices (`EVIOCGABS` on the `temperature`/`humidity` nodes) | `T=<centi> H=<centi>` / `ERR` |
| `REBOOT` | reboot the panel | `OK` (then down) |
| `PING` | liveness probe | `OK` |
| anything else | — | `ERR` |

`VERSION` is the stable compatibility bootstrap. `version` is the helper release version and `proto` is the wire protocol version; clients accept the same protocol major and treat later minor versions as additive. Helpers from before this command return the ordinary unknown-command `ERR`, so the app verifies them with `PING` and reports them as reachable but compatibility-unverified. Run `hapaneld-helper --version` to print the same identity without initializing hardware or binding the socket.

`WATCH`/`SUBSCRIBE` instrument physical buttons the Android input pipeline doesn't deliver to a sandboxed app — e.g. the WF1589T power key (grabbed so it no longer sleeps the panel) and the TPA10 orange button (an `EV_SW` switch, not a key). The **app** chooses which node to watch and whether to grab it, from its `DeviceProfile`; the daemon streams raw events and the app decides what each means. The app first probes `INPUTV2`, requires each `WATCH` acknowledgement before subscribing, and treats an older helper's stream as usable but diagnostically unverified. A current daemon never degrades a requested exclusive grab into a non-exclusive reader, holds a grab only while at least one subscriber owns delivery, and releases it when the last subscriber disconnects. If a node disappears after setup, its reader retries until it can reopen and, where requested, re-establish the grab.

`SCREEN OFF` powers the display backlight down at the hardware level (true off) while leaving the device Awake — no keyguard, so it wakes without a PIN. This is why a panel with a device PIN needs the daemon for screen-off: a sandboxed app can only dim Settings brightness (clamped to a minimum on many panels) and `lockNow()` would force the keyguard.

## Safety

- **Peer-uid authentication.** The transport is an abstract-namespace UNIX socket, so the daemon reads the connecting process's credentials (`SO_PEERCRED`) and accepts **only** ha-paneld's current live uid plus root. ADB operators use a root shell; generic Android shell uid 2000 is rejected because Shizuku also runs authorized applications under that identity. The app's uid is resolved live by `stat`-ing `/data/data/io.github.maxlyth.hapaneld`, because it changes on every reinstall. Every other local app is rejected and the connection closed before a single command runs. (The earlier `127.0.0.1:8889` TCP listener had no auth — any app with `INTERNET` could `REBOOT`/`SCREENCAP` it.)
- **Airtight parsing.** A bounded per-connection line buffer (commands split across reads still parse; overlong lines are dropped, not mis-split or overflowed); every argument `sscanf` is width-bounded; unknown verbs return `ERR`.
- **Resource limits.** Concurrent connections are capped, and an idle connection is dropped after a timeout — except long-lived `SUBSCRIBE` streams, which are meant to sit idle reading events. So a connection flood can't exhaust the thread-per-connection model.
- **Descriptor-anchored Companion files.** Backup and restore accept only the full/minimal HA Companion package names and the fixed login-file set; no caller pathname reaches the filesystem. Package, parent and file components are opened relative to trusted directory descriptors with `O_NOFOLLOW`, and live files must be app-owned, regular, single-link inodes within strict per-file and aggregate bounds. Restore uploads first enter a root-owned compartment, then a single helper-owned transaction stages replacement inodes, preserves owner and SELinux label, and fsyncs a fixed-format prepared marker before moving old live files. Old-to-rollback renames are directory-fsynced before new live files are installed; a separately named committed marker is atomically renamed and fsynced before rollback cleanup. After a daemon or power interruption, the next Companion data command rolls a prepared transaction back or finishes a committed transaction, and unexplained rollback artifacts fail closed rather than being deleted. Supported Companion `START`/`RELOAD` requests return `BUSY` while the mutex is held and while any durable marker or unexplained rollback remains after a daemon restart.
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
| `commands.def` / `dispatch.c` | the shared verb→handler manifest + exact-match `dispatch()` |
| `version.c` | stable helper and protocol identity exposed by `VERSION` and `--version` |
| `led.c` / `screen.c` / `input.c` | LED (sysfs + ledjni), backlight power, evdev buttons |
| `sysctl.c` | density / governor / reload / start / reboot / screencap (the shell-out verbs) |
| `companion.c` | descriptor-anchored, bounded HA Companion backup/restore transaction |
| `perf.c` | `PERFDUMP` `/proc` snapshot |
| `cht8305.c` | `CHT8305` room temp/humidity read (input-subsystem `EVIOCGABS`, no exec) |
| `util.c` | clamp, node IO, the argument validators |
| `sysexec.c` | **the only file that execs / pipes / spawns / reboots** — every shell-out funnels here |

Isolating `sysexec` keeps the entire privilege/injection surface in one auditable file, and lets the sanitizer-smoke and unit-test builds swap it for a stub so the real parser runs on the host with no side effects.

## Build and test

```bash
./helper/build.sh        # cross-compile both ABIs at Android API 26 -> helper/dist/<abi>/hapaneld-helper

make -C helper           # host -Werror compile check (the same src/*.c set)
make -C helper test      # host-native unit tests (validators, clamp, /proc parser, dispatch, accumulator)
make -C helper fuzz      # bounded in-house ASan+UBSan smoke — see fuzz/README.md for its limits
```

Or compile by hand with any Android NDK: `*-clang -O2 -s -Ihelper/src -o hapaneld-helper helper/src/*.c`.

## Provision (per panel, root/adb)

Install or upgrade the daemon on every rooted supported panel. Sandbox-walled panels need it for their privileged controls, while direct-`su` panels also use the current helper for the descriptor-confined Companion backup/restore protocol. The app and helper therefore form one compatibility unit even when older releases could route some controls through `su`.

```bash
./helper/build.sh
./helper/install-daemon.sh <ip:5555>
```

Do not execute the adb/shell-owned staging copy from `/data/local/tmp` as root. The installer hashes the staged input, selects a verified boot-persistence runner before stopping the previous helper, publishes only a root-owned copy, verifies the exact Companion protocol response, and rolls back to the prior binary/service if the replacement does not start correctly. The main provisioner performs the same helper migration automatically when installing a current app release.

## Boot persistence (init service)

The daemon must (re)start in a root domain after every reboot, and the app cannot bootstrap that service itself. `helper/install-daemon.sh` probes the panel and chooses one of two authenticated, rollback-capable paths:

- A writable-system/userdebug panel receives `/system/bin/hapaneld-helper` plus the init service in `/system/etc/init`.
- A read-only-system panel is accepted only when a supported Magisk, KernelSU, or APatch service runner is detected; it receives a root-owned binary under `/data/adb/hapaneld` and a `service.d` launcher.

If neither persistence path is verified, installation stops without replacing or stopping the existing helper. Reboot the panel when convenient after installation to confirm boot persistence.
