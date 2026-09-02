# Control API and Home Assistant reference

ha-paneld exposes each panel through Home Assistant MQTT discovery and a local HTTP service on port `8888`. This page describes the stable contracts and the main endpoint families. The bundled OpenAPI document is the exhaustive reference for the installed build: open `http://<panel>:8888/api` or fetch `http://<panel>:8888/api/v1/openapi.json`.

## Home Assistant MQTT discovery

Set an MQTT broker on the Configure page and supported entities appear in Home Assistant without YAML. Entity IDs default to the forms below, where `<panel>` is the configured panel ID. Hardware-specific entities are omitted when the active device profile declares the capability absent, and optional setting and diagnostic entities can be exposed or hidden individually on the Configure page.

| Entity | Contract |
|---|---|
| `light.<panel>_screen` | Screen on/off and brightness using Home Assistant's JSON light schema and a `0`–`255` brightness value. Off follows the active profile: physical backlight-off where the panel exposes it, Android sleep where a `keyevent` route is validated, or safe brightness dimming as the fallback. The Android-sleep route refuses a panel protected by a PIN, pattern or password. |
| `light.<panel>_led` | RGB or brightness-only panel LED, depending on the available backend; supported backends also expose effects. |
| `light.<panel>_buttons` | Brightness-capable button backlight when declared by the active profile. |
| `light.<panel>_button_led1` … `button_led4` | Individually controlled button LEDs when declared by the active profile. |
| `switch.<panel>_relay1` … `relay4` | Hardware relays when declared by the active profile. |
| `text.<panel>_navigate` | Sends a dashboard path to the active renderer. The built-in renderer navigates in process; a foreign renderer receives a deep link. |
| `text.<panel>_home_dashboard` | Configures the path used by dashboard-return actions. |
| `number.<panel>_volume` | Announcement volume from `0` to `100` percent on Android's music stream. |
| `event.<panel>_button` | Hardware button events when key filtering is enabled. |
| `sensor.<panel>_illuminance` | Ambient illuminance in lux when the panel has a standard Android light sensor. |
| `sensor.<panel>_temperature` / `sensor.<panel>_humidity` | On-panel climate readings when declared by the active profile. |
| `binary_sensor.<panel>_proximity` | Learned near/far occupancy state. It remains unavailable until the local model is trustworthy. |
| `sensor.<panel>_proximity_level` | Normalized proximity from `0` for the learned far baseline to `100` for the learned near reference. |
| `sensor.<panel>_zigbee_gateway_health` | Bounded gateway health and redacted diagnostic attributes on Zigbee-capable panels. |
| `switch.<panel>_voice_assistant` | Enables the on-panel wake-word listener and Home Assistant Assist routing on panels with a microphone. Hidden from Home Assistant until exposed. |
| `sensor.<panel>_voice_state` | Current voice-assistant phase — off, idle, listening, processing, responding or error — on panels with a microphone. Hidden from Home Assistant until exposed. |
| `sensor.<panel>_storage_health` | Diagnostic storage and SQLite health with free-space, database-growth and bounded integrity attributes. Critical pressure or a database failure also fails installer verification; ha-paneld does not reclaim data automatically. |
| `switch.<panel>_camera_enabled` | Master switch for the experimental camera trial, off by default and offered only on profiles that declare a camera. Turning it on arms the trial and nothing more: Android still withholds the camera until the permission is granted at the panel, and with the switch off the camera does not open for anything. |
| `image.<panel>_camera_snapshot` | A still from the panel camera on profiles that declare one. It carries the snapshot URL rather than the picture, so Home Assistant fetches a frame when the card is actually viewed and no image is stored on the broker. It is unavailable while the camera switch is off, and it is a still rather than a live view. |
| `button.<panel>_reload` | Reloads the built-in renderer in process or restarts the selected foreign renderer. |
| `button.<panel>_reboot` | Reboots through an available privileged route. |

Registry-backed configuration, performance and diagnostic entities are published only when exposed. The panel and Companion update buttons are always published. `GET /api/v1/config/schema` reports which settings are Home Assistant-capable, and `GET /api/v1/config` reports their current `ha_expose` state.

Discovery payloads and current states are republished after MQTT reconnects and Home Assistant birth messages. ha-paneld also removes stale discovery entries when the panel ID, profile capabilities or discovery shape changes.

## Browser pages

| Path | Purpose |
|---|---|
| `/` | Panel status, capabilities, diagnostics and current dashboard preview. This is the Home Assistant device's `configuration_url`. |
| `/setup` | Guided first-run and reset journey for identity, connection, renderer, Home dashboard and entity-filter choices. |
| `/configure` | MQTT, renderer and panel behavior settings. |
| `/profiles` | Device-profile selection, inspection and YAML authoring. |
| `/entities` | Built-in renderer entity learning, policy and overrides. |
| `/install` | Software, backup, restore and maintenance actions. |
| `/logs` | Redacted live application logs. |
| `/api` | Interactive explorer for the bundled OpenAPI document. |

## HTTP API conventions

The canonical machine API is under `/api/v1`. Pre-0.8.5 flat machine paths such as `/config`, `/perf`, `/action` and `/proximity` return `308 Permanent Redirect` to their versioned equivalents, preserving the method and body. New clients should use `/api/v1` directly.

`GET /health` and `POST /play` remain available at the root for simple monitors and Home Assistant automations, with identical versioned routes at `/api/v1/health` and `/api/v1/play`.

While the panel is watching Home Assistant lifecycle events, the health line carries one extra token, `ha=<state>`, where the state is `normal`, `shutting_down`, `starting`, `back_online` or `connection_lost`. The token is absent entirely when the panel is not watching, so the line is unchanged for existing consumers. The events are subscribed to by exact type on the connection ha-paneld already holds, which requires the built-in renderer and configured Home Assistant credentials. Home Assistant refuses those subscriptions for a non-administrator user; that is not an error, and the panel falls back to the MQTT source described below rather than guessing. `connection_lost` means the connection dropped without Home Assistant saying why, and is deliberately distinct from a deliberate shutdown.

A second token, `ha_src=<socket|mqtt>`, names which source observed the state, and it appears only when one did: the initial `normal` and a `connection_lost` the panel noticed from its own transport are local inferences, so they carry no `ha_src` at all. There are two sources, and they differ in authority. The `socket` source is Home Assistant's own lifecycle event, which proves the shutdown was intended, but Home Assistant only permits those subscriptions for an administrator account. The `mqtt` source is the broker publishing Home Assistant's will, which needs no such permission and so works for the ordinary non-administrator accounts panels normally use; because that will also fires when Home Assistant merely loses its connection to the broker, the panel words it as having gone offline rather than claiming a deliberate shutdown, and it carries no equivalent of the starting stage.

While the panel holds an authenticated Home Assistant socket for any reason (the built-in renderer watching lifecycle events, a Home Assistant ambient light or presence entity source, or a registry watch), the health line also reports the measured connection to Home Assistant. `ha_net=<healthy|warning|severe>` is the rolling five-minute verdict on the **network path**: a warning when more than 5% of the window's probes went unanswered, severe when two consecutive probes did. Round-trip latency never raises it, because the round trip also contains Home Assistant's own response time and the panel's thread scheduling; on a wired panel it can read hundreds of milliseconds against an actual path of under two. That figure is classified separately as `ha_resp=<healthy|warning|severe>`, which passes 100 ms for a warning and 1,000 ms for severe and is a performance observation only. `ha_net_p95=<ms>` is the nearest-rank 95th-percentile round trip of the app's own WebSocket ping probes (`-1` while no probe has answered), `ha_net_n=<count>` the probes in the window, `ha_net_miss=<count>` the probes that went unanswered and `ha_net_age=<ms>` how long ago the last probe was answered (`-1` when none has been), which is what separates a socket that has only just connected from a stream that stopped probing. For the first three minutes after the app starts, `ha_net=settling` is reported alone and no verdict is formed, because startup load makes the round trip describe the panel rather than the connection. One isolated miss or spike never alarms. Failures Home Assistant itself causes, such as closing or refusing the connection while it restarts or rejecting the sign-in, are counted apart and never as loss. A probe that goes unanswered while any other frame keeps arriving, including a late reply to an earlier probe, is a busy server rather than a lost path: it is counted as a server failure, and the shared socket stays up. Measurement belongs to an authenticated socket, not to the wish for one, so the tokens are absent until the panel has actually connected and signed in, and absent again once it stops (no credentials, nothing that needs the socket, or a connection that has given up). While a socket that has been live is being re-established the tokens remain, because a broken path has to be reported during the very outage it explains. A healthy verdict is therefore always stated explicitly rather than by omission. `GET /api/v1/status` carries both verdicts as an unconditional `ha_network` object and `GET /api/v1/diag` as one `[ha-network]` line; neither ever includes the Home Assistant address, the network name or an individual sample.

The service intentionally uses a trusted-LAN model rather than general API authentication. It rejects non-LAN peers, invalid Host headers and browser requests with a mismatched Origin or Referer before routing. Keep port `8888` on a trusted network and do not expose it directly to the internet.

Optional [Hardened mode](security-mode.md) requires physical access to the panel. Selected high-impact remote actions cannot proceed until someone approves them on the panel's screen, and they cannot be approved remotely. A protected request returns HTTP `202` with `error: "approval-required"`; approve it on the panel, then repeat the identical method, path, parameters and body from the same peer within ten minutes. The approval is consumed by the matching retry and forgotten when ha-paneld restarts. Other `202` responses, such as a scheduled profile activation, are successful admissions and do not contain `error: "approval-required"`.

`GET /api/v1/config` redacts credentials. Configuration updates are partial merges, so clients must send only the fields they intend to change and must never construct a write by round-tripping the redacted response. A successful update becomes visible only after its SQLite transaction is durable; if persistence fails, the previous configuration and active listeners remain authoritative and the request reports failure.

The Configure page can start Home Assistant's normal browser sign-in through `POST /api/v1/ha/oauth/start`. The returned link may be opened or copied into a private browser window, which avoids typing credentials on the panel. Home Assistant redirects to the panel-local callback; ha-paneld exchanges the one-use code itself and never returns access or refresh tokens to the browser. The callback is available only through the same trusted-LAN and Host checks as the rest of port `8888`, is bound to the panel address that started it, expires after ten minutes and sends `no-store`/`no-referrer` responses.

## Endpoint families

This table is a maintained overview rather than a replacement for OpenAPI. The explorer documents exact methods, parameters, request bodies, status codes and response schemas for every active route.

| Endpoint family | Purpose |
|---|---|
| `/api/v1/health`, `/api/v1/info`, `/api/v1/status`, `/api/v1/sensors`, `/api/v1/radio` | Liveness, panel identity, health findings, capabilities and bounded sensor or gateway state. |
| `/api/v1/config`, `/api/v1/config/schema`, `/api/v1/config/export`, `/api/v1/config/import`, `/api/v1/config/revisions` | Partial configuration, registry metadata, portable bundles and on-panel revision restore. Secret-bearing export, import and restore operations follow the Hardened-mode approval contract. |
| `/api/v1/config/home-dashboards`, `/api/v1/config/ha-area`, `/api/v1/config/probe-broker`, `/api/v1/config/probe-log-sink` | Home dashboard and area choices plus MQTT-broker and log-sink preflights. Guided setup and Configure use the relevant Home Assistant and broker operations; the log-sink probe remains available to API clients. It transmits one marked record without changing saved configuration; verify the returned marker at a syslog collector because a completed socket write is not delivery acknowledgement. |
| `GET /api/v1/setup`, `POST /api/v1/setup/identity`, `POST /api/v1/setup/home-dashboard`, `POST /api/v1/setup/entity-filter`, `POST /api/v1/setup/attest` | Current guided-setup state and answer recording. Dashboard, connection and filter values are still written through the normal configuration contract. |
| `/api/v1/ha/oauth/start`, `/api/v1/ha/oauth/panel-start`, `/api/v1/ha/oauth/callback`, `/api/v1/ha/oauth/status` | Short-lived administrator-browser or on-panel Home Assistant sign-in. The panel exchanges and stores credentials server-side; the browser receives no tokens. |
| `/api/v1/profiles` | Lists immutable bundled and local profile revisions and their activation state. Subroutes provide schema and driver catalogues, templates, passive device drafts, validation, comparison, import, activation, rollback and deletion. |
| `/api/v1/provisioning/plan` and `/api/v1/provisioning/plan.txt` | Read-only, profile-driven provisioning guidance combined with live panel observations. |
| `/api/v1/backup`, `/api/v1/restore`, `/api/v1/install/*`, `/api/v1/uninstall`, `/api/v1/updates/ignore`, `/api/v1/webview/heal`, `/api/v1/companion/repair-url` | Full panel backups cover configuration, entity-filter state, durable panel state and the profile catalog, with optional Companion login; restore returns eligible device-local state only to the originating panel. The remaining routes handle component and APK installation, update state and maintenance. Long-running operations report through `/api/v1/install/status` and share one destructive-operation lane. |
| `/api/v1/action`, `/api/v1/input` | Bounded Dashboard return, renderer Reload, navigation, launcher, volume, reboot and pixel-tap control. Dashboard return is routine; remote Reload and Reboot require physical approval in Hardened mode. A completed tap with `capture=true` returns a fresh PNG screenshot, while Hardened mode rejects non-loopback remote input rather than approving it remotely. |
| `/api/v1/auto-brightness` | Current adaptive-brightness state, seven-day history, Home Assistant illuminance-source discovery and live-validated selection, learned-history reset and manual-pause resume. |
| `/api/v1/auto-sleep`, `/api/v1/auto-sleep/prerequisite`, `/api/v1/auto-sleep/history`, `/api/v1/auto-sleep/source` | Current automatic-sleep decision, redacted Home Assistant Area eligibility, bounded source-history replay and durable inclusion of one discovered source. Transient Home Assistant discovery and history failures are retryable and do not discard the last settled view. |
| `/api/v1/proximity` | Learned proximity state plus guided teach, non-actuating test and confirmed relearn operations. Retired manual threshold operations return `410 Gone`. |
| `/api/v1/voice/pipelines`, `/api/v1/voice/test` | Home Assistant Assist pipelines available for the configured wake words, and a one-shot voice-assistant test trigger. Both require a microphone-capable panel; the test trigger also requires the voice assistant to be enabled. |
| `/api/v1/dashboard/entity-filter` and `/api/v1/dashboard/entities/*` | Built-in renderer filter state, learned entity catalogue, synchronization, policy, overrides, issue decisions, export, activation and confirmed reset. |
| `/api/v1/perf`, `/api/v1/perf/history`, `/api/v1/perf/costs`, `/api/v1/perf/binding` | Service and renderer performance snapshots, bounded history, separately sampled feature costs and opaque A/B comparison binding. |
| `/api/v1/camera/status` | The camera session's own status, byte-for-byte the `camera` object in `/api/v1/status`, served alone so the Dashboard's Camera stream card can poll it every couple of seconds without rebuilding the whole status document. It reads session state and never opens the camera, so an idle panel pays nothing for it, and a panel with no camera reports the object's absent state rather than an error. |
| `/api/v1/diag`, `/api/v1/logs/stream`, `/api/v1/screenshot.png`, `/api/v1/camera/snapshot.jpg`, `/api/v1/sensortrace`, `/api/v1/inspect/*` | Redacted issue diagnostics, live logs, dashboard screenshots, sensor traces and temporary WebView DevTools relay. The diagnostics omit configured network and panel identifiers while retaining categorical hardware/boot posture, app-database usage and current privilege/capability state; review a report before posting it publicly. The camera snapshot endpoint is an experimental, off-by-default trial that returns one JPEG frame at the profile default or requested `?res=` resolution and is offered only on profiles declaring a camera. The same trial serves a video-only H.264 stream over RTSP, outside this HTTP API, at `rtsp://<panel>:8554/live` while the camera setting is on; `?res=`, `?fps=` and `?kbps=` on that URL ask for less than the profile ceilings and never for more, the first client's parameters bind the one encode session that later clients join, and the `camera` object in `/api/v1/status` reports the stream's clients, encoder, delivered rate and, separately, the rate the stream asked for. Those two differ when a snapshot has already opened the session: the capture rate is fixed by whoever opens it, a stream arriving afterwards joins that session rather than reconfiguring it, and `encode_fps` is therefore what the encoder was given while `requested_fps` is what was asked for. The same applies to a requested resolution or bitrate, which the panel does not currently report back. Do not put this panel's own camera card on this panel's dashboard: capturing, encoding, sending, decoding and compositing the same picture on one already busy SoC is a loop the panel cannot afford, and the stream yields to the dashboard when it happens. |
| `/api/v1/logship/status` | Passive live log-shipping summary shown in Dashboard Runtime diagnostics while shipping is enabled. It reports whether shipping is configured, the live transport state and sent or dropped line counts without repeating the destination; polling it transmits no records. |
| `/api/v1/power-safety`, `/api/v1/power-safety/state`, `/api/v1/power-safety/repair`, `/api/v1/power-safety/acknowledge` | Bounded Android reachability assessment, terminal-safe state, explicit verified repair and presentation-only acknowledgement of one exact unchanged manual caution. Repair never reboots. Remote repair and acknowledgement require physical approval in Hardened mode; acknowledgement never changes the underlying risk, diagnostics or installer result. |
| `/api/v1/apps`, `/api/v1/packages`, `/api/v1/peers`, `/api/v1/ui/layout`, `/api/v1/dashboard/clear-storage`, `/api/v1/display/density`, `/api/v1/tame` | Supporting data and actions used by the browser UI and fleet tooling. |

### Profile administration contract

Profile YAML is previewed before it is saved. `POST /api/v1/profiles/preview` validates the exact bytes and returns a short-lived preview token; `POST /api/v1/profiles/import` accepts those same bytes with the token in `X-Profile-Preview-Token` and stores an immutable inactive revision. Selection and rollback requests require explicit confirmation plus the current catalogue revision, and successful selection schedules a controlled service restart. The Profile page uses this same API.

The profile schema, compiled driver catalogue and downloadable starter YAML are available from `/api/v1/profiles/schema`, `/api/v1/profiles/drivers` and `/api/v1/profiles/template`. See [Device profiles](profiles/README.md) for the YAML reference and authoring workflow.

### Playback contract

`POST /play` and `POST /api/v1/play` accept a raw audio URL or `{"url":"…"}`. Each panel owns one announcement lane: a newer accepted request cancels and replaces queued, downloading or playing audio, so clips do not overlap. A request returns `400 no-url` when no URL is present and `503 stopping` after shutdown closes admission.

Audio fetched over HTTPS uses Android's platform trust store and hostname verification. A self-signed certificate is rejected unless its CA is installed on the panel; plain HTTP remains available for trusted LAN sources.

## Pairing and discovery

ha-paneld advertises `_ha-paneld._tcp.local.` with version, capability, route and friendly-name metadata. An opaque per-responder probe token lets the panel check that its own current advertisement remains visible on the LAN; after repeated misses it rebuilds the responder with bounded retries, and diagnostics warn if recovery is exhausted. With no MQTT broker configured, it browses for Home Assistant's `_home-assistant._tcp` service and uses a discovered broker on port `1883`. Set the broker explicitly when it is elsewhere or more than one Home Assistant instance is present.

If the broker requires credentials, enter them on `/configure`; MQTT status reports an authentication rejection until they are correct. The HTTP service remains available when MQTT discovery or connection is unavailable.
