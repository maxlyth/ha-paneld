# Changelog

Human-readable summaries of each release. The auto-generated commit list is appended below these
notes on each [GitHub release](https://github.com/maxlyth/ha-paneld/releases). Cutting a release? Follow
the pre-tag checklist in [docs/RELEASING.md](docs/RELEASING.md) (it starts with "check the README").

From **v0.8.0**, entries are grouped under **Added** (new features/entities), **Changed** (behaviour
changes to existing features), **Fixed** (bug fixes), and **Docs** (documentation) — only groups with
content appear. Earlier releases predate this convention and keep their flat lists.

## Unreleased

HTTP UI redesign — a declarative settings registry, configurable Home Assistant exposure, a canonical config API, config bundles with on-panel history, and a tabbed multi-page web UI. (On the `http-ui-redesign` branch; not yet released.)

### Added

- **Declarative settings registry** — a single source of truth (`config/SettingsRegistry`) describing each setting's type, group, Basic/Advanced tier, portability scope, validation, and Home Assistant entity. Drives the config API, the generated form, and MQTT discovery so the three can't drift. Pure/unit-tested, with golden tests asserting byte-identical discovery payloads.
- **Configurable HA exposure** — every config entity gains a per-panel "expose to Home Assistant" toggle. Hiding one clears its retained discovery (the entity leaves HA entirely — zero recorder / state-machine cost). Set from the new Configure page or `POST /api/v1/config` (`ha_expose_<key>`).
- **Canonical config over HTTP** — the formerly MQTT-only settings (`wake_on_wave`, `prevent_idle_dim`, `watchdog`, `auto_brightness`, `brightness_bias`, `navbar`, `touch_sound`, `cpu_governor`, `network_adb`, `zigbee_router`, `ambient_lux`) are now settable via the config API, applied through the same path an HA command uses.
- **`/api/v1` namespace** — `config`, `config/schema`, `perf`, `proximity`, `diag`, `health`, and `input` (tap injection). Existing flat routes remain for back-compat.
- **Config bundles + revision history** — `GET /api/v1/config/export` (versioned, secrets excluded by default) and a transactional `POST /api/v1/config/import` (migrate → scope/secret filter → validate-all-or-reject → snapshot → apply; `?dry_run=1` previews the diff, `?mode=fleet` applies only portable keys). On-panel revision ring buffer with `GET /api/v1/config/revisions` + restore.
- **Tabbed web UI** — Dashboard / Configure / Test / Install / Fleet / API. **Configure** is a schema-driven Basic/Advanced form with inline expose pips and bundle backup/restore; **Test** adds an interactive screenshot (View/Control — tap the image to touch the panel) plus on-screen nav actions and a TTS test; **Install** surfaces panel-health warnings + the capabilities matrix. Self-contained, offline, no build step.

## v0.8.5-rc7 - 2026-07-01

### Fixed

- **MQTT reconnect watchdog now runs on a dedicated thread** — on panels with slow/contended root (`su`), blocking calls could exhaust the shared background thread pool and silently stall the watchdog, so a dropped MQTT connection never self-healed. The watchdog is now its own thread, immune to that starvation, so a stuck/half-open connection is always detected and rebuilt.

## v0.8.5-rc6 - 2026-07-01

### Changed

- **ha-paneld self-update now defaults to OFF** — automatic self-update is a supply-chain risk if control of the release repo were ever lost, so it is now strictly opt-in for new installs (the pinned-signer check still guards it when enabled). Turn it on per panel with the **Self-update** switch if you want a panel to track releases automatically.

## v0.8.5-rc5 - 2026-07-01

### Fixed

- **MQTT panels no longer silently stop updating after a broker/network flap** — a broker-side disconnect could leave the panel's socket half-open (CLOSE-WAIT) while the MQTT client still reported itself connected, so it published into a dead link and Home Assistant showed stale values, with the reconnect-watchdog none the wiser. Three layered fixes: ha-paneld now holds a **partial wakelock** so the SoC and network never suspend into that state (screen still sleeps freely; on by default, `keep_awake`); the MQTT connection sets an explicit **30 s keepalive** so a dead link is detected quickly (and Home Assistant is told the panel is unavailable sooner, rather than shown stale); and the reconnect watchdog is now **liveness-based** — it tracks broker-acknowledged publishes and forces a full reconnect when nothing has been acknowledged for a few minutes, even if the client still claims to be connected.

### Changed

- **Helper connection-cap now unit-tested** — the daemon's concurrent-connection limit is covered by tests (boundary + concurrent rejection + a race check); no behaviour change.

## v0.8.5-rc4 - 2026-07-01

### Added

- **ha-paneld self-update (stable / pre-release channels)** — ha-paneld can now update **itself** over root from GitHub releases, following a configurable channel. A **Self-update** switch (on by default on the stable channel) checks on the 24 h cadence; an **Update channel** select picks **Stable** or **Pre-release**; an **Update ha-paneld** button forces it on demand. Uses the same pinned-signer install path as the Companion updater. It never auto-**downgrades** — moving from a running pre-release back to stable waits for the stable channel to catch up, while a forward update (or a stable→pre-release switch) installs immediately.

### Changed

- **MQTT retain rework — no zombie entities across upgrades** — discovery config is now published un-retained and tracked, so when ha-paneld starts a newer version it actively prunes the discovery topics it no longer publishes (version-gated) and re-announces the current set. Entities removed or renamed between versions no longer linger as dead entries in Home Assistant, and deletions stick.
- **Reload returns to the intended dashboard** — a dashboard reload now navigates back to the configured home dashboard after reloading, instead of leaving the WebView wherever it happened to be.
- **Network-adb persistence redesign** — the network-adb switch no longer just writes a boot prop and hopes the OS honours it (some firmwares strip it). ha-paneld now re-asserts network-adb at every boot and MQTT reconnect while the switch is on, distinguishes "active (enabled elsewhere)" from "persistent via ha-paneld" in the status, and won't disable adb that another mechanism turned on.
- **App↔daemon contract cross-check (reliability)** — a CI check now verifies every helper verb the app sends is implemented by the native daemon, so a protocol drift between the Kotlin clients and the helper fails the build instead of silently breaking a control at runtime.

### Docs

- **NSPanel Pro firmware-quirks-by-version table** — consolidated the per-firmware behaviour (stock WebView 107, per-version adb-enable route, v4.0.0 F-Droid bundle, 4.0.12 proximity graded/binary, 4.5.x reboot loops) into one quick-reference table in the hardware docs.

## v0.8.5-rc3 - 2026-07-01

### Changed

- **HA Companion app installer hardened (security)** — the auto-install/update is now gated by a **signer + package allowlist**: the downloaded APK must declare the allowlisted package **and** be signed by the pinned official HA Companion certificate, otherwise it's refused. This closes the fresh-install / MITM vector (Android's same-signer rule only protects *updates*, not first installs). Downgrades remain allowed (`pm install -d`), reserved for future stable/pre-release channel switching.

### Added

- **`/diag` capture time + uptime** — a `[captured]` header line (ISO-8601 timestamp + device uptime) so a pasted diagnostics dump can be correlated with logs/events; the capabilities block stays time-free for regression-diff stability.

## v0.8.5-rc2 - 2026-07-01

### Added

- **HA Companion app auto-install / update** — ha-paneld can now install a missing Companion or update an out-of-date one over root, self-healing the render stack on these no-Play panels (the minimal Companion variant has no Play auto-update, so ha-paneld is the only update path). Fetches the latest **minimal** APK from `home-assistant/android` releases. Opt-in per panel via the new **Companion auto-update** switch (checked on the 24 h update cadence); an **Update Companion app** button forces it on demand. Leaves a Play-managed *full* Companion alone.

### Fixed

- **Never-blank-screen guard** — a stray or stale screen-off (notably a broker- or automation-**retained** screen-off replayed on reconnect) could leave a panel dark and apparently bricked, since a screen-off kills the backlight but nothing re-lit it. ha-paneld now **ignores retained inbound MQTT commands** (commands must be fresh; state/discovery stays retained), tracks whether a screen-off was **deliberate**, and runs a watchdog that re-lights an unintentionally-dark panel within a minute — while leaving a genuine, user-requested screen-off alone.

## v0.8.5-rc1 - 2026-07-01

### Added

- **Central log shipping** — optionally forward each panel's own-process logcat (its own `Log.*` output plus the Ktor/HiveMQ library logs — **no root, no `READ_LOGS`**) to a configurable sink for fleet-wide debugging without per-panel `adb logcat`. Two transports: **syslog** (TCP, RFC5424) or **HTTP** (NDJSON batches). Off by default; set the destination via `POST /config` or `provision.sh --log-host/--log-port/--log-proto`, with live status on the info page + `/diag`. Each line is redacted for tokens / passwords / URL secrets before it leaves the device; LAN-only by intent.
- **S9E GPIO diagnostic** — `/diag` now reports each gpiochip's base/ngpio/label plus the per-pin export state on Smatek S9E panels, so a missing-button-LED result can be told apart from a gpiochip-base shift.

### Fixed

- **Panels no longer get stuck offline after an HA restart or network flap** — the MQTT bridge relied solely on HiveMQ's built-in auto-reconnect, which could stall after a *transient* auth rejection during an HA/broker restart (the broker returns before its auth backend is ready) or when its reconnect thread was deferred by Android power management, leaving the panel showing "MQTT credentials rejected" and never recovering until a manual config save or app restart. Added a service-level **reconnect watchdog** that forces a fresh connection whenever the bridge stays non-connected, plus a **connectivity-regained callback** that reconnects the instant the network returns.
- **WebView age check reads the real engine version** — the *"System WebView is too old"* warning now derives the Chromium version from the WebView User-Agent (`Chrome/<v>`) instead of the package `versionName`, which a Cromite / LineageOS SystemWebView deliberately stamps to the OEM stock value to clear a signature-locked provider gate. A panel running a modern engine behind a stamped-old package (e.g. Cromite on the Tuya TPA10) is no longer falsely warned, and the info row now shows the real engine when it differs. The lookup escalates only when the package version looks old, so modern-package panels are unaffected.

### Docs

- README slimmed — reference, build and roadmap material moved into `docs/`.

## v0.8.4 - 2026-06-29

Highlights since 0.8.3: a hardened privileged helper, the full control surface on sandbox-walled (no-`su`) panels, opt-in vendor-app taming, a dashboard watchdog, an admin launcher, panel-health warnings, and preliminary Shelly Wall Display profiles. (The per-RC sections below detail the path to this release.)

### Added

- **Privileged helper hardened (security)** — the root helper (LED / true screen-off / buttons / density / CPU governor / screenshot for sandbox-walled panels) no longer listens on an unauthenticated loopback TCP port; it's now an **abstract UNIX socket authenticated by peer UID** (only ha-paneld accepted), with bounded parsing and a command-parser fuzz + unit suite gating it in CI.
- **Full control surface on sandbox-walled panels** — density, font scale, CPU governor, on-demand screenshot and the Performance / Top-processes cards now work on no-`su` panels (e.g. Tuya TPA10) via the helper, plus the ZHICAI SMT1019 `/dev/ledjni` LED. The helper installs on **every** sandbox panel now, with `/diag` flagging it if it's needed but missing.
- **Opt-in vendor-app taming** — an interactive per-package blocklist (the *Vendor taming* card) force-stops, boot-disables and strips the floating-overlay permission from intrusive vendor apps; profile-seeded candidates for NSPanel Pro, TPA10, SMT1019 and WF1589T; critical packages refused at both the app and daemon layer; default empty, fully reversible.
- **Boot-chime silencing** — `switch.<panel>_silence_boot_chime` mutes the firmware boot sound and the Companion startup notification (per-platform audio key, profile-selected).
- **Dashboard watchdog** — `switch.<panel>_watchdog` relaunches the dashboard app if it crashes or stays backgrounded.
- **Admin launcher + default-home assertion** — a built-in admin app drawer reachable from the navbar **Launcher** button; on root panels `ensureDashboardHome()` keeps the dashboard as the boot home.
- **In-app update checker** — polls GitHub releases for ha-paneld and the installed HA Companion; banners in the `:8888` UI and a `/diag` line (for no-Play panels).
- **Panel-health warnings** — the info page flags a **system WebView too old** to render the HA dashboard (banner + highlighted version + update link) and **no dashboard app detected** (soft, renderer-aware — only when none of the HA Companion, Fully Kiosk or a configured `dashboard_package` is present; ha-paneld runs fine without one).
- **Shelly Wall Display device profiles (preliminary)** — `ShellyWallDisplay` (legacy MT6580) and `ShellyWallDisplayV2` (arm64) cover the full family, wired into `detect()`, plus a daily availability + weekly Wayback monitor for the Shelly OTA endpoints. Hardware verification ongoing.

### Changed

- **Helper renamed** `hapaneld-ledd` → **`hapaneld-helper`** (it does far more than LEDs now); panels upgrade automatically on redeploy.
- **Navbar** — every mode gains a **Reload** key; narrow-mode adds pop-up brightness/volume sliders; the volume % now syncs on any external volume change; navigate-to-the-current-URL reloads instead of no-opping.
- **Font scale via the helper** on sandbox panels (previously density-only).
- **Parser fuzzer moved off CI** — runs locally on demand (`make fuzz`); UART I/O could hang the CI runner.
- **Firmware URL monitor cadence** — daily polling with a 1-hour retry if any URLs are unreachable.

### Fixed

- **Wake ANR** — proximity-triggered wake no longer calls `Su.run()` on the main thread.
- **Navbar swipe / overscan / tap pass-through** corrected.
- **Vendor-renamed critical packages** can no longer be tamed (name-normalisation guard).
- **install-daemon.sh** detects systemless (Magisk bind-mount) root correctly; long WebView renderer process names are trimmed.

### Removed

- **Thread Mesh Router (preview) deferred** — the experimental Thread NCP flash/commission preview from the rc2–rc4 prereleases is pulled from 0.8.4 pending on-hardware UART validation; the **Zigbee-router** switch is unaffected; Thread returns in a later release (0.8.6+).

### Docs

- Recorded the NSPanel Pro stock WebView version (`107.0.5304.105`, Chromium 107), confirmed on a unit freshly flashed to firmware 3.5.1.

## v0.8.4-rc6 - 2026-06-29

### Added

- **Panel-health warnings** — the `:8888` info page now surfaces the states that silently stop a panel from showing the dashboard. **System WebView too old**: if the WebView's Chromium major is below the HA-frontend threshold (matches `provision.sh`'s `check_webview`), a banner explains why the dashboard renders blank/broken and links the update steps, and the version is highlighted in the panel-information table (a Cromite-swap caveat is noted, since that reports the stale OEM version). **No dashboard app detected**: a soft, renderer-aware notice that fires only when none of the HA Companion, Fully Kiosk (`de.ozerov.fully`), or a configured `dashboard_package` is installed — ha-paneld itself runs fine without one. Available-update notices already banner here. Decision logic is pure and unit-tested (`PanelHealthTest`).

### Removed

- **Thread Mesh Router (preview) deferred** — the experimental Thread NCP flash + commission support previewed in the `0.8.4-rc2`–`rc4` prereleases has been pulled from 0.8.4. Driving the EFR32MG21 radio to a working OpenThread NCP needs more on-hardware validation (finalising the firmware's Spinel UART pin configuration), so it's parked rather than shipped half-done. The **Zigbee-router** switch is unaffected. Thread support is planned to return in a later release (0.8.6+).

## v0.8.4-rc5 - 2026-06-26

### Added

- **Shelly Wall Display device profiles (preliminary)** — two new profiles cover the full Shelly Wall Display family: `ShellyWallDisplay` (legacy MT6580, covers Stargate/4" + Atlantis + Pegasus/X2-6.9") and `ShellyWallDisplayV2` (arm64, covers Blake/XL + Jenna/X2i + Cally/XLi + Maverick/U1 + Dayna/D1). Both are wired into `detect()`. Deployment requires ADB (all models) or the Shelly AppStore (modern/v2.6.0+). Hardware verification still in progress — relay control routes through the HA Shelly integration (not sysfs), no root on any model.
- **Shelly Wall Display firmware monitor** — daily availability monitor and weekly Wayback Machine archival for the Shelly Wall Display OTA endpoints, surfaced in Discussion #14.

### Changed

- **Firmware URL monitor cadence** — switched to daily polling (was hourly) with a 1-hour retry if any URLs are unreachable.

## v0.8.4-rc4 - 2026-06-26

### Added

- **Boot chime silencing** — an opt-in toggle (`switch.<panel>_silence_boot_chime`) suppresses the Sonoff start-up sound via `Settings.System`, surfaced as an MQTT entity and HTTP Controls-card switch.
- **Dashboard watchdog** — if the dashboard WebView crashes or is moved to the background, ha-paneld relaunches it automatically. Configurable delay; no root needed.
- **Admin launcher** — a minimal on-demand launcher (long-press the ha-paneld notification, or `POST /admin`) pops up for installing apps and changing settings while keeping the dashboard as the default home. Dismisses itself when done.
- **In-app update checker** — polls the GitHub releases API 30 s after service start and every 24 h; shows a banner in the web UI and a `/diag` line when a newer stable version is available for ha-paneld or the installed HA Companion build.

### Changed

- **Navigate reload** — navigating to the URL already displayed now reloads the page (previously a no-op), and the navbar gains a dedicated reload button for panels without a gesture bar.
- **Navbar volume % always in sync** — volume percentage now updates immediately when any external source (HTTP Controls card, HA-driven media player) changes the volume, not only on navbar ± presses.
- **Navbar brightness/volume sliders** — narrow-mode layout adds compact slider controls alongside the ± buttons.

### Fixed

- **Wake ANR eliminated** — proximity-triggered wake was calling `Su.run()` on the main thread; moved to a background coroutine.
- **Top-process name truncated** — long WebView renderer cmdlines (e.g. `com.android.webview:sandboxed_process0:org.chromium…`) are now trimmed to the package prefix.
- **install-daemon.sh root detection** — systemless root (Magisk overlay) is now detected correctly on panels whose `/system` is a bind-mount.

## v0.8.4-rc3 - 2026-06-25

### Added

- **Tame candidates for TPA10, SMT1019, WF1589T** — device profiles now seed curated tame candidates for panels beyond the NSPanel Pro so the vendor-taming picker has profile-informed suggestions on those panels.

### Fixed

- **Navbar swipe, overscan, tap pass-through** — directional swipe detection corrected (was triggering on vertical flings); always-on bar respects display overscan insets; taps on the bar no longer pass through to the content layer behind it.
- **Vendor-renamed critical packages protected** — packages that pass the is-critical check by their declared name but ship under a vendor-renamed package ID could previously be tamed; an additional name-normalisation guard closes that gap.

### Changed

- **Parser fuzzer moved off CI** — the helper command-parser fuzzer runs locally on demand (`make fuzz`) rather than as a CI step; UART I/O could hang the CI runner indefinitely with no timeout.

## v0.8.4-rc2 - 2026-06-25

### Added

- **Opt-in vendor-package taming** — a new Configure card surfaces an interactive per-package tick list of vendor apps that can be **force-stopped, boot-disabled, and stripped of the floating-overlay permission** so they can't draw above the dashboard. Profiled panels (NSPanel Pro) show the profile's curated candidates (e.g. `com.eWeLinkControlPanel`, unticked by default); generic panels enumerate live by overlay/launcher heuristic. Ticking and un-ticking applies immediately on Save (no reboot). Reversible: unticking re-enables. Critical system packages (`android`, `com.android.systemui`, etc.) are refused at both the app and daemon layer regardless of input. Default is empty — nothing is ever touched until the user opts in. Motivated by the Sonoff/CoolKit control-panel app drawing a floating widget over the dashboard after a firmware update.

### Fixed

- **Font scale on sandbox-walled panels** — display-sizing font-scale changes now route through the helper daemon when `su` is unavailable (TPA10), matching the existing density path. The stale "root only" note in the display-sizing card and `docs/display-sizing.md` is corrected.

## v0.8.4-rc1 - 2026-06-24

### Security

- **Privileged root helper hardened** — the helper that performs the root actions a sandboxed app can't (LED, true screen-off, hardware buttons, display density, CPU governor, screenshots, perf snapshots) **no longer listens on an unauthenticated loopback TCP port**. It previously bound `127.0.0.1:8889`, which **any** local app holding `INTERNET` could connect to and use to `REBOOT` the panel, change the CPU governor / display density, or `SCREENCAP` the screen — a real privacy + denial-of-service surface. It now uses an **abstract-namespace UNIX socket authenticated by peer UID** (`SO_PEERCRED`): only ha-paneld itself is accepted (plus root/shell for adb), and every other local app is rejected before it can issue a single command. Hardened further with airtight bounded parsing (exact-match commands, width-bounded arguments, overlong lines dropped not mis-split), all command execution funnelled through **one audited seam** with whitelisted arguments, connection caps + idle timeouts, and a **command-parser fuzzing + unit-test suite** against hostile local input.

### Added

- **Full control surface on sandbox-walled (no-root-shell) panels** — panels that can't `su` (e.g. the Tuya TPA10) now get, routed through the privileged helper, the controls that were previously root-shell-only: **display density** and **font scale**, **CPU governor**, **on-demand screenshot** (info page + `/screenshot.png` / HA camera image), and the **Performance / Top-processes / Responsiveness** cards.
- **Helper is now the control path for every sandbox panel, not just LED ones** — a panel's profile declares `usesDaemon` independently of its LED mechanism, so the daemon is installed on any no-`su` panel. `/diag` flags *needed-but-missing* so a sandbox panel without the daemon surfaces clearly.
- **ZHICAI SMT1019 RGB LED** — the LED ioctl (`/dev/ledjni`) is root-only; the helper now drives it so the LED works on the SMT1019 (it was reported unavailable in 0.8.3).
- **Proximity calibration** — device profiles can declare a default polarity; user-captured near/far calibration is stored per panel.
- **TPA10 vendor-app disable offer** — provisioning detects the Tuya vendor stack and offers a one-tap disable.
- **WebView age warning** — provisioning warns when the system WebView is too old to render the HA frontend, with a link to the sideload instructions.

### Changed

- **Root helper renamed `hapaneld-ledd` → `hapaneld-helper`** and restructured — the binary, its UNIX socket (`@hapaneld-helper`), and init service (`hapaneld_helper`) were renamed to reflect its broader role. Source split by capability; all command execution behind one audited seam. **No behaviour change — but panels running the helper must be redeployed**: `install-daemon.sh` removes the old `hapaneld-ledd` install automatically.

## v0.8.3 - 2026-06-19

### Changed

- **Lower-latency root actions** — root commands now run through a single long-lived `su` shell instead of forking `su` afresh per call (each fork+auth cost ~200–300 ms). Navbar **Back / Launcher / Recents** and other root-gated actions respond noticeably faster. Transparently falls back to a per-call `su` if the persistent shell is unavailable or a command stalls, so it's never worse than before.
- **Navbar auto-hide lingers longer** — the *Swipe reveal* bar now stays ~5 s (was 4 s) before sliding away.
- **Info-page card headers demarcated** — each card's title is now a subtly set-off header bar (slight tint + divider), separating it from the content. Groundwork for upcoming drag-to-reorder + collapsible cards.

### Added

- **Backlight no longer idle-dims** — several panel firmwares dim the hardware backlight at the screen-off timeout even while the OS keeps the screen on, so the panel went very dim ~60 s after the last touch despite full brightness. ha-paneld now holds the screen-off timeout high to defer that, **on by default** (`switch.<panel>_prevent_idle_dim`) — these are mains-powered wall panels; turn it off to restore the firmware's own dimming. No root needed (`WRITE_SETTINGS`).
- **Live screenshot in the info page + `/screenshot.png`** — the HTTP UI now shows a live panel screenshot (root `screencap`), scaled to a single column and **click-to-open full size** in a new tab. The `/screenshot.png` endpoint is also usable directly as a Home Assistant camera `still_image_url` / Picture-card image. LAN-only (like the rest of the surface), captured on demand — no background polling. Root required.
- **Smatek S9E proximity** — the S9E's `SensorManager` proximity registers but never delivers events, so `binary_sensor.<panel>_proximity` (and wake-on-wave) didn't work. ha-paneld now reads the raw proximity GPIO (gpio18: 1 = near, 0 = far) over root instead, on panels whose profile declares one. Reporter-confirmed (GitHub #5).
- **Touch-click feedback** — an opt-in click on every screen tap, produced by ha-paneld itself: a 1 px system-overlay touch watcher catches each tap (without consuming it) and plays the OS key-click via SoundPool, so it works even on the WebView dashboard where Android's own touch sounds never fire. Rides the existing `switch.<panel>_touch_sound`.
- **ZHICAI SMT1019 (WF2489T) support** — a dedicated profile for the rk3576 ZHICAI SMT1019 (`ro.product.device` `WF2489T`), a locked-down unit with no root and a firmware-restricted RGB LED (the `/dev/ledjni` ioctl is denied to sandboxed apps). The profile declares the LED unavailable so it's no longer mis-reported as present, uses the no-root screen-off path, and labels the device correctly. From the reporter's /diag (GitHub #8).

### Fixed

- **Touch sound re-applied at startup** — the touch-sound switch raised the system-stream volume only when toggled, so a panel that booted with it already enabled stayed silent (volume left at 0); it's now re-applied on boot.
- **Navbar brightness/volume now sync to Home Assistant** — stepping brightness or volume from the soft navbar changed the panel but never published the new state, so `light.<panel>_screen` and `number.<panel>_volume` went stale in HA. Both now publish on change.
- **Navbar volume ± reliable on all panels** — on a panel whose audio stream reports a small max number of steps, the old percent round-trip could round back to the *same* raw level, so a tap changed nothing and the system volume slider never appeared. The buttons now step the raw stream level directly, so every tap moves the volume **and** flashes the slider.
- **No navbar flash on auto-hide** — after the bar slid off the bottom edge it could flash back into view for a single frame before disappearing; it now hides cleanly.
- **Swipe-reveal no longer scrolls the dashboard** — a swipe-up to reveal the navbar could also scroll/displace the dashboard behind it (blank space at the bottom, top cropped). The reveal strip now consumes the whole gesture (it was letting un-consumed move events fall through), and its capture zone is taller so a fast off-screen swipe lands on it reliably.
- **Opening the config UI no longer crashes the daemon** — the foreground service re-ran its full startup on every `onStartCommand`, so anything that re-issued `startForegroundService` (e.g. opening the app) started a *second* HTTP server, which threw `BindException: Address already in use` on `:8888` and killed the whole process (taking down MQTT, sensors and the navbar with it). Subsystem startup is now one-time per service instance.
- **Screenshot card no longer reflows the info page** — the live screenshot reserves the panel's aspect-ratio box up front (a loading shimmer fills it, and stops with a glyph if the capture fails), so the image arriving no longer shoves the rest of the page down.
- **Proximity no longer stuck "near" on binary sensors** — the uncalibrated binary reading assumed the far value equals the sensor's max range, but some panels report far=1 with max=9 so it read NEAR forever; near is now `raw < 0.5` (0 = near).
- **Binary proximity card hides its no-op controls** — on a binary sensor there is nothing to calibrate, so the Capture / Sensitivity / Reset row is hidden and the card just shows the live near/far state and a one-line note.
- **Screen brightness reflects (and drives) the real backlight** — ha-paneld now reads and writes the hardware backlight node directly, not just the Android `SCREEN_BRIGHTNESS` setting, so HA shows the actual backlight level and the slider moves it on panels whose firmware idle-dims the backlight behind the OS.
- **Launcher button opens an actual launcher** — it no longer lands on the Home Assistant app (which registers as a home screen); it prefers the device's real default launcher and skips dashboard/kiosk home apps.
- **Wake-on-wave updates the screen entity in Home Assistant** — a proximity wake now publishes the ON state, so `light.<panel>_screen` no longer stays OFF after the screen wakes (reported in GitHub #6).
- **Zigbee no longer reported as "none" on 4.x firmware** — the gateway is detected by its `zgateway` binary, covering the 4.x `/vendor/bin/siliconlabs_host/run.sh` layout the old marker-file check missed.
- **Narrower gutters in a single-column layout** — on a one-column display the info page uses small page + inter-card margins instead of the desktop spacing, reclaiming width.

### Docs

- **F-Droid install guide** — [docs/fdroid.md](docs/fdroid.md): add the ha-paneld F-Droid repository and install / auto-update straight on the panel, no PC.

## v0.8.2 - 2026-06-16

Adds a soft on-screen navigation bar for panels whose firmware hides the native one, and completes Smatek S9E hardware support.

### Added

- **Soft navigation bar** — an on-screen overlay bar (`select.<panel>_navbar`: **Off / Always on / Swipe reveal**) for panels whose firmware suppresses the native Android navbar (e.g. NSPanel Pro). Buttons: **Back**, **Launcher** (the device launcher/app-drawer), **Recents**, and — since these panels have no physical keys — **Brightness ±** and **Volume ±** (tap to step, press-and-hold to ramp; volume shows the system slider). On wide panels (e.g. the landscape TPA10) the live brightness/volume **percentage** shows between each ± pair. Back/Recents fire via root `input keyevent` where the app can `su` (no accessibility service needed), falling back to the accessibility service only where su is sandbox-blocked; **Recents is omitted on panels whose firmware has no overview screen** rather than presenting a dead control. Button presses show a highlight **held until the action completes** (so a ~250 ms root key-injection doesn't look ignored), and *Swipe reveal* hides the bar behind a bottom-edge strip that slides it up on touch and auto-hides.
- Drawing the overlay needs `SYSTEM_ALERT_WINDOW`; it's self-granted via in-app `su` on SuperSU panels, and `provision.sh` grants it for sandbox-walled panels (Tuya TPA10) that can't.

### Fixed

- **Smatek S9E now detected** — it was falling back to the generic profile (reported via its generic `rk3566_r` Build fields), which hid its two mains relays and four button LEDs. Detection now also matches the vendor model code in `ro.product.version` (`S9…`), so the S9E picks up its profile (relays, button LEDs at gpio 147–150, Smatek/S9E labels).
- **S9E relays visible on current firmware** — the relay sysfs class was renamed `st_relay` → `strelay` between S9E firmware 1.0.2 and 1.1.0, so ha-paneld now probes both names and uses whichever the panel actually exposes. Previously only the original 1.0.2 image worked; relays now surface on the 1.1.0+ firmware most panels ship. *Still untested on hardware.*
- **S9E button LEDs export their GPIOs** — gpio 147–150 aren't exported at boot (the firmware exports only gpio113), so the LED nodes didn't exist and the lights never appeared; ha-paneld now exports (and sets to output) each pin on demand before use. *Still untested on hardware.*

## v0.8.1 - 2026-06-10

Locks the HTTP control surface to the local network, makes the info page screenshot-safe, and adds a one-click jump to the panel's Home Assistant device page.

### Added

- **LAN-only control surface** — the HTTP API/UI (`:8888`) now refuses any request whose source isn't local (loopback / RFC1918 / link-local / IPv6 ULA). On a dual-stack panel this **closes the surface off from the public internet over a routable IPv6**, rather than relying on the home router to firewall inbound v6.
- **Screenshot-safe info page** — identity and network values (Device ID, MQTT broker, globally-routable IP/IPv6) are **blurred by default**; click a value, or the **Reveal** toggle, to show them (auto re-blurs). Only *globally-routable* addresses blur — a LAN RFC1918 / ULA address stays visible. Config-form fields blur the text only (the field outline stays crisp, and focusing a field reveals it for editing).
- **"Open in Home Assistant"** — when the panel's MQTT credentials are also a Home Assistant user, the info page resolves the panel's **own HA device page** and links straight to it. Works for **non-admin** HA users and across reverse-proxy / tunnel setups (it resolves the device id from HA's entity registry over the WebSocket API, and finds HA via the MQTT broker host when mDNS can't reach it).
- **Screen diagonal** on the info page — calculated from resolution + dpi (assuming square pixels); click to toggle inches ↔ cm, with width × height on hover.
- **Recommended display density per model** — the density "rec" button now suggests **160 dpi (86P) / 250 dpi (120P)** at text-scale 1.0.

### Fixed

- **Config-form race** — saving the config form twice in quick succession could leave MQTT stopped or half-connected (the "resubmit until it sticks" symptom); config reloads are now serialized so they can't interleave. The setup banner no longer reports "needs the MQTT broker" while the broker is merely mid-reconnect.

### Docs

- First **unit-test harness** — JVM tests (no emulator) covering the config-reload serialization and the setup-banner logic, so those regressions are guarded.

## v0.8.0 - 2026-06-08

On-panel auto-brightness, a major hardening of NSPanel Pro hardware support, and an overhauled info / diagnostics page.

### Added

- **Auto-brightness** *(opt-in)* — `switch.<panel>_auto_brightness` drives the backlight from a lux stream (the panel's own ambient-light sensor, or HA-fed `number.<panel>_ambient_lux` on sensor-less panels), with an asymmetric response (snappy on lights-on, smoothed on daylight drift) and a Dimmer↔Brighter bias. Off by default.
- **Controls card** (software nav bar) — Back / Recents / Launcher (reach Settings & other apps), plus Vol−/Vol+ and a confirmed Reboot, for panels with no physical nav bar. Each button **disables itself when its capability is absent** (Back/Recents need the accessibility service; Launcher/Reboot need root).
- **Auto-reload on update** — an open info-page tab reloads itself when the app is updated (reload-banner fallback while you're mid-edit in a field).
- **Debug sensor trace** *(instrumentation)* — `GET /sensortrace` exposes a RAM ring-buffer of raw lux / proximity samples + the auto-brightness internals (CSV or JSON) for filter fit-testing.

### Changed

- **Panel info reorganised** into separate cards — **Panel information**, **Networking**, **ha-paneld profile** (the device-profile-declared rows, linked to the profile source), alongside **Capabilities** — so it scales across columns instead of one ever-growing card.
- **Richer hardware readout** — a **Model · firmware** row (distinguishes 86P / 120P / 86P-Gen2), Zigbee provenance, **total eMMC** storage (matches the box spec), and Light / Proximity rows showing **technology · value-type · range** (e.g. `Infrared · Integer · 0–9 cm`). Proximity graded-vs-binary is decided authoritatively from the firmware version where the profile knows the rule.
- **Info-page layout** rebuilt on native CSS multi-column masonry — balanced columns with near-zero layout-shift (CLS) from phone to 15″ panels; live tables wrap full values (touch can't hover a tooltip); dark-mode polish throughout.
- **Diagnostics (`/diag`) rewritten** — terse, version-stamped, reflects every field ha-paneld detects, **safe to paste into a public issue** (network addresses + instance identifiers omitted), and structured for ingest into a regression-test harness.
- **Network ADB readout** distinguishes *active now* from *persistent* (survives a reboot).

### Fixed

- **NSPanel Pro Zigbee** — detects both the stock **vendor-native** gateway and the NSPanelTools-managed one; the `switch.<panel>_zigbee_router` works on both, an explicit *off* persists across reboot, and the **120P vendor-guard CPU-spin** is resolved (ha-paneld no longer starts a duplicate guard).
- **Accurate relay reporting** — Gen1 NSPanel Pro (86P / 120P) correctly shows **no relays**; the PX30 kernel's phantom `st_relay` nodes are no longer mistaken for hardware.

### Docs

- **Firmware backup & restore guide** for button-less (Rockchip) panels.

## v0.7.1 - 2026-06-07

Hardware buttons, CPU/display controls, and per-panel identity.

- **Hardware buttons instrumented via the daemon's evdev reader** — keys Android doesn't deliver to
  apps now reach HA:
    - **WF1589T power button** — ha-paneld **suppresses the button's built-in screen-lock** and
      instead publishes each press as an `event.<panel>_button` event, so its action is **decided by
      Home Assistant** (an automation), not hard-wired to lock the panel. (The PMIC's long-press
      hardware power-off is unchanged.)
    - **TPA10 5th (orange) button** — reports `SW_MUTE_DEVICE` (a switch, not a key), which is why
      stock firmware never surfaced it; now published as a `KEYCODE_MUTE` event.
- **CPU profile tiers** — the CPU governor select is now three intent-based options — **Performance /
  Efficiency / Auto** — instead of raw kernel governor names. Auto maps to the SoC's dynamic governor
  (ramps up on interaction, idles low — best for a mains, 24/7 panel).
- **Display sizing** *(experimental / R&D)* — set display **density** and **text size** to match an HA
  dashboard to a desktop browser (Android panels often ship these mismatched to the physical screen).
  Root panels only; the right per-panel values aren't dialled in yet — see
  [docs/display-sizing.md](docs/display-sizing.md).
- **Per-panel HA device identity** — manufacturer/model defaults per panel (Sonoff / NSPanel Pro,
  Tuya / TPA10, Electron / WF1589T, Smatek / S9E; inferred from `Build.*` on unknown panels). The
  default model carries a " (ha-paneld)" suffix so the device is distinguishable from a co-installed
  integration managing the same hardware; the Configure form's value overrides it verbatim.
- **Theme-aware App UI** — the on-panel standing screen follows the panel's light/dark setting.
- **Stable HTTP performance table** — optional rows latch with a `–` placeholder instead of vanishing,
  so the page no longer jumps as metrics come and go.
- **README hero renders on both GitHub themes** (light/dark `<picture>` wordmark).
- **Security policy** — `SECURITY.md` + GitHub Private Vulnerability Reporting enabled.
- **Docs** — TPA10 hardware doc now covers all three button classes, incl. the recessed pin-hole
  (Rockchip factory-reset / MASKROM-loader, not a Linux input).

## v0.7.0 - 2026-06-06

Architecture-focused release (no new entities).

- **Device-profile architecture** — each supported panel now has a single canonical silo
  (`device/<panel>.kt`) declaring its quirks/paths; the LED, Zigbee and relay controllers read the
  active profile (detected once at startup) instead of hardcoding device specifics, while still
  runtime-probing to confirm. An unrecognised panel falls back to a Generic profile and works for
  whatever it physically has. The detected platform is shown on the info page. No change to the HA
  entities. Design: [docs/architecture/device-profiles.md](docs/architecture/device-profiles.md).
- **Security hardening** — the Zigbee role-switch is allowlisted before any shell interpolation; the
  security posture (LAN-trust, network-layer access control, HA-auth as the future path) is documented
  in [docs/architecture/security.md](docs/architecture/security.md).
- **Docs** — a "Why not Fully Kiosk?" section; releases are now cut as contenders (tagged only on
  approval), per [docs/RELEASING.md](docs/RELEASING.md).

## v0.6.3 - 2026-06-05

Small fixes and polish; documents the 0.7.0 roadmap.

- **Better entity icons** — `mdi:adb` for Network ADB and `mdi:monitor-dashboard` for Navigate
  (the previous `mdi:android-debug-bridge` isn't a valid MDI name and rendered blank).
- **Fleet update fix** — `scripts/update-fleet.sh` no longer reads panels from a non-tty stdin when they
  were given as args (a pipeline/CI stdin had clobbered the panel list).
- **Roadmap** — documents the **0.7.0 device-profile architecture refactor** (architecture only, no new
  features): [docs/architecture/device-profiles.md](docs/architecture/device-profiles.md). DLNA renderer
  reframed as under-consideration (it would be a separate HA device; the TTS recipe already covers announce).

## v0.6.2 - 2026-06-05

New controls (root/su panels):

- **CPU governor** (`select.<panel>_cpu_governor`) — set the scaling governor across all cores
  (powersave ↔ performance) to trade panel heat/noise against dashboard responsiveness; options come
  from the kernel's `scaling_available_governors`. Automatable from HA (e.g. powersave when empty).
- **Persistent network adb** (`switch.<panel>_network_adb`, opt-in) — keep `adb tcpip 5555` across
  reboots via `persist.adb.tcp.port`, plus a `provision.sh --persist-adb` flag. Leaves a standing LAN
  adb port, so it's off by default.
- **Smatek S9E button LEDs** (`light.<panel>_button_led1..4`) — the four button LEDs (gpio147–150).
  Experimental/untested like the rest of S9E.

Other changes:

- **IPv6** — the HTTP server now binds dual-stack (`::`), so the panel UI/API answer on IPv6 as well as
  IPv4, and the info page shows the panel's IPv6 address.
- **Navigate is local-only** (`text.<panel>_navigate`) — any scheme + host is stripped from the posted
  value and navigation is driven via the in-app `homeassistant://navigate/<path>` deep link. This stops
  the HA Companion opening a disorienting WebView for external URLs; the entity now holds a local path
  (defaults to `/` instead of `unknown`).
- **More robust Zigbee gateway detection** — the router switch is now gated on the guard script ha-paneld
  actually invokes, not just the `package_version` marker. This still shows on a configured panel that
  lost only the marker, and correctly hides on panels left with an empty `siliconlabs_host` dir + an
  orphaned `zgateway` (where ON could not restart it and it wouldn't survive a reboot).
- **Zigbee toggle no longer blocks MQTT** — the slow vendor lifecycle (OFF ~8 s; ON's gateway spawns on
  a ~30 s timer) now runs off the MQTT callback thread, publishing optimistically then reconciling to
  the real running state (polling for the slow start).
- **Fleet updates that don't leave panels dead** — `adb install -r` puts the app in Android's *stopped*
  state, which never auto-starts (not even via `START_STICKY`) until something launches it, so a bare
  install loop leaves panels installed-but-dead (entities `unavailable` in HA). `scripts/provision.sh`
  now retries the launch and explains why it's mandatory, and a new `scripts/update-fleet.sh` wraps
  provision.sh across many panels (downloading the release once) so every panel is installed **and
  launched and verified**.
- Docs: refreshed the on-panel launcher screenshot to the v0.6.x responsive UI (480×480).

## v0.6.1 - 2026-06-05

Zigbee router control for the Sonoff NSPanel Pro (the only panel with a Zigbee radio).

- **`switch.<panel>_zigbee_router`** — turn the panel's built-in Silicon Labs EFR32 into a Zigbee
  router/repeater (extends an existing mesh) and back off. ON starts the Sonoff `zgateway` host stack
  (kept alive by its own supervisor) and ensures router mode; OFF stops it and frees the radio.
- **Info-page Zigbee row** — shows the installed gateway driver and version (e.g. `sonoff 3.7.1`),
  whether it's running, and the current network role.
- Implementation is local and credential-free: ha-paneld talks to the panel's on-device mosquitto
  broker (`zigbee/system/network-role/…`); no firmware reflash, no `/dev/ttyS5` handling. The router
  appears as a normal device in your ZHA/Zigbee2MQTT coordinator, with its own signal/last-seen there.
- **Smatek S9E (barebones, experimental/untested)** — on-board **relays** as `switch.<panel>_relay1/2`
  (`/sys/class/st_relay`, gated on presence), and the four buttons reported as `event.<panel>_button`
  (`KEYCODE_F1`–`F4`). Derived from vendor paths in seaky#98; no S9E was available to validate, and the
  relays switch mains loads — treat as experimental. Button LEDs + proximity radar documented, not yet
  wired. See [docs/hardware/s9e.md](docs/hardware/s9e.md).

## v0.6.0 - 2026-06-05

New entities and on-panel UX — all app-side (no daemon or root changes).

- **Temperature + humidity sensors** — `sensor.<panel>_{temperature,humidity}` where the panel has
  them (e.g. the TPA10's CHT8305), read via `SensorManager`. Recorder-friendly (on-change only, sane
  deltas + min interval, rounded values); the illuminance gate was also relaxed (2 s → 15 s).
- **Button-backlight light** — `light.<panel>_buttons` (brightness) on sysfs-LED panels, via the
  helper daemon.
- **Remote nav actions** — `button.<panel>_{back,recents}` via the accessibility service
  (`performGlobalAction`); uniform from HA dashboards/scripts, no root.
- **Wake-on-wave** — local, instant screen wake on proximity-near (`switch.<panel>_wake_on_wave`,
  default on where a proximity sensor exists). Sleep stays HA's job.
- **Auto-return to dashboard** — after an app update the launcher UI bounces back to the Home
  Assistant app once connected (touch cancels; configurable).
- **Config QR code** — scan the panel's config URL instead of typing it; the App UI is now responsive
  (fits 480×480 without scrolling, larger on roomy panels).
- **Touch-sound switch** — `switch.<panel>_touch_sound` for consistent UI click sound across the fleet.
- **TTS recipe** — `docs/tts.md`: render with any HA engine (Piper/Cloud) → panel `/play`.

## v0.5.1 - 2026-06-05

- **Removed the device admin.** It was effectively unused — screen-off already powers the backlight
  via the root helper daemon / `su` (`bl_power`), never `lockNow()` — but once activated on an older
  build it **blocked the app's own uninstall** (`DELETE_FAILED_DEVICE_POLICY_MANAGER`), and Android
  won't remove a non-test admin via `dpm`. Dropping it removes that trap entirely; fresh installs are
  never affected. Upgrading from a build where you'd activated it: deactivate it under **Settings →
  Security → Device admin apps** before uninstalling.

## v0.5.0 - 2026-06-05

The first release aimed at general use — a redesigned web UI, an in-app config screen, deeper
performance insight, and proper release signing. Especially for Tuya TPA10 owners.

**Web UI**

- Redesigned as a responsive **masonry dashboard** — single column on a phone, multiple columns on a
  wide screen, with full-width charts and labelled gridlines you can actually read.
- A **new app icon** — a wall panel showing the Home Assistant mark.
- **REST API explorer** at `/api` (try any endpoint from the browser) plus an OpenAPI spec at
  `/openapi.json` you can import into Swagger or Postman for fleet tooling.

**On the panel**

- Tapping the launcher icon now opens a proper **info screen** (status, the config URL, and buttons to
  open configuration or the Home Assistant app) instead of dropping back to the launcher.
- **Configure on the panel itself** — the config page opens in an in-app browser, so kiosk panels with
  no browser installed still work.

**Performance**

- **CPU clock** (current vs hardware max) so thermal throttling is visible, plus a **responsiveness**
  metric that reflects how the dashboard actually feels.
- **Top processes** — live top-5 by CPU, to confirm the dashboard is getting the CPU. Needs root.
- **1-click WebView DevTools** relay for deep dashboard debugging — no `adb` cable needed.
- Instrumentation now **only samples while you're viewing the page** and can be **switched off**, so
  the performance tool isn't itself a constant background cost.

**Proximity**

- Calibrate the near/far cutoff from the web page. A guided "capture near / capture far" flow handles
  the wildly different sensor scales and inverted polarity across panels; sensitivity (High/Med/Low)
  controls flap resistance. The raw value stays on the panel (shown live); only a clean on/off goes to
  Home Assistant, so a graded sensor can't flood the recorder. Fixes proximity being effectively dead
  on panels whose raw value exceeds the reported sensor range.

**Fleet & releases**

- **Config API** — read/update any setting over HTTP (`GET /config`; partial-merge `POST /config`).
- **One-command provisioning** (`scripts/provision.sh`) — install, grant permissions, set id/MQTT;
  idempotent, with a `--verify` check; runs on Linux, macOS, and Windows (Git Bash/WSL).
- Releases are now **signed with a dedicated release key**; the README documents the signing model and
  the build/fork steps.

## v0.4.2 - 2026-06-04

Self-diagnostics, so you can tell what works on *your* panel — and get help when it doesn't.

- **Capabilities matrix** on the web page: every feature (screen-off, LED, buttons, root, brightness)
  shown as working / degraded / unavailable, each with the exact command to fix a shortfall on your
  firmware.
- **`/diag` dump** — one-click hardware / firmware / SELinux / sensor report to paste into a bug
  report, so a panel can be diagnosed without the maintainer owning that hardware.
- New **[performance tuning guide](docs/performance.md)** — why HA wall panels get slow (the
  WebSocket event firehose) and how to fix it.

## v0.4.1 - 2026-06-04

First public release. Live diagnostics and navigation for keyless panels.

- **Live performance view** on the web page — CPU / GPU / RAM history chart, load average, temperature.
- **Launcher** and **Home** buttons for panels with no physical Home/Back keys.
- **True screen-off** on Sonoff PX30 panels (real backlight-off, not just dimming) — no lock screen / PIN.
- HA device card enriched with configurable manufacturer / model, firmware and serial, plus a **Visit**
  link to the panel's web page.
- MQTT auto-reconnect and retained-state restore after a broker or panel restart.

## v0.4.0 - 2026-06-04

Hardware control across panel types, and a configuration web page.

- **RGB LED**: clean-room native driver for rk3576 panels (`/dev/ledjni` — no vendor blob, no root)
  and a root helper daemon for sysfs-LED panels (Tuya TPA10).
- **Panel web page** for status and configuration — no per-device app UI needed.
- **True screen-off** and **boot-persistence** for the helper daemon.
- Reproducible **Docker build** so anyone can build the APK without a local toolchain.

## v0.3.0 - 2026-06-03

The control API — Home Assistant can now drive the panel, validated on real hardware.

- **Brightness** and **sleep / wake** (`light.<panel>_screen`).
- **RGB LED** (`light.<panel>_led`), **navigate-to-URL** (`text.<panel>_navigate`), and
  **hardware-button events** (`event.<panel>_button`).
- **TTS volume** control (0–100%); TTS plays on the media stream, not the quiet accessibility stream.
- **Reload dashboard** and **reboot** buttons (root).
- Panel **light + proximity** sensors exposed to HA.
- Builds signed with a stable keystore so updates install in place (no uninstall dance).

## v0.1.0-MVP - 2026-06-03

Initial release — the TTS receiver, reimplemented as a proper Android app.

- **`POST /play`** TTS-announce HTTP contract (replaces the bash receiver).
- **MQTT auto-discovery**, **mDNS** advertisement, and a foreground service that survives reboots.
