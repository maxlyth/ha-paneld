# Roadmap

The curated public roadmap for ha-paneld. The [README](../README.md#status--roadmap) carries a short
"where it's heading" summary; this is the full list. Shipped work lives in
[CHANGELOG.md](../CHANGELOG.md). Nothing here is a dated commitment during the v0.x line — it's the
direction, ordered roughly by priority within each tier.

## Planned

- **Proximity-calibration capture UX** — the capture flow is still unintuitive and **fails quietly**.
  Make it a clear guided near→far flow with a live raw readout and an explicit **calibrated ✓ /
  incomplete ⚠** state. Two concrete traps to fix: capturing only *near* leaves it `calibrated:false`
  with no visible cue (the user thinks they've set it but haven't); and **wake-on-wave silently does
  nothing until calibration is complete** — the uncalibrated fallback (`raw < maxRange`) doesn't fit the
  NSPanel Pro's graded sensor (raw ~50–100, range 9 cm), so a wave never registers as "near". Surface
  "not calibrated → wake-on-wave inactive" prominently, and/or give the uncalibrated fallback a sensible
  default for graded sensors. (Gauge auto-ranging shipped in 0.8.0.)
- **More performance tooling** — deeper on-device instrumentation to measure, diagnose and tune
  dashboard performance on weak panels.
- **Central log shipping** — optionally forward panel logs to a central logging server for fleet-wide debugging: the Android system log (`logcat` / ha-paneld's own service log) **and** the dashboard **browser console** (WebView JS console + errors, captured over the existing CDP path). Avoids per-panel `adb logcat` to chase a remote issue. Configurable sink (syslog / HTTP / Loki-style) and **opt-in** — logs can carry tokens and URLs, so redaction + LAN-only by default.
- **Auto-brightness self-calibration** — an opt-in mode that records the panel's ambient-light sensor and any manual brightness corrections over a **24-hour cycle**, then fits the lux→backlight curve to the room's real day/night light profile instead of today's hand-tuned default + bias. Turns the fixed curve into a learned, per-panel one; re-runnable when the panel moves or the room's lighting changes.
- **MQTT TLS (with self-signed support)** — the HiveMQ client supports TLS, but ha-paneld connects **plaintext** only today (`tcp://…:1883`). Add TLS with **zero-config autodiscovery**: a TLS-first probe (8883, or the configured port) that falls back to plaintext (1883) if the handshake fails, plus explicit `ssl://` / `mqtts://` scheme handling. For the common home **self-signed** broker, fail back gracefully — **trust-on-first-use** (pin the presented cert's fingerprint, shown in the `:8888` UI to confirm), a **user-supplied CA** upload for the strict path, or a clearly-labelled **opt-in "accept self-signed / insecure"** toggle — never a silent trust-all (MITM footgun).
- **Reload returns to the current dashboard (+ per-panel home dashboard)** — the reload control (`button.<panel>_reload`) hard-restarts the dashboard app (`am force-stop` + relaunch), which cold-starts onto the Companion's **default** dashboard and loses the current view. Keep the hard restart (it's the right recovery for a wedged/frozen WebView — the classic reason panels get nightly-rebooted), but **re-navigate to the intended dashboard once the WebView is back up** (handling the cold-start timing — the deeplink must fire after the frontend loads). Introduce a per-panel **home dashboard** setting as that target, so reload, auto-return-to-dashboard, and cold-start all land on *this panel's* dashboard rather than the Companion's user-default. Shares the intended-dashboard concept with the navigate item above.
- **Installer default-app setup** — a deploy-time flag for `install.sh` / `provision.sh` that sets the Android defaults over root, idempotently: **HA Companion as the home/launcher** (sidelines the vendor launcher; boots straight to the dashboard) and **HA Companion as the "Assist & voice input" default app** (so the assist gesture / long-press-home triggers HA Assist via the Companion's `VoiceCommandIntentActivity`). The on-demand admin launcher itself already ships (the navbar **Launcher** button); this is the installer automation around it.
- **Built-in relay control beyond the S9E** — the same `switch.<panel>_relay*` model on other panels
  with onboard relays, once each one's control path (GPIO / vendor node) is known.
- Daemon boot-persistence on su-only (PX30) panels, if true-off is wanted without relying on `su`
  at runtime.
- **On-device scheduler** — run panel actions (screen on/off, sleep/wake, reboot, reload/navigate URL)
  at **fixed times or repeating intervals**, configured from the HTTP UI or REST API. Runs on-device, so
  schedules keep working through Home Assistant or network outages (unlike an HA automation). Parity with
  the vendor tools' scheduled screen-on / reboot.
- **HTTP UI redesign — continued iteration** — the first cut shipped in 0.8.5 (a tabbed multi-page
  app: Dashboard · Configure · Test · Install · Fleet · API, with a schema-driven Configure tab and
  per-setting Home Assistant exposure). The layout, grouping and navigation are **still settling** and
  will keep changing over the next releases: curating the reduced "Basic" settings view, refining which
  values sit on which dashboard cards, and the customisable layout below.
- **Customisable info-page layout** — drag-and-drop card re-ordering plus a per-card collapse
  (disclosure triangle) so users can hide cards they don't care about, with the card order + collapsed
  state **persisted per panel**. (Card title bars were demarcated as the groundwork for this.)
- **Tame a runaway vendor Zigbee guard** — the panel's stock `guard_process.sh` can pin a CPU core
  endlessly restarting a `zgateway` that won't stay up (the *120P vendor-guard spin*), even when
  ha-paneld isn't managing Zigbee. Detect it (zgateway crash-loop / high CPU) and offer to stop or take
  over the gateway; also make ha-paneld's existing vendor-native stop `awk`-free (awk is absent on some
  panels, so the current stop can silently fail).
- **A mains panel must never become unresponsive** — only the backlight/screen should power down for
  efficiency; the SoC, network and touch must stay live at all times. ha-paneld should hold a
  **partial wakelock** (CPU + network up, screen free to sleep) so a panel can never suspend into the
  unreachable "zombie" state — energy-efficient *and* always reachable. It should also **detect and warn**
  about settings that can make a panel unresponsive — native screen-off → SoC-suspend,
  `stay_on_while_plugged_in=0`, aggressive Doze, a mis-reported power source — surfacing them with a
  one-tap fix in the **HTTP info page, the on-panel config UI, and the installer**. Panels ha-paneld lands
  on have usually run other software first, so their power/settings state **can't be assumed — check it,
  don't trust it**.

## Stretch goals

Larger directions that aren't on the v1.0 path but fit a panel agent well — and overlap with what some other products do. Listed to gauge interest and to shape the architecture early. They lean on the same "panel as a first-class local device" primitives (mic/speaker/camera, root `input`, the HTTP surface) ha-paneld is already building.

1. **Camera + microphone stream (RTSP / WebRTC), importable as an HA camera** — expose the panel's front camera and mic as an RTSP or WebRTC stream that Home Assistant ingests via its generic-camera / go2rtc / WebRTC stack, turning a wall panel into a room camera or video intercom. Needs `CAMERA` + `RECORD_AUDIO` — opt-in, privacy-gated, with an on-panel recording indicator. Overlaps with Fully Kiosk's camera/motion feature and dedicated room cameras.
2. **Micro wake word → HA conversation agents** — on-device wake-word detection (microWakeWord / openWakeWord) so the panel acts as a voice satellite: detect the wake word locally, then stream audio to a Home Assistant Assist pipeline / conversation agent. Overlaps with HA Voice PE, ESPHome voice satellites and Atom Echo — but on hardware already mounted on the wall.
3. **Push-to-talk (PTT) intercom** — press-and-hold on one panel to talk live on another room's panel over the LAN: low-latency, direct panel-to-panel, no cloud. Reuses the mic-capture + speaker-playback primitives. Overlaps with Alexa/Sonos drop-in, but local-only and across mixed hardware.
4. **Remote control via the web UI — beyond tap** — the 0.8.5 Test tab already does remote **tap** (click the live screenshot to send a touch) plus the nav actions; extend it with **swipe/drag**, keyboard input, and a faster capture path (the current poll-a-PNG loop is fine for taps, too slow for scrolling). Overlaps with Fully Kiosk remote admin and scrcpy/VNC, but integrated and permissionless where root is available.
5. **Wall-panel-native notifications** — Home Assistant notifications today arrive via the HA Companion app, whose model is built for a **phone in your pocket** (heads-up + a pull-down shade) — wrong shape for an always-on wall panel. Render an HA-sent notification as a **banner or full-screen overlay over the dashboard** (reusing the system-overlay mechanism the navbar already uses), with optional **wake-screen, TTS announce, and LED/backlight flash**, an **auto-dismiss timeout**, and an **acknowledge** action published back to HA. Overlaps with Fully Kiosk's to-foreground-on-message / alarm screens and vendor panels' popup alerts — but driven by HA over the existing MQTT/HTTP surface.
6. **DLNA / UPnP media renderer** — advertise the panel as a UPnP/DLNA media renderer so Home Assistant auto-discovers it as a `media_player`. It would appear as a **separate** HA device (MQTT discovery has no media_player platform, so it can't join the panel's MQTT-discovery device), and the shipped [TTS recipe](tts.md) already covers the main announce case — so this is an interest-gauge, not a commitment.
