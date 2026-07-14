# Changelog

## v0.9.2-rc3 - 2026-07-14

**Home Assistant dashboards that seemed too demanding for a low-powered wall panel can now be made far more responsive.** A large Home Assistant installation may send thousands of entity states and updates to a panel even when its dashboard displays only a small fraction of them. Users experience delayed taps and sluggish navigation and may reasonably conclude that the panel itself is too weak. The built-in renderer can now learn what the dashboard uses and ask Home Assistant to send only those states. Filtering remains opt-in during pre-release testing, but it no longer requires a hand-maintained entity list. For the smaller group already using a second Home Assistant instance, a filtering proxy or a similar workaround solely to reduce the panel's load, the built-in filter may allow that extra infrastructure to be retired.

### Added

- **The built-in renderer can learn which entities its dashboard actually needs** — ha-paneld examines the configured dashboard and observes the states it uses while running. A dedicated Entities page shows what was found, explains why each entity is included and allows manual additions or exclusions before filtering is enabled.
- **Learned results survive app updates without making backups unnecessarily large** — ha-paneld retains the user's manual choices, discards old observations automatically and can rebuild the rest of the catalog when needed.
- **Recognized dashboard rules that could hide required entities are surfaced before filtering** — broad or dynamic rules that ha-paneld recognizes are shown with their source and a suggested correction. Users can fix the dashboard, deliberately continue without the uncertain entities or leave filtering disabled; ignored warnings remain visible and can be restored later. Custom cards and behavior that automatic learning has not observed may still require manual review.

### Changed

- **Existing exact entity lists continue to work alongside automatic learning** — testers can keep a manually chosen list or switch to the new guided workflow. Filtering remains opt-in during pre-release testing.
- **The unfinished remote-control page is withheld** — the Test tab and its screenshot tap-control workflow are hidden while the feature is reviewed for reliability. Existing screenshot, action, input and audio APIs remain available, and old `/test` bookmarks return to Dashboard.

## v0.9.2-rc2 - 2026-07-14

### Added

- **Early entity filtering for built-in-renderer testers** — panels made sluggish by a large Home Assistant installation can be given an exact list of dashboard entities, preventing unrelated state updates from reaching the renderer. This first version is disabled by default and configured through `/api/v1/dashboard/entity-filter`; it does not yet discover entities automatically or provide a web setting. See [the tester instructions](docs/built-in-renderer.md#experimental-entity-filter-092).

### Changed

- **Installing the APK alone leaves setup incomplete** — It does not complete ha-paneld's permissions, startup, configuration or verification. Releases now lead with an installer that performs those steps and clearly label the APK as requiring manual setup, while preserving on-device sideloading.

### Fixed

- **Home Assistant now shows the screen's real power state** — on panels such as the TPA10, switching off the backlight could leave Home Assistant showing the screen as on because the stored brightness remained non-zero. ha-paneld now reads whether the backlight itself is powered. The updated app and helper daemon must be installed together on affected panels.
- **Settings changes and restarts no longer mix old and new behaviour** — work already underway during a settings change, MQTT reconnect or service restart could finish using the previous configuration, allowing an old connection, dashboard, audio request or status update to reappear after the change. ha-paneld now discards anything that belongs to the previous setup once its replacement begins.
- **Home Assistant no longer shows hardware changes that did not happen** — if an LED, relay or display command failed or was overtaken by a newer command, Home Assistant could still show the requested state even though the panel had not applied it. ha-paneld now publishes changes only after the hardware confirms them and prevents older commands from replacing newer ones.
- **Interrupted maintenance tasks no longer look successful** — an upload, download, software installation or uninstall that stopped part-way could still be reported as complete, while a failed update check could leave an old result looking current. ha-paneld now keeps these failures visible and reports success only when the full operation finishes.

## v0.9.2-rc1 - 2026-07-12

### Added

- **Built-in dashboard performance is now measurable on every panel** — the Performance page and `/api/v1/perf` show how long a cold or warm dashboard load takes to become usable and how often the renderer has reloaded unexpectedly in the past 24 hours. These measurements do not require root and make it easier to see whether a renderer or configuration change actually helped.
- **Two newly reported panel types now identify correctly** — preliminary profiles give the Amazon Echo Show 5 Gen 2 running LineageOS and the unbranded ZX-SMT156/RK3566_T cautious defaults based on their submitted diagnostics. Follow-up diagnostic reports also collect the remaining hardware details needed to refine support without a long manual adb session.
- **Dashboard startup now shows what the panel is waiting for** — if networking is still coming up after a reboot, the built-in dashboard shows whether it is waiting for network services, a link, an address or a connection instead of looking broken. It learns the panel's typical startup time to give more useful progress on later boots and disappears entirely when networking is already ready.

### Changed

- **Changes made on the panel now stay in sync with Home Assistant** — screen power, brightness, volume, relays, LEDs and proximity could become stale or briefly jump back after a local or external change. ha-paneld now reports the latest confirmed panel state and keeps pending updates in order.

### Fixed

- **Panels recover automatically from a temporary MQTT login rejection** — a rejected connection could leave a panel offline or start overlapping reconnect attempts. ha-paneld now starts a fresh connection and retries at a controlled pace, while diagnostics show what happened and when the next attempt will run.
- **Home Assistant entities no longer become stale after a failed MQTT update** — a missed state update could block later changes or trigger repeated retries. ha-paneld now keeps the latest panel state, limits the retry rate and continues sending pending updates.
- **A reboot no longer shows two connection errors before the dashboard appears** — if networking was not ready, the built-in dashboard could first show Chromium's offline error and then Home Assistant's 60-second connection-failed countdown. It now waits for the network and opens the dashboard directly; genuine later failures still retry.
- **“Silence boot chime” now also silences startup notification sounds** — on panels with separate ring and notification volumes, startup could still play a notification sound even when Silence boot chime was enabled. The setting now mutes both streams and uses a silent notification channel.

## v0.9.1 - 2026-07-12

### Added

- **System WebView updates can now be installed automatically** — panels with a recommended WebView build gain an optional WebView auto-update switch, which is off by default and installs a newer recommended build during update checks. The first switch between different WebView vendors may still require the one-time manual procedure in the panel's hardware guide.
- **Switching from the Companion app no longer makes the dashboard look smaller** — the built-in dashboard now uses the same scale and carries over the Companion app's Page zoom value. A Dashboard zoom setting allows deliberate per-panel adjustment, while rooted panels can use the sharper display-density control when a larger change is needed.
- **ha-paneld settings are now available from the dashboard sidebar** — the built-in dashboard adds the same App Configuration entry as the Companion app, opening ha-paneld's configuration page directly on the panel.
- **The built-in dashboard starts with sensible wall-panel defaults** — the sidebar starts hidden and background connections stay active so the panel remains responsive while idle. These defaults apply only on first run and remain changeable.

### Changed

- **Camera streams on TPA10-class panels can now start without a tap** — LineageOS WebView 150 replaces Cromite as the recommended build because it keeps Home Assistant camera autoplay enabled. Cromite remains available as a fallback.
- **The dashboard no longer stretches when scrolling past an edge** — the built-in dashboard now disables Android's elastic stretch or edge glow by default. The `dashboard_overscroll` API setting restores the native effect for anyone who prefers it.
- **LED pulse effects are smoother and no longer flash white** — the breathing animation now follows a smoother curve at a higher frame rate instead of stepping visibly or flashing at its dimmest point.

### Fixed

- **Bottom-row dashboard controls now work normally with swipe-to-reveal navigation** — taps and scrolling near the bottom edge could be delayed or ignored because the navigation gesture captured every touch in that area. ha-paneld now intercepts only a genuine upward edge swipe, leaving ordinary dashboard interaction untouched.
- **Dragging or scrolling a dashboard card no longer refreshes the page** — pull-to-refresh now starts only when a drag begins at the very top edge of the screen. Card controls and scrolling inside the dashboard no longer trigger an accidental reload. (#29)
- **LED effects stop as soon as the light is turned off or another effect is selected** — strobe, blink or pulse could previously continue running, sometimes leaving the panel flashing until ha-paneld restarted. (#16)

## v0.9.0 - 2026-07-10

**ha-paneld can now display Home Assistant itself.** The optional built-in dashboard allows a panel to show Home Assistant without using the Companion app as the visible dashboard. It remains experimental and off by default; the Companion app remains the default and fully supported. Everything below is cumulative since v0.8.7.

### Added

- **Optional built-in Home Assistant dashboard** — ha-paneld can show the dashboard in its own WebView, with sign-in provisioned without typing on the panel. The feature is experimental and off by default. It does not provide Companion app features such as Assist voice control, so the Companion remains the better choice when those are required.
- **Wall-panel controls for the built-in dashboard** — camera streams start automatically and can open fullscreen, pull-to-refresh is immediate, an idle panel can return home, and edge-to-edge fullscreen mode reveals the system bars with a swipe. Renderer storage can be cleared remotely, and HTTPS servers using a user-installed private certificate authority are supported. (#25)
- **Light and dark appearance now follows where the interface is viewed** — the web interface follows each browser's preference, while a Dark mode setting themes ha-paneld's on-panel screens and supplies a dashboard default on older Android versions. A theme chosen inside Home Assistant still takes priority.
- **The Configure page stays current without losing unsaved edits** — settings changed through Home Assistant, the API or another browser trigger a reload when the form is untouched. If edits are in progress, a banner offers the reload instead of discarding them.
- **Features that need root access are visible instead of silently missing** — unavailable controls are greyed out with an explanation, the Install page offers a manual APK download, and the installer states which capabilities the panel can use.
- **The built-in dashboard can reuse the Companion app's sign-in** — on rooted panels, selecting the built-in dashboard borrows the existing Companion login so it can be tried without entering a Home Assistant URL or token on the panel.
- **Home Assistant can control LED effects** — compatible panel lights expose strobe, blink and pulse through Home Assistant's standard light effect selector.
- **MQTT connections can be encrypted** — `ssl://` and `mqtts://` broker addresses use TLS and the Android trust store, defaulting to port 8883.
- **Prereleases can be installed with one option** — `install.sh … | bash -s -- --prerelease` or `provision.sh --prerelease` selects the newest release candidate instead of the latest stable release.

### Changed

- **Dashboard options are easier to find and experimental features are clearly marked** — dashboard settings now have their own Configure card and renderer picker, while maturity badges identify unfinished or experimental controls.
- **The Performance page separates dashboard cost from measurement overhead** — sampling uses fewer resources, its own cost appears separately, and the work of hosting the built-in dashboard is labelled clearly.
- **Companion-only settings no longer clutter panels without the Companion app** — those controls are hidden and their unused Home Assistant entities are retired when no Companion installation is present.

### Fixed

- **A broken built-in dashboard no longer traps the panel in a restart loop** — repeated crashes are slowed and eventually fall back to ha-paneld's launcher, while a revoked Home Assistant login shows repair instructions and can be replaced from the Configure page.
- **Returning to a healthy built-in dashboard no longer reloads it unnecessarily** — kiosk and watchdog recovery now bring the existing page forward, preserving its current view instead of blanking and loading it again.
- **Switching from the Companion app reliably makes the built-in dashboard the panel's home screen** — existing panels now reclaim the Android home role immediately instead of returning to the Companion.
- **The navigation bar's Reload action no longer stops ha-paneld itself** — reloading a built-in dashboard now refreshes the page without terminating the app that hosts it.
- **Fullscreen and dark-mode changes now take effect when saved** — these settings previously stored the new value without visibly applying it to the running dashboard.
- **Panel status no longer shows stale or misleading hardware and software information** — uninstalling the Companion clears its update notice, and diagnostics no longer assume every Rockchip `/dev/ledjni` RGB light uses the rk3576 controller. (#24)
- **Provisioning login failures now explain how to recover** — failed Home Assistant sign-in no longer ends without useful guidance, and credentials containing special characters are handled correctly.
- **The dashboard returns promptly after a vendor home app is disabled** — panels no longer remain on ha-paneld's launcher until the next watchdog interval after vendor taming.

## v0.8.7 - 2026-07-07

**This release includes a large amount of work to improve the reliability of the install and provisioning scripts** (`install.sh`, `provision.sh`, and the root-daemon installer) across the supported panels — su-dialect probing, adb preflighting, honest self-verification, and clear, classified failure reporting. If you still hit an install or provisioning problem, **please [report it](https://github.com/maxlyth/ha-paneld/issues)** so it can be fixed.

Beyond that, panel telemetry is more reliable across the fleet, the Tuya TPA10 gains room temperature/humidity sensors, and the `:8888` header is now responsive on narrow panels.

### Added

- **Room temperature & humidity on the Tuya TPA10** — the onboard CHT8305 chip is exposed as opt-in Room temperature / Room humidity sensors in Home Assistant, read through the root helper daemon (proper temperature/humidity device classes, off by default), with a per-panel calibration offset for panel self-heating.

### Changed

- **Provisioning is much more robust** — `provision.sh` and the root-daemon installer now probe each panel's `su` dialect (join-style vs exec-style, `su 0` / `su root` / `su -c`, or root-adbd with no `su`), preflight the adb connection and fail fast (~12s) with specific recovery steps instead of hanging, classify install failures (signature mismatch, downgrade, out-of-storage) with how to recover, and degrade gracefully when a vendor build refuses adb-side permission grants. The daemon installer's exit code now reflects whether the daemon is actually running.
- **Diagnostic sensors populate on more panels** — CPU usage and SoC temperature fall back to the root helper daemon when the app can't read them directly, so they no longer show "Unknown" on sandboxed panels. All OS-sourced telemetry — the diagnostic sensors and the `:8888` performance view — now flows through one shared reader instead of two paths that could disagree.
- **Responsive `:8888` header** — a sticky top bar that never scrolls away; on a narrow or single-column panel the tab bar collapses to a hamburger menu and header items progressively hide instead of wrapping, so the header never overflows.
- **Config & controls polish** — the "expose to Home Assistant" control is now a link / broken-link icon toggle, and the Controls action buttons collapse to icons when the row would otherwise wrap. The low-value instrumentation master switch is gone (the performance sampler stays gated by page views, the real cost control).

### Fixed

- **`/api/v1/diag` responds instantly** — it serves the last-known panel snapshot and refreshes in the background instead of re-running the full probe suite (which can take >12s on an NSPanel Pro).
- **`provision.sh` self-verify and `--persist-adb` work reliably** — verify no longer always ended "re-run to finish", `--persist-adb` is confirmed by read-back, and provisioning can no longer clobber other accessibility services if it can't read the current list.
- **"Open in Home Assistant" is more reliable** — it falls back to the URL the HA Companion already uses when the panel's own HA device-page URL can't resolve.
- **On the Tuya TPA10, the room-temperature calibration offset saves from the web UI** (a posted value was previously accepted but silently dropped), and the older `temperature`/`humidity` sensors — which never streamed a live value on this chip — are retired in favour of the daemon-read `room_temp`/`room_humidity`.

### Docs

- The root-daemon install step (`helper/install-daemon.sh`) is now linked from the provisioning guide.

## v0.8.7-rc4 - 2026-07-07

### Fixed

- **`/api/v1/diag` responds instantly** — it claimed to reuse the cached panel snapshot but actually re-ran the full probe suite (a dozen su/system probes, >12s on an NSPanel Pro) whenever the snapshot was more than 15s old. It now serves the last-known snapshot and refreshes it in the background, like the Configure endpoints already did.
- **`provision.sh` self-verify works again** — the end-of-run checklist grepped for a diagnostics token that no longer exists (`a11y.enabled=`) and gave a cold `/diag` only 4 seconds (it can take >12s while the panel probes its capabilities), so every run ended "re-run to finish" even when the panel was fully provisioned, and `--verify` always exited non-zero. Verify now passes on a healthy panel and also reports whether the root helper daemon is running.
- **Root commands now work on SuperSU-style panels (NSPanel Pro)** — that `su` re-joins its arguments and runs them through its own `sh -c`, so wrapping a command in `sh -c` silently stripped the quoting (the daemon installer's multi-step root blocks were mangled). Both scripts now probe the panel's su dialect (join-style vs exec-style, plus `su 0` / `su root` / `su -c` prefixes and root-adbd with no su at all) and wrap commands accordingly.
- **The daemon installer's systemless boot script is pushed as a file** instead of being generated through nested device shells, which evaluated its boot-completed wait once at install time instead of at boot.
- **`provision.sh` can no longer wipe other accessibility services** — if reading the current enabled-services list fails, it skips the write (with a manual-path hint) instead of overwriting the whole list.
- **`--persist-adb` is verified by read-back** — previously a silent no-op on panels whose su form wasn't one of the two it tried.

### Changed

- **Provisioning and the daemon installer preflight the adb connection** — connect failures, the on-panel authorization dialog, and stale "offline" sessions are detected up front and fail fast (~12s instead of minutes) with specific recovery steps, before any release download.
- **Install failures are classified with recovery steps** — signature mismatch (debug vs release), downgrade, and out-of-storage each explain how to recover (including backing up config first) instead of aborting with a raw adb error.
- **Permission grants degrade gracefully** — a vendor build that refuses adb-side grants no longer aborts provisioning; each failed grant names the manual Settings path and the run continues to the self-verify.
- **`install-daemon.sh` exit code now reflects daemon liveness** — files-in-place but no running process is reported as a failure with recovery steps; the script also states which root path it is using.

### Docs

- Sandbox-walled panels' root-daemon install step (`helper/install-daemon.sh`) is now linked from the provisioning guide, not just `helper/README.md`.

## v0.8.7-rc3 - 2026-07-07

### Changed

- **On the Tuya TPA10, room climate now reports as `room_temp` / `room_humidity`** (read through the panel's helper daemon) instead of the previous `temperature` / `humidity` sensors, which read through a standard Android sensor that never streams a value on this chip — so they showed a frozen, stale reading. The old entities are retired automatically; update any automation or dashboard card that referenced them.

### Fixed

- **The room-temperature calibration offset saves from the web UI again** — a posted value was accepted (HTTP 200) but silently dropped, so the field always reverted to 0.

## v0.8.7-rc2 - 2026-07-06

### Added

- **Room temperature & humidity on the Tuya TPA10** — the onboard CHT8305 chip is exposed as opt-in Room temperature / Room humidity sensors in Home Assistant (proper temperature/humidity device classes, off by default), with a per-panel calibration offset to correct for panel self-heating.

### Changed

- **Responsive `:8888` header** — a sticky top bar that never scrolls away; on a narrow/single-column panel the tab bar collapses to a hamburger menu and header items progressively hide instead of wrapping, so the header never overflows. The GitHub link stays reachable on every tab and in the footer.
- **Config & controls polish** — the "expose to Home Assistant" control is now a link / broken-link icon toggle, checkbox rows lay out correctly, and the Controls action buttons collapse to icons when the row would otherwise wrap.
- **"Open in Home Assistant" is more reliable** — it falls back to the URL the HA Companion already uses when the panel's own HA device-page URL can't resolve (e.g. a remote panel over a tunnel), so the button appears on every tab whenever a Companion is configured.
- **Removed the low-value instrumentation master switch** — the performance sampler and sensor trace are always on (still page-view gated, which is the real cost control).

## v0.8.7-rc1 - 2026-07-06

### Changed

- **Diagnostic sensors report on more panels** — CPU usage and SoC temperature now fall back to the root helper daemon when the app can't read them directly, so they populate on panels where they previously showed "Unknown". All OS-sourced panel telemetry (the diagnostic sensors and the `:8888` performance view) now flows through one shared reader, replacing two separate read paths that could disagree.

## v0.8.6 - 2026-07-05

The Install tab becomes a software-management hub, panels get encrypted backup/restore and self-healing update paths, and health problems surface much earlier and more clearly.

### Added

- **The Install tab is a software-management hub** — view and update ha-paneld, the HA Companion and the System WebView from `:8888`, each with a channel selector and a picker of recent releases, an on-demand health re-check, and an update banner with a per-version "Ignore this version" dismissal.
- **Encrypted panel backup & restore** — export a panel's full state (ha-paneld config plus, on a rooted panel, the HA Companion login) as a passphrase-sealed bundle; restore reapplies the config and re-logs in the Companion with no on-panel OAuth — the wall-panel equivalent of the cloud app-data backup these GMS-less panels don't have.
- **One-tap self-heal for the two most common blank-dashboard causes** — a too-old System WebView (downloaded and installed from the ha-paneld WebView mirror, per-panel known-good build) and a Companion with no internal URL (which shows as a "Missing 'Host' header" screen).
- **Watchdog crash-loop protection** — a dashboard app that crashes on every launch backs off instead of restart-storming and raises a clear health warning; a per-panel Companion version cap guards against a known-bad update (e.g. the NSPanel Pro's Android 8.1 crashing on Companion 2026.6.5+).
- **Install an APK from the web UI**, and **uninstall** a removable app — both gated behind explicit opt-in toggles (ha-paneld itself and the System WebView provider can't be removed this way).
- **Opt-in diagnostic sensors** (IP address, CPU usage, memory usage, SoC temperature, boot time), and **local-state sync extended to the CPU governor** — values changed outside ha-paneld now reach Home Assistant without flooding the broker.
- **Panel switcher in the `:8888` header** — on a multi-panel network, the panel's name becomes a dropdown that jumps your browser to the same view on another panel.
- **Experimental kiosk lock** — an opt-in per-panel setting that keeps non-admin users on the dashboard by suppressing the Android navigation bar and returning to the dashboard on any navigate-away; always reversible (on-panel 7-tap unlock, `:8888`, Home Assistant, or a reboot).
- **Dashboard & Launcher app pickers** are now dropdowns of installed apps, and both settings show what "auto" resolves to instead of a bare placeholder.
- **Provisioning tames recommended vendor apps by default** (e.g. the NSPanel Pro's eWeLink control-panel overlay and factory test tools) — reversible, and it can never strand the panel's home role.
- **Live log viewer** (Logs tab, app + system sources, pause/filter/follow) and a **live Sensors card** on the Dashboard, both served over Server-Sent Events with the same redaction pass as remote log shipping.
- **Companion auto-update channel** (Stable / Pre-release), and full config export/import via `provision.sh`.

### Changed

- **Publishing a setting to Home Assistant is now opt-in per setting** — admin-oriented settings (self-update, Companion auto-update, network ADB, Zigbee router, app watchdog) are panel-local by default; existing panels keep whatever they already expose.
- **The web UI loads instantly** — the Dashboard and Configure tab render immediately from a cached, single-flight probe snapshot instead of blocking on every root-probed value.
- **Safer, leaner APK installs** — every over-the-air install preflights free space and streams the verified APK straight into the installer instead of keeping a second staged copy.
- **The header shows the panel's friendly name** instead of its `panel_id`, and panel-health warnings are more prominent, with a one-tap self-heal offered from a failed Companion login.
- Clearer update-entity names ("ha-paneld auto-update"), and config import is now best-effort — valid keys apply even when others in the same bundle don't.

### Fixed

- **Home Assistant entity ids are anchored to the panel id, not the friendly name** — new entities keep stable `sensor.<panel>_*`-style ids regardless of how the device is renamed.
- **Local changes now reach Home Assistant** — brightness, volume and the effective backlight sync back to HA via a deadbanded, settle-aware heartbeat instead of going stale until the next command.
- **Vendor kiosk launchers (eWeLink) can be tamed** without stranding the panel, and the Launcher button always falls back to something usable instead of doing nothing.
- **MQTT reliability** — a superseded client rebuilding in the background could no longer overwrite the live connection's status with a false "credentials rejected" warning.
- **A brightness command or an interrupted config save can no longer leave the panel in a bad state** — brightness floors above zero, and config writes are atomic.
- **A hung `su` can no longer stall the app** — one-shot root commands now run with a hard timeout.
- **Touch-to-wake** — a screen-off can never strand the panel dark, even before proximity calibration.
- **"Open in Home Assistant" self-heals** when HA's device id changes, and auto-return after a relaunch now waits for the MQTT reconnect instead of giving up after 8 seconds.

### Security

- **CSRF and DNS-rebinding protection** on the `:8888` control API.
- **App and Companion updates download over HTTPS only**, on top of the existing signature + package pin.

## v0.8.6-rc6 - 2026-07-05

### Added

- **Panel switcher in the `:8888` header** — when more than one ha-paneld panel is on your network, the panel's name in the header becomes a dropdown of the others; picking one jumps your browser to the **same view on that panel** (e.g. `/configure` on panel A → `/configure` on panel B), so you can hop between local panels without remembering IP addresses. A single-panel network just shows the name as plain text.
- **Experimental kiosk lock** — an opt-in, per-panel setting (marked experimental) that keeps non-admin users on the dashboard by suppressing the Android navigation bar and returning to the dashboard if they navigate away. Aimed at generic Android panels with a working system navbar (the vendor panels are already covered by taming); on-panel unlock is a 7-tap top-left corner, and it's designed so it can't strand a panel or lock an admin out.
- **Dashboard & Launcher app pickers on the Configure tab** — the *Dashboard app* and *Launcher app* settings are now dropdown menus of the apps installed on the panel, instead of free-text package-name fields (a blank entry still means auto-detect).

### Changed

- **The header now shows the panel's friendly name** instead of its `panel_id`, on both the dashboard and the tabbed UI shell.
- **Panel-health warnings are more prominent**, and a failed HA Companion login now offers a **one-tap self-heal** from the warning.
- **Removed the redundant "Tame vendor packages" field** from the Configure tab — the Vendor packages card already covers it.

### Fixed

- **Touch-to-wake — a screen-off can never strand the panel dark.** Touching a dark screen always wakes it, even before proximity calibration, so no screen-off path can leave the panel unresponsive.
- **"Open in Home Assistant" self-heals when HA's device id changes.** If a panel's HA device is deleted and recreated (new device id, same panel), the info-page link re-resolves instead of pointing forever at the deleted device (which showed *"Device / service not found"*).
- **Saving on the Configure tab settles on a clear "Saved."** instead of hanging on a "reconnecting MQTT…" message that never cleared.
- **Auto-return to the dashboard after a relaunch now waits for the MQTT reconnect** (polling for up to 90s) instead of a single 8-second check, so a slow reconnect after a restart no longer leaves the panel sitting on the ha-paneld UI.

## v0.8.6-rc5 - 2026-07-03

### Changed

- **Safer, leaner APK installs** — every over-the-air install (ha-paneld self-update, the HA Companion updater, and the new WebView heal) now **preflights free space** before downloading, so a large APK can't fill `/data` or fail half-written on a low-storage panel, and **streams the verified APK straight into the installer** instead of keeping a second staged copy — halving peak disk use (a WebView build is ~250 MB).

### Added

- **The Install tab is now a software-management hub** — view and update ha-paneld, the HA Companion and the System WebView from `:8888`. Each GitHub-hosted component shows its installed version with a channel selector and a picker of recent releases (with release-notes links), so you can install a specific version or the channel's newest — not just "latest". Adds an on-demand health re-check (the same audit now drives the dashboard banner, the Install tab and `/status`, so they can't drift), a dashboard update banner with a per-version **"Ignore this version"** dismissal that re-surfaces when a newer release ships, and a radio-firmware (EFR32/Zigbee) status card.
- **Install an APK from the web UI** — upload an APK to a panel and, after it's parsed and its package/version/signer are shown for confirmation, install it over root. Gated behind an `allow_apk_upload` toggle with a prominent security warning (the `:8888` UI is unauthenticated LAN-trust).
- **Encrypted panel backup & restore** — export a panel's full state as a single bundle: ha-paneld config (including secrets) plus, on a rooted panel, the **HA Companion login** (its database + session captured over root). Seal it with a passphrase (AES-256-GCM + PBKDF2) or take a plain JSON bundle; restore auto-detects which, shows a decrypt/dry-run preview before the destructive apply, reapplies the config, then rewrites the Companion login with the correct owner uid + SELinux context and relaunches it — so a wiped or replacement panel gets its dashboard connection back with **no on-panel OAuth login**. This is the wall-panel equivalent of the cloud app-data backup these GMS-less panels don't have. (The login DB's SQLite WAL is checkpointed at capture so the restored login is never silently discarded.)
- **Uninstall an app from the Install tab** — a picker of removable (third-party / updated-system) apps with a one-tap root uninstall. ha-paneld itself and the System WebView provider are excluded from both the list and the endpoint, so you can't remove the tool or revert the WebView to the stock build that blanks the dashboard; a live uninstall on a remote panel stays attended-only.
- **One-tap WebView auto-heal** — a too-old System WebView is the most common reason a panel shows a blank Home Assistant dashboard, and these panels have no Play Store to update it. When ha-paneld detects the WebView is too old and the panel profile has a known-good build (NSPanel Pro → LineageOS 138, TPA10 → Cromite 147), the Install tab's warning now offers an **"Update WebView now"** button that downloads the correct `com.android.webview` from the ha-paneld WebView mirror and installs it over root — signature-pinned, like the app/Companion updaters — then reloads the dashboard. No adb, no F-Droid, no manual sideload. (#5)
- **Per-panel HA Companion version cap** — the Companion auto-updater can now be pinned below a build that's known to crash on a specific panel. On the Sonoff NSPanel Pro (Android 8.1) the Companion app 2026.6.5 and newer crash-loop on launch, so the updater is capped at the last good release and installs (or holds at) that instead of the newest one. The cap is a safety guard, not a preference, so even a manual "update now" honours it; panels without a known-bad build are unaffected.
- **Watchdog crash-loop protection** — if the dashboard app crashes immediately on every launch (e.g. after an incompatible update), the watchdog no longer relaunches it forever in a screen-flashing restart storm. After a few rapid relaunches it backs off, leaves the panel on whatever it can show (the admin launcher), and raises a **"Dashboard app is crash-looping"** health warning on the Install tab explaining how to recover. The warning clears automatically once the dashboard comes back healthy.
- **CPU profile stays in sync when changed outside ha-paneld** — the local-state → HA sync now covers the CPU scaling governor: if a thermal daemon or another app changes the governor, HA's "CPU profile" reflects it on the next heartbeat (same deadband/settle rule as brightness and volume, so no broker flooding). Recent "changed outside MQTT" events (brightness, volume, backlight, governor) are also surfaced on the info page and in the `/diag` dump for debugging.
- **Detect + repair a Companion with no internal URL** — a Home Assistant Companion whose server has an empty internal URL makes it request a host-less address, which recent Home Assistant releases reject with a full-screen *"Missing 'Host' header"* — a blank dashboard that's easy to misread as a broken panel. On a rooted panel ha-paneld now detects this and the Install tab offers a one-tap **"Repair internal URL"** that copies the server's external URL into the empty internal URL and relaunches the Companion. The database is read via an immutable, read-only handle so the check never disturbs the app's data.

## v0.8.6-rc4 - 2026-07-03

### Added

- **Provisioning tames the recommended vendor apps by default** — `scripts/provision.sh` now disables a panel profile's known-intrusive vendor apps (on NSPanel Pro: the eWeLink control panel overlay + the factory test tools) as part of provisioning, so a freshly set-up panel isn't obstructed by vendor clutter. It's fully reversible and **can never strand the panel** (a home launcher is only tamed once ha-paneld's admin launcher is a fallback home, and the home role is handed over first); pass `--no-tame` to skip. A stock, un-provisioned panel is never modified on its own — only running provisioning applies it. The `:8888` tame picker also gains a one-click "tame all recommended".
- **Diagnostic sensors for Home Assistant (opt-in)** — a new **Diagnostics** section in the Configure tab exposes the panel's **IP address, CPU usage, memory usage, SoC temperature and boot time** as HA sensor entities. Every one is **off by default** (panel-local until you tick its "expose to HA" pip), and the values are pushed with a per-metric deadband so they never flood the broker. CPU and SoC temperature need root/su (unavailable on sandbox-walled panels); IP, memory and boot time work everywhere.

### Fixed

- **Home Assistant entity ids are anchored to the panel id, not the friendly name** — new MQTT entities now carry a `default_entity_id` of `<domain>.<panel_id>_<capability>`, so their entity ids stay `sensor.<panel>_ha_paneld_*` regardless of the device's friendly name and no longer drift when you rename it. The friendly (device) name is still used for the display name, so you keep pretty names AND stable ids (the same approach Zigbee2MQTT uses). Existing entities are unchanged — the id is applied only at first registration — so there's no churn; only newly-created or re-registered entities get the pinned id. (HA ignores the older `object_id` discovery field, which is why the ids were drifting.)
- **Vendor kiosk launchers (eWeLink) can be tamed and no longer hijack the Launcher button** — on Sonoff panels the eWeLink control panel registers itself as a HOME launcher, which made it both un-tameable (the brick-guard protects home launchers so the panel can't be stranded with no home) and the app the navbar Launcher button opened, obstructing the dashboard. Taming a vendor home launcher is now allowed as long as ha-paneld's own admin launcher remains as the fallback home — the home role is handed over before the app is disabled, so the panel can never be stranded — and the Launcher button skips known vendor kiosks, falling through to a real launcher or ha-paneld's admin launcher. eWeLink now appears in the tame picker's "Recommended" group on NSPanel Pro. On a panel where the Launcher button would only fall through to the admin launcher (no separate launcher installed), it's now shown **disabled** with an explanatory tooltip rather than duplicating the Admin launcher button.

## v0.8.6-rc3 - 2026-07-02

### Added

- **Home dashboard shows what "auto" resolves to** — an unset per-panel home dashboard now reads `auto (HA default view)` in the dashboard row and Configure placeholder, instead of a bare `—`, so it's clear a reload lands on Home Assistant's own default view.

### Changed

- **Publishing a setting to Home Assistant is now opt-in per setting** — each setting declares whether it appears in HA by default; the admin-oriented ones (self-update, Companion auto-update + channels, network ADB, Zigbee router, app watchdog, brightness bias) are now **panel-local by default** — configurable in the `:8888` web UI but not cluttering HA unless you enable their expose toggle. The everyday controls (wake-on-wave, prevent-idle-dim, navbar, auto-brightness, touch sound, CPU profile) stay in HA as before. Existing panels keep whatever they already expose; this only changes freshly-provisioned panels. Touch sound and boot-chime silence now default on for new panels.

### Fixed

- **The Launcher button always lands somewhere** — on kiosk panels with no dedicated launcher app (the Companion registers as home but is the dashboard, and the vendor pseudo-launcher may be tamed or absent), the navbar Launcher key did nothing and the launcher setting read a dead `—`. It now falls back to ha-paneld's own admin launcher (the same path the web UI's launcher action already used), and the setting reads `auto (ha-paneld admin launcher)` when nothing else resolves.
- **Local changes now reach HA — a general state-sync keeps MQTT truthful** — values changed OUTSIDE ha-paneld (auto-brightness or any local app writing the brightness setting, hardware volume keys, vendor firmware dimming the backlight node) previously left HA showing stale state until the next command. A per-channel sync now runs on the existing heartbeat: it publishes only when a value differs from the last-published state beyond a deadband AND has stopped moving (a fast-oscillating value publishes nothing until it settles; a slow ramp publishes at most once per tick; steady state publishes zero messages) — so HA tracks reality without MQTT flapping. Channels: commanded brightness, volume, and the effective backlight (below).
- **`light.<panel>_screen` now reports the REAL backlight level** — panel firmware (NSPanel Pro idle-dim; also observed on the TPA10) moves the hardware backlight without touching Android's brightness setting, so the HA entity showed a stale level until the next command. ha-paneld now reads the effective level from the backlight sysfs (plain file read where permitted, root, or two new helper-daemon verbs `BLREAD`/`BLSET` on no-root panels), reconciles HA whenever it drifts from the last reported value, and the sensors endpoint + dashboard report the same effective value. Brightness commands on no-root daemon panels now also drive the hardware node directly, so they take effect past a firmware dim. Daemon panels need the updated helper binary (redeployed automatically by `install-daemon.sh`).

## v0.8.6-rc2 - 2026-07-02

### Added

- **"auto" now shows what it picked** — the Dashboard app and Launcher app settings display the detected package when unset: the read-only dashboard rows show e.g. `auto (io.homeassistant.companion.android.minimal)`, and the Configure fields carry the same as a placeholder — so "auto" is never a mystery.

### Changed

- **The panel's immutable hardware id anchors its HA device** — discovery now carries a second device identifier derived from the Android device ID (alongside the panel_id one), so Home Assistant recognises the same physical panel across a panel_id change instead of creating a duplicate device. Existing devices gain the new identifier automatically on the next discovery publish.
- **Stable entity ids for new registrations** — every MQTT discovery payload now carries an `object_id` keyed to the panel's unique `panel_id`, so Home Assistant names newly-registered entities `<domain>.<panel_id>_<capability>` instead of deriving the id from the device's friendly display name. Friendly names remain purely cosmetic, and two panels sharing a display name can no longer collide into `_2` suffixes. Entities already registered in HA keep their existing ids (HA pins them by `unique_id`) — no churn on existing setups.
- **provision.sh vendor-strip guard modernised** — the replacement-home check now accepts the HA Companion (either variant) or ha-paneld's built-in admin launcher; the deprecated `l.l` sideload launcher is no longer referenced.
- **Configure tab renders instantly** — the Display card, form values and schema now read through the same cached probe snapshot as the dashboard (stale-while-revalidate: last-known values render immediately, a background refresh keeps them current), removing the multi-second block that recurred whenever the density probe cache had expired.

## v0.8.6-rc1 - 2026-07-02

### Added

- **Live log viewer** — a new **Logs** tab tails the panel's logs in the browser, no `adb logcat` needed: pick **App** (ha-paneld's own process log, works with no root) or **System** (the full device logcat, root panels), filter by level or text, pause/resume, and follow the tail. Served as a plain Server-Sent-Events stream at `/api/v1/logs/stream` (also `curl -N`-able). Tokens/passwords are redacted server-side by the same single pass that guards remote log shipping — the capture subprocess only runs while someone is watching (or shipping is on) and stops when the last viewer disconnects.
- **Live Sensors card** — the Dashboard now shows live readings from **all of the panel's sensors** — ambient light, proximity (near/far + raw), temperature, humidity, plus volume and brightness — refreshed every 2 seconds via a new `/api/v1/sensors` endpoint. Readings are the hardware's live values, shown **even when a sensor is hidden from Home Assistant** by the exposure config, with an age indicator per reading; the card renderer is reusable so other tabs can mount it.
- **Companion auto-update channel** — the Companion updater gains the same **Stable / Pre-release** channel select as ha-paneld's own auto-update (`select.<panel>_companion_update_channel`, default Stable; also on the Configure tab). Switching channel triggers an immediate check when Companion auto-update is on.
- **Config export/restore from the provision script** — `provision.sh --export FILE` saves a panel's full config bundle (includes secrets — protect the file), `--restore FILE` best-effort-imports a bundle (full restore, device keys included), `--restore-fleet FILE` applies only the portable keys for cross-panel deployment. Completes the bundle feature across API + web UI + fleet scripts.

### Changed

- **The web UI loads instantly** — the Dashboard previously gathered every root-probed value (Zigbee state, CPU governor, network-ADB, density, touch sound…) before sending any HTML, leaving a blank page for 10+ seconds on slower panels (and ~4s on the Configure tab). The page shell now renders immediately and the probe-backed values hydrate in place from a new `GET /api/v1/info`; the probes themselves run through a cached, single-flight snapshot (pre-warmed at start, refreshed at most every 15s, invalidated on config changes), which also makes `/api/v1/diag` and the Configure tab fast.
- **Config import is now best-effort** — a bundle exported from a different panel model or a different ha-paneld version restores everything it validly can: valid keys apply, invalid values are reported and skipped, unknown keys warn and skip (previously a single invalid value rejected the whole bundle). `?strict=1` keeps the old all-or-nothing behaviour; dry-run previews now list what would be skipped. Status reports `applied` / `partial` / `rejected`.
- **Clearer update-entity names** — "Self-update" is now **"ha-paneld auto-update"** and "Update channel" is **"ha-paneld auto-update channel"**, so it's obvious they apply to ha-paneld and not the Companion app. Display names only — entity ids are unchanged, so no Home Assistant entity churn. (#18)

### Fixed

- **False "credentials rejected" while connected** — after a connection rebuild, the superseded MQTT client could keep auto-reconnecting in the background before its teardown completed; its rejected attempts overwrote the live connection's status, so the UI warned about invalid MQTT credentials (and the watchdog force-rebuilt every couple of minutes) while the panel was in fact connected and healthy. Superseded clients are now ignored by the status listeners and told to stop reconnecting, and the credentials warning only appears when the rejection is persistent — a transient rejection during a broker restart renders as "reconnecting…" instead. This also explains (and ends) the long-standing pattern of NOT_AUTHORIZED loops from a broker that accepts a fresh client.
- **No more false Companion "update available" for the installed variant** — the update check compared the installed `-minimal` versionName against the release tag as if the variant suffix were a prerelease marker, so a panel on `2026.6.5-minimal` was offered "2026.6.5" as an upgrade. Versions are now compared variant-stripped, in both the banner check and the auto-updater. (#17)
- **A brightness command can never blank the panel** — the HA brightness command and auto-brightness could drive the backlight to 0 outside the screen-off path, leaving the panel dark until the never-blank watchdog re-lit it. The brightness setter now floors at a minimum-visible level; a deliberate screen-off remains the only path to 0.
- **Config saves are atomic** — the Configure form previously applied fields one by one, so an interruption mid-save could persist a half-written config (e.g. a broker without its credentials). All fields now commit in a single atomic write, and the saved config migrates across schema changes on upgrade.
- **A hung `su` can no longer stall the app** — one-shot root commands now run with a hard timeout and are force-killed on expiry, degrading to "no root" instead of occupying a thread forever when a root shell hangs on auth.

### Security

- **Cross-site request forgery (CSRF) protection** on the `:8888` control API — a state-changing request (e.g. a `POST` to reconfigure MQTT, reboot the panel, or play audio) is now refused when it carries a browser `Origin`/`Referer` from a different site, so a malicious web page a user on your LAN happens to visit can no longer silently drive the panel. The panel's own web UI and non-browser callers (Home Assistant `rest_command`, `curl`, monitors) are unaffected.
- **DNS-rebinding protection** — the API only answers to a `Host` of the panel's IP address, `localhost`, an `*.local` (mDNS) name, or a name you allowlist (the new `http_allowed_hosts` setting); other hostnames are refused. Reaching a panel by IP (the usual way) is unchanged.
- **App and Companion updates download over HTTPS only** — the installer now refuses a plaintext (`http`) redirect at any hop in the download chain, on top of the existing signature + package pin that already blocks installing a tampered APK.

## v0.8.5 - 2026-07-02

The reliability release: MQTT connectivity that survives broker restarts, network flaps and flaky address families; safe, pinned app updating; and the first cut of the redesigned web UI. This canonical note summarises the full 0.8.5 release-candidate line (the dated rc sections below record the development history).

### Added

- **HA Companion app auto-install / update** (opt-in) — self-heals a missing or out-of-date minimal Companion over root, gated by a **signer + package allowlist** (pinned official certificate; a MITM/compromised asset can't install).
- **ha-paneld self-update with Stable / Pre-release channels** (opt-in, default off — auto-pull from the release repo is a supply-chain decision each operator makes deliberately; same pinned-signer install path) with no silent downgrades.
- **IPv6 as a first-class transport** — the broker connect resolves with an address-family preference and the watchdog flips family when one path won't hold, so panels land on whichever network family actually works; IPv6 broker literals parse correctly; a Wi-Fi high-performance lock plus a partial wakelock (`keep_awake`, on by default) keep the radio and SoC from power-save-stalling idle connections; an IPv6-loopback test harness guards regressions.
- **Redesigned web UI (first iteration, evolving)** — tabbed multi-page app (Dashboard · Configure · Test · Install · Fleet · API), a declarative settings registry driving a schema-generated Configure form, per-entity **Home Assistant exposure toggles**, a canonical versioned **`/api/v1`** (flat paths 308-redirect; `GET /health` + `POST /play` stay at the root), config **export/import bundles** with on-panel revision history, and an interactive remote-control screenshot.
- **`/diag` diagnostics depth** — capture timestamp + uptime, and a paste-safe `MQTT state` line (connection state, broker-ACK liveness age, family preference) plus wakelock state.
- **Reload returns to the intended dashboard** — a per-panel home-dashboard setting that reload navigates back to.

### Fixed

- **MQTT reliability, end to end** — the headline of this release. Stuck-offline after an HA/broker restart (stalled auto-reconnect) self-heals via a service-level reconnect watchdog + network-regained nudge; half-open (CLOSE-WAIT) connections that still claim "connected" are caught by **broker-ACK liveness** with a 30 s keepalive and heartbeat; the watchdog runs on a dedicated thread immune to thread-pool starvation; and the watchdog itself can never be frozen by the wedged connection it supervises — its probe and rebuild run on guarded side-threads with detached teardown. Panels that used to sit "unavailable" in Home Assistant until an app restart now self-heal within ~2–3 minutes.
- **Never-blank-screen guard** — retained/stale MQTT screen-off commands are ignored, deliberate screen-offs are tracked, and a watchdog re-lights an unintentionally dark panel.
- **No zombie entities across upgrades** — discovery is published un-retained and actively pruned on version change, so removed/renamed entities disappear from Home Assistant instead of lingering.
- **ANR under a wedged broker** — sensor callbacks moved off the main thread.
- **Network-adb persistence** — re-asserted at boot/reconnect when enabled; ownership-aware off.

### Docs

- NSPanel Pro firmware-quirks-by-version table; helper daemon verb-contract CI cross-check; connection-cap unit tests.

## v0.8.5-rc11 - 2026-07-02

### Fixed

- **Panels no longer stay "unavailable" in Home Assistant after a broker drop** — the connection watchdog could freeze inside its own liveness probe: the heartbeat publish runs into the same internal client monitor that a wedged (half-open) connection holds, so the exact failure the watchdog exists to heal also disabled the watchdog, leaving the panel unavailable until a manual app restart. The watchdog loop now never makes a potentially-blocking MQTT call itself: the heartbeat and the connection rebuild each run on their own guarded side-thread (skipped while a previous one is still in flight, with a timeout that re-arms healing if a rebuild wedges), and tearing down a wedged client is fully detached from bringing up its replacement. A stalled connection now self-heals within ~2–3 minutes, flipping address family so it lands on whichever network path actually holds.

## v0.8.5-rc10 - 2026-07-02

### Fixed

- **ANR under a wedged MQTT connection** — the light/proximity/temperature/humidity sensor callbacks were delivered on the **main thread**, and each fans out to an MQTT publish. A HiveMQ publish can block on an internal monitor while the broker connection is (re)establishing, so a stalled broker plus a live light sensor could hang the UI thread → "ha-paneld isn't responding". Sensor callbacks now run on a dedicated background thread, so a slow/blocked publish can never stall the app. (Surfaced when the whole fleet reconnected to one broker at once after an update.)

## v0.8.5-rc9 - 2026-07-01

> **⚠ The web UI has been significantly revised in this release — and it is a work in progress.**
> The single-page info/control surface is now a tabbed, multi-page app (Dashboard · Configure · Test ·
> Install · Fleet · API), and configuration has moved to a schema-driven Configure tab. Expect the
> layout, grouping and navigation to **keep changing over the next few releases** as the design
> settles — treat the current arrangement as a first iteration, not the final shape.
> **Nothing has been removed**: every control, value and workflow from the previous UI is still
> present (some have moved — proximity tuning, display sizing and vendor taming now live on the
> Configure tab, with their values still visible on the Dashboard), the old flat HTTP paths redirect
> permanently (308) to the new `/api/v1` API — with `GET /health` and `POST /play` still served at the
> root for plain-`curl` automations — and the MQTT entities are unchanged by default. The redesign
> runs on its own track and does not slow the regular feature release pace.

HTTP UI redesign — a declarative settings registry, configurable Home Assistant exposure, a canonical config API, config bundles with on-panel history, and a tabbed multi-page web UI.

### Added

- **Declarative settings registry** — a single source of truth (`config/SettingsRegistry`) describing each setting's type, group, Basic/Advanced tier, portability scope, validation, and Home Assistant entity. Drives the config API, the generated form, and MQTT discovery so the three can't drift. Pure/unit-tested, with golden tests asserting byte-identical discovery payloads.
- **Configurable HA exposure** — every config entity gains a per-panel "expose to Home Assistant" toggle. Hiding one clears its retained discovery (the entity leaves HA entirely — zero recorder / state-machine cost). Set from the new Configure page or `POST /api/v1/config` (`ha_expose_<key>`).
- **Canonical config over HTTP** — the formerly MQTT-only settings (`wake_on_wave`, `prevent_idle_dim`, `watchdog`, `auto_brightness`, `brightness_bias`, `navbar`, `touch_sound`, `cpu_governor`, `network_adb`, `zigbee_router`, `ambient_lux`) are now settable via the config API, applied through the same path an HA command uses.
- **`/api/v1` namespace** — the machine API is now versioned and canonical under `/api/v1` (config, schema, bundles, perf, proximity, diag, action, tame, display, inspect, screenshot, input, status). The pre-0.8.5 flat paths respond **308 Permanent Redirect** (method + body preserved) to their `/api/v1` homes; `GET /health` and `POST /play` — the external contract endpoints — also stay served at the root for plain-`curl` callers. `provision.sh` and the OpenAPI spec/explorer target `/api/v1`.
- **Config bundles + revision history** — `GET /api/v1/config/export` (versioned, secrets excluded by default) and a transactional `POST /api/v1/config/import` (migrate → scope/secret filter → validate-all-or-reject → snapshot → apply; `?dry_run=1` previews the diff, `?mode=fleet` applies only portable keys). On-panel revision ring buffer with `GET /api/v1/config/revisions` + restore.
- **Tabbed web UI** — Dashboard / Configure / Test / Install / Fleet / API. **Configure** is a schema-driven Basic/Advanced form with inline expose pips and bundle backup/restore; **Test** adds an interactive screenshot (View/Control — tap the image to touch the panel) plus on-screen nav actions and a TTS test; **Install** surfaces panel-health warnings + the capabilities matrix. Self-contained, offline, no build step.

## v0.8.5-rc8 - 2026-07-01

### Added

- **IPv6 treated as a first-class transport for MQTT** — ha-paneld now resolves the broker and connects with an address-family preference, and the connection watchdog flips family on a stalled reconnect, so if one family won't hold (e.g. a flaky IPv6 path) the panel automatically lands on whichever works instead of flapping. It also holds a **Wi-Fi high-performance lock** (alongside the existing wakelock) so the radio never drops into power-save and stalls an idle connection. IPv6 broker literals (`tcp://[…]:1883`) are now parsed correctly, and a new test harness (broker-address parsing + a real IPv6-loopback connect test) guards against IPv6 regressions.

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
