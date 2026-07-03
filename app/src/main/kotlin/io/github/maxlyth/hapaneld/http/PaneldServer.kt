package io.github.maxlyth.hapaneld.http

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.AudioPlayer
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.config.ConfigBundle
import io.github.maxlyth.hapaneld.config.ConfigDiff
import io.github.maxlyth.hapaneld.config.Migrations
import io.github.maxlyth.hapaneld.config.Scope
import io.github.maxlyth.hapaneld.config.SettingType
import io.github.maxlyth.hapaneld.config.SettingValue
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.config.Validation
import io.github.maxlyth.hapaneld.control.CdpRelay
import io.github.maxlyth.hapaneld.control.CompanionDb
import io.github.maxlyth.hapaneld.control.DensityController
import io.github.maxlyth.hapaneld.control.Su
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.control.TameController
import io.github.maxlyth.hapaneld.control.VolumeController
import io.github.maxlyth.hapaneld.device.TameCandidate
import io.github.maxlyth.hapaneld.input.PanelAccessibilityService
import io.github.maxlyth.hapaneld.logship.LogCapture
import io.github.maxlyth.hapaneld.sensors.SensorReporter
import io.github.maxlyth.hapaneld.util.Cached
import io.github.maxlyth.hapaneld.util.HelperClient
import io.github.maxlyth.hapaneld.util.Json
import io.github.maxlyth.hapaneld.util.UpdateChecker
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Ktor CIO HTTP surface on :8888. Serves the TTS-announce contract plus a small panel info/config
 * UI at `/` (the device's `configuration_url`, so HA shows a "Visit" link).
 *
 * Routes:
 *   GET  /         panel info + panel_id config form (HTML)
 *   POST /config   set panel_id (form `panel_id`), then live-reconfigure
 *   GET  /health   200 with version + panel id
 *   POST /play     body has a URL (raw or `{"url":"…"}`) -> 200 "playing", background playback;
 *                  no URL -> 400 "no-url"
 *
 * [info] returns the ordered key/value facts to render; [onConfig] applies new settings (panel id
 * + MQTT broker/user/password). Both are supplied by the service, which owns the runtime objects.
 */
class PaneldServer(
    private val config: Config,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val sensors: SensorReporter,
    // For the on-screen Controls card (software navbar) on panels with no physical nav bar.
    private val system: SystemController,
    private val volume: VolumeController,
    // Called after this server has written new settings to [config]; the service rebuilds MQTT/mDNS.
    private val onReconfigure: () -> Unit,
    // Applies a single behaviour setting through the MQTT bridge's command path (persist → drive
    // hardware → publish HA state). Lets the config API set the formerly MQTT-only keys identically
    // to an HA command. Returns true if the key was a recognised live setting.
    private val applySetting: (String, String) -> Boolean,
    // Current values of the CONTROLLER-sourced settings (touch_sound, cpu_governor, network_adb,
    // zigbee_router) — their state lives in controllers, not SharedPreferences, so the config
    // form/schema/dashboard read them through this instead of Config.getRaw.
    private val liveValues: () -> Map<String, String> = { emptyMap() },
    // This panel's capability snapshot (sensors + cpu/adb/zigbee probes) for availableWhen gating.
    private val capabilities: () -> Capabilities = { Capabilities() },
    private val info: () -> Map<String, String>,
    // Per-panel "HA-optimised" density + text-scale suggestions (DeviceProfile), or null.
    private val recommendedDensity: Int? = null,
    private val recommendedFontScale: Float? = null,
    // Vendor-taming: the controller (applies the action) and the profile's curated recommendations (the
    // picker's "Recommended" group).
    private val tame: TameController,
    private val tameProfileCandidates: List<TameCandidate> = emptyList(),
    // Live log viewer sources (Logs tab / SSE stream). App = own-process logcat (no root); system =
    // full logcat via su, gated on Su.available() at request time. Null → the viewer 404s.
    private val logApp: LogCapture? = null,
    private val logSystem: LogCapture? = null,
    // EFFECTIVE backlight (sysfs actual_brightness via BrightnessController, cached) — the sensors
    // endpoint + Live-state row report what the hardware is doing, not just the Android setting.
    private val effectiveBrightness: () -> Int = { -1 },
    // Trigger a WebView auto-heal (download + install the profile's recommended System WebView). Injected
    // by the service (needs Context + a coroutine); runs off the request thread. Fired by the Install-tab button.
    private val onHealWebView: () -> Unit = {},
    // Repair a Companion server row with an empty internal_url (the HA 2026.7 "Missing Host header"
    // incident). Injected by the service; runs off the request thread. Fired by the Install-tab button.
    private val onRepairCompanionUrl: () -> Unit = {},
) {
    // Per-INSTALL build token (changes on every (re)install, not just a version bump) so an open info
    // page can auto-reload after the app is updated — even a same-version dev re-spin. /health carries it.
    private fun buildToken(): String =
        runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime.toString() }
            .getOrDefault(Config.VERSION)

    /** True if [host] (the request's source IP) is a LAN-local address: loopback, RFC1918, link-local, or
     *  IPv6 ULA. Global/public sources return false → 403. Parses with InetAddress, which unmaps an
     *  IPv4-mapped IPv6 source (`::ffff:a.b.c.d`) to its IPv4 form, so the dual-stack bind can't smuggle a
     *  public IPv4 past the RFC1918 check. Strips any `%zone` and leading `/`. */
    private fun isLocalSource(host: String): Boolean = runCatching {
        val a = java.net.InetAddress.getByName(host.substringBefore('%').removePrefix("/"))
        a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isAnyLocalAddress ||
            (a is java.net.Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc) // fc00::/7 ULA
    }.getOrDefault(false)

    // Panel-info rows blurred by default (screenshot hygiene) — identity + network values a casual share
    // shouldn't leak. "Reveal" un-blurs them. Not access control: the values are still in the page source.
    private val SECRET_FIELDS = setOf("Device ID", "MQTT")
    // Address rows blur ONLY when the value is globally ROUTABLE — an unroutable RFC1918 / ULA / link-local
    // address (e.g. the LAN IPv4, or a ULA v6) has no external use, so it stays visible.
    private val ADDRESS_FIELDS = setOf("Local IP", "Local IPv6")

    /** True only for a parseable, globally-routable address (not loopback / RFC1918 / ULA / link-local).
     *  Unparseable values (e.g. "—") return false → not blurred. */
    private fun isRoutable(host: String): Boolean = runCatching {
        val a = java.net.InetAddress.getByName(host.substringBefore('%').removePrefix("/"))
        !(a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isAnyLocalAddress ||
            (a is java.net.Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc))
    }.getOrDefault(false)

    /** Appends a calculated diagonal to the Display row ("…dpi · 6.4″"), assuming square pixels
     *  (diag_in = hypot(w,h)/dpi). The diagonal is clickable to toggle ″↔cm, with W×H in the hover title. */
    private fun displayCell(v: String): String {
        val n = Regex("\\d+").findAll(v).mapNotNull { it.value.toIntOrNull() }.toList()
        if (n.size < 3 || n[2] == 0) return esc(v)
        val (w, h, dpi) = Triple(n[0], n[1], n[2])
        val inch = Math.hypot(w.toDouble(), h.toDouble()) / dpi
        val inchS = "%.1f".format(inch)
        val cmS = "%.1f".format(inch * 2.54)
        val title = "W %.1f × H %.1f cm".format(w * 2.54 / dpi, h * 2.54 / dpi)
        return """${esc(v)} · <span class="diag" data-in="$inchS″" data-cm="$cmS cm" """ +
            """title="${esc(title)}" onclick="diagToggle(this)">$inchS″</span>"""
    }

    // Display sizing (density + text scale) via `wm density` / `font_scale` — su panels only.
    private val density = DensityController()
    // On-panel config revision history (ring buffer) — written on every successful apply.
    private val revisions = RevisionStore(appContext.filesDir)
    private val urlRegex = Regex("""https?://[^\s"']+""")
    // Stored as a stop lambda over a type-inferred server local, so we never have to name Ktor's
    // EmbeddedServer<TEngine, TConfiguration> generic type (which shifts between Ktor versions).
    private var stopServer: (() -> Unit)? = null

    fun start() {
        // Bind the IPv6 wildcard "::" — on Android this is dual-stack (net.ipv6.bindv6only=0), so the
        // server answers on both IPv6 and IPv4, instead of the IPv4-only default 0.0.0.0.
        val server = embeddedServer(CIO, port = config.httpPort, host = "::") {
            // 0.8.1 security: refuse any request whose SOURCE is not LAN-local. The unauthenticated control
            // surface answers on the panel's globally-routable IPv6 (dual-stack "::"), so without this it can
            // be reached from the internet whenever the home router doesn't firewall inbound IPv6 — and we
            // must not depend on that. Allow loopback / RFC1918 / link-local / ULA; global/public source 403s.
            // (Known limitation to iterate on: a LAN peer reaching the panel via its *global* v6 uses a global
            // source and is also rejected — use IPv4 on-LAN; a same-/64-prefix exception is the next refinement.)
            intercept(ApplicationCallPipeline.Plugins) {
                // Use origin.remoteAddress (the RAW peer IP), NOT remoteHost — remoteHost reverse-resolves to
                // a hostname, and forward-resolving that picks a (possibly global) address that fails the
                // RFC1918 check, 403-ing legitimate LAN clients. Verified: remoteAddress returns 172.31.x etc.
                if (!isLocalSource(call.request.origin.remoteAddress)) {
                    call.respondText("forbidden\n", status = HttpStatusCode.Forbidden)
                    return@intercept finish()
                }
                // CSRF guard: a LAN browser on a malicious page must not be able to silently drive a
                // state-changing endpoint (e.g. POST /config → MQTT takeover). Cross-origin writes carry
                // a mismatched Origin/Referer and are refused; same-origin UI fetches and header-less API
                // clients (curl / HA rest_command) pass. See OriginGuard.
                if (!OriginGuard.allowed(
                        call.request.origin.method.value,
                        call.request.headers["Origin"],
                        call.request.headers["Referer"],
                        call.request.headers["Host"],
                    )
                ) {
                    call.respondText("cross-origin refused\n", status = HttpStatusCode.Forbidden)
                    return@intercept finish()
                }
                // Anti-DNS-rebinding: pin the Host header to unrebindable values (IP / localhost /
                // *.local) + any configured names, so a rebound hostname can't read secrets or drive
                // the surface as "same-origin". See OriginGuard.hostAllowed.
                if (!OriginGuard.hostAllowed(call.request.headers["Host"], config.httpAllowedHosts)) {
                    call.respondText("host not allowed\n", status = HttpStatusCode.Forbidden)
                    return@intercept finish()
                }
            }
            routing {
                get("/") {
                    call.respondText(infoHtml(), ContentType.Text.Html)
                }
                // Static front-end assets (externalised from the Kotlin string so CI can lint them).
                get("/info.js") {
                    call.response.headers.append("Cache-Control", "no-cache")  // assets iterate; always serve fresh
                    call.respondText(asset("info.js"), ContentType.Application.JavaScript)
                }
                get("/info.css") {
                    call.response.headers.append("Cache-Control", "no-cache")
                    call.respondText(asset("info.css"), ContentType.Text.CSS)
                }
                get("/icon.svg") {
                    call.respondText(asset("icon.svg"), ContentType.Image.SVG)
                }
                // Generic bundled-asset server for the redesigned UI (page scripts + vendored libs).
                get("/assets/{f...}") {
                    val rel = call.parameters.getAll("f")?.joinToString("/").orEmpty()
                    val body = if (rel.isEmpty() || rel.contains("..")) null else runCatching { asset(rel) }.getOrNull()
                    if (body == null) {
                        call.respondText("not found\n", status = HttpStatusCode.NotFound)
                    } else {
                        val ct = when {
                            rel.endsWith(".js") -> ContentType.Application.JavaScript
                            rel.endsWith(".css") -> ContentType.Text.CSS
                            rel.endsWith(".svg") -> ContentType.Image.SVG
                            rel.endsWith(".json") -> ContentType.Application.Json
                            else -> ContentType.Text.Plain
                        }
                        call.response.headers.append("Cache-Control", "no-cache")
                        call.respondText(body, ct)
                    }
                }
                // Tabbed multi-page shell. `/` stays the existing dashboard (now with a tab bar); the
                // other tabs are dedicated pages that consume /api/v1.
                get("/configure") { call.respondText(page("configure", "Configure", configureBody()), ContentType.Text.Html) }
                get("/test") { call.respondText(page("test", "Test", testBody()), ContentType.Text.Html) }
                get("/install") { call.respondText(page("install", "Install", installBody()), ContentType.Text.Html) }
                get("/fleet") { call.respondText(page("fleet", "Fleet", fleetBody()), ContentType.Text.Html) }
                get("/logs") { call.respondText(page("logs", "Logs", logsBody()), ContentType.Text.Html) }
                // Self-contained REST API explorer (no Swagger-UI CDN bundle) + the OpenAPI spec it
                // renders — the spec also imports into Swagger/Postman for fleet tooling.
                get("/api") {
                    call.respondText(asset("api.html"), ContentType.Text.Html)
                }
                get("/health") {
                    call.respondText("ha-paneld ${Config.VERSION} panel=${config.panelId} build=${buildToken()}\n")
                }
                // TTS/announce contract — REAL at the root (external automations call it with plain
                // curl, which doesn't follow 308) as well as under /api/v1.
                post("/play") { handlePlay(call) }
                // Pre-0.8.5 flat machine endpoints → 308 to their /api/v1 homes.
                legacyRedirects()

                // ---- /api/v1 — the canonical machine API (0.8.5 conformity pass). Every machine
                // endpoint lives here; the pre-0.8.5 flat paths 308 to their v1 homes (method + body
                // preserved), except /health and /play which stay REAL at the root too — they're the
                // external "contract" endpoints called by plain curl (no -L) from HA automations and
                // monitors. Human pages + static assets stay top-level. ----
                route("/api/v1") {
                    get("/health") {
                        call.respondText("ha-paneld ${Config.VERSION} panel=${config.panelId} build=${buildToken()}\n")
                    }
                    get("/config") { call.respondText(configJson(), ContentType.Application.Json) }
                    post("/config") { handleConfigPost(call) }
                    get("/config/schema") { call.respondText(configSchemaJson(), ContentType.Application.Json) }
                    // Versioned config bundle: backup (export) and transactional restore/deploy (import).
                    get("/config/export") { handleConfigExport(call) }
                    post("/config/import") { handleConfigImport(call) }
                    // On-panel revision history + rollback.
                    get("/config/revisions") { call.respondText(revisionsJson(), ContentType.Application.Json) }
                    post("/config/revisions/{id}/restore") {
                        val id = call.parameters["id"]?.toLongOrNull()
                        if (id == null) call.respondText("bad-id\n", status = HttpStatusCode.BadRequest)
                        else handleRevisionRestore(call, id)
                    }
                    get("/perf") {
                        PerfReader.touch()
                        call.respondText(PerfReader.json(), ContentType.Application.Json)
                    }
                    get("/proximity") { call.respondText(sensors.proximityJson(), ContentType.Application.Json) }
                    // Live Sensors card: last-published values + live extras. Volume is the current
                    // media-stream percent; brightness is the system setting (0-255, -1 unknown).
                    get("/sensors") {
                        // Effective backlight first (reflects firmware dims); raw setting as fallback.
                        val bright = effectiveBrightness().takeIf { it >= 0 } ?: runCatching {
                            android.provider.Settings.System.getInt(appContext.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
                        }.getOrDefault(-1)
                        call.respondText(
                            """{${sensors.valuesJson()},"volume_pct":${runCatching { volume.getPercent() }.getOrDefault(-1)},"brightness":$bright}""",
                            ContentType.Application.Json,
                        )
                    }
                    // Hydration payload for the dashboard (see infoJson) — the one place the probe
                    // suite actually runs; cached + single-flight, so concurrent viewers share it.
                    get("/info") {
                        call.respondText(withContext(Dispatchers.IO) { infoJson() }, ContentType.Application.Json)
                    }
                    get("/diag") {
                        // Reuses the snapshot facts (≤15s old) instead of re-running the probe suite.
                        val facts = withContext(Dispatchers.IO) { snapCache.get().facts }
                        call.respondText(DiagReader.dump(appContext, facts), ContentType.Text.Plain)
                    }
                    // Live log tail as Server-Sent Events (?source=app|system). Feeds the Logs tab;
                    // also curl-able (`curl -N .../api/v1/logs/stream`). Lines are pre-redacted.
                    get("/logs/stream") { handleLogStream(call) }
                    // Health + capabilities as JSON (warnings as ready-to-render HTML) — feeds every
                    // variant's Install/health section client-side.
                    get("/status") { call.respondText(statusJson(), ContentType.Application.Json) }
                    // Auto-heal the System WebView (download + install the profile's recommended build).
                    // Fire-and-forget: the install runs off-thread (large download); the client refreshes.
                    post("/webview/heal") {
                        onHealWebView()
                        call.respondText("""{"status":"started"}""", ContentType.Application.Json)
                    }
                    // Repair a Companion server row with an empty internal_url (HA 2026.7 "Missing Host
                    // header" incident). Fire-and-forget: the repair force-stops + relaunches the Companion
                    // off-thread; invalidate the health cache so the warning clears on the next poll.
                    post("/companion/repair-url") {
                        onRepairCompanionUrl()
                        companionUrlCache.invalidate()
                        call.respondText("""{"status":"started"}""", ContentType.Application.Json)
                    }
                    // Per-panel Canvas dashboard layout (opaque Gridstack JSON, stored in Config).
                    get("/ui/layout") {
                        call.respondText("""{"layout":${jsonStr(config.uiDashboardLayout)}}""", ContentType.Application.Json)
                    }
                    post("/ui/layout") {
                        config.uiDashboardLayout = call.receiveParameters()["layout"].orEmpty()
                        call.respondText("""{"ok":true}""", ContentType.Application.Json)
                    }
                    // Inject a tap at device pixel (x,y) for the interactive screenshot (Test tab). Tiered:
                    // accessibility gesture (no root) → su `input tap`. Nav keys reuse POST /action.
                    post("/input") {
                        val q = call.receiveParameters()
                        val x = q["x"]?.trim()?.toFloatOrNull()
                        val y = q["y"]?.trim()?.toFloatOrNull()
                        if (x == null || y == null) {
                            call.respondText("bad-coords\n", status = HttpStatusCode.BadRequest)
                        } else {
                            val ok = injectTap(x, y)
                            if (ok) call.respondText("ok\n")
                            else call.respondText("no-input-capability\n", status = HttpStatusCode.ServiceUnavailable)
                        }
                    }
                    // Master switch for the instrumentation sampler (enabled=true|false) — persisted + live.
                    post("/instrumentation") {
                        val on = call.receiveParameters()["enabled"]?.toBooleanStrictOrNull()
                        if (on == null) {
                            call.respondText("bad-value\n", status = HttpStatusCode.BadRequest)
                        } else {
                            config.setInstrumentation(on)
                            PerfReader.enabled = on
                            io.github.maxlyth.hapaneld.sensors.SensorTrace.enabled = on
                            if (on) PerfReader.touch()
                            call.respondText("""{"enabled":$on}""", ContentType.Application.Json)
                        }
                    }
                    // On-screen Controls card (software navbar) for panels with no physical nav bar.
                    post("/action") {
                        val a = call.receiveParameters()["a"]
                        val ok = when (a) {
                            "back" -> { PanelAccessibilityService.navBack(); true }
                            "recents" -> { PanelAccessibilityService.navRecents(); true }
                            // Launcher, not Home: the HA Companion IS the home/launcher on these panels, so the
                            // hard, useful action is escaping TO a launcher to reach Settings/config apps.
                            // launchLauncher itself falls back to the admin launcher when nothing resolves
                            // (stale configured pkg, or no launcher app at all) — same path as the navbar key.
                            "launcher" -> { system.launchLauncher(config.launcherPackage); true }
                            "admin_launcher" -> { system.launchAdminLauncher(); true }
                            "reboot" -> { scope.launch { system.reboot() }; true }
                            // step() (adjustStreamVolume) not setPercent: on a coarse stream (e.g. the TPA10's
                            // 7-step STREAM_MUSIC) the current→percent→raw round-trip truncates back to the same
                            // index, so +10% was a no-op. step() always moves one real notch and flashes the slider.
                            "volup" -> { volume.step(up = true); true }
                            "voldn" -> { volume.step(up = false); true }
                            else -> false
                        }
                        if (ok) call.respondText("ok\n") else call.respondText("bad-action\n", status = HttpStatusCode.BadRequest)
                    }
                    // Debug-only sensor trace (RAM ring buffer, instrumentation-gated) for fit-testing the
                    // auto-brightness + proximity filters. CSV by default (drop into a plot); ?format=json
                    // for programmatic use / a future on-panel chart. Not an HA/MQTT surface.
                    get("/sensortrace") {
                        if (call.request.queryParameters["format"] == "json") {
                            call.respondText(io.github.maxlyth.hapaneld.sensors.SensorTrace.toJson(), ContentType.Application.Json)
                        } else {
                            call.respondText(io.github.maxlyth.hapaneld.sensors.SensorTrace.toCsv(), ContentType("text", "csv"))
                        }
                    }
                    // Live panel screenshot via root `screencap` (LAN-only like the rest of this surface).
                    // Embedded scaled in the info page + linkable full-size; also usable as an HA camera
                    // still_image_url. Captured on demand — no background polling.
                    get("/screenshot.png") {
                        // su-direct on su panels; via the root daemon's SCREENCAP on sandbox panels (TPA10).
                        val png = if (io.github.maxlyth.hapaneld.device.DeviceProfile.detect().appCanSu)
                            Su.runBytes("screencap -p")
                        else io.github.maxlyth.hapaneld.util.HelperClient.sendBytes("SCREENCAP")
                        if (png != null && png.isNotEmpty()) {
                            call.respondBytes(png, ContentType.Image.PNG)
                        } else {
                            call.respondText("screenshot-unavailable\n", status = HttpStatusCode.ServiceUnavailable)
                        }
                    }
                    get("/openapi.json") {
                        call.respondText(asset("openapi.json"), ContentType.Application.Json)
                    }
                    // Live proximity state for the tuning UI (raw never goes to HA; it lives here).
                    get("/proximity") {
                        call.respondText(sensors.proximityJson(), ContentType.Application.Json)
                    }
                    // step=near|far -> snapshot the current raw into that calibration slot.
                    post("/proximity/capture") {
                        val step = call.receiveParameters()["step"]
                        val raw = sensors.lastRaw
                        when {
                            step != "near" && step != "far" ->
                                call.respondText("bad-step\n", status = HttpStatusCode.BadRequest)
                            raw.isNaN() ->
                                call.respondText("no-reading\n", status = HttpStatusCode.BadRequest)
                            else -> {
                                config.captureProximity(step, raw)
                                sensors.reevaluate()
                                call.respondText(sensors.proximityJson(), ContentType.Application.Json)
                            }
                        }
                    }
                    // v=<float> -> manual threshold fine-tune (overrides the captured midpoint).
                    post("/proximity/threshold") {
                        val v = call.receiveParameters()["v"]?.toFloatOrNull()
                        if (v == null) {
                            call.respondText("bad-value\n", status = HttpStatusCode.BadRequest)
                        } else {
                            config.setProximityThreshold(v)
                            sensors.reevaluate()
                            call.respondText(sensors.proximityJson(), ContentType.Application.Json)
                        }
                    }
                    // s=HIGH|MEDIUM|LOW -> hysteresis band width (flap resistance).
                    post("/proximity/sensitivity") {
                        config.setProximitySensitivity(call.receiveParameters()["s"].orEmpty())
                        sensors.reevaluate()
                        call.respondText(sensors.proximityJson(), ContentType.Application.Json)
                    }
                    post("/proximity/reset") {
                        config.resetProximityCalibration()
                        sensors.reevaluate()
                        call.respondText(sensors.proximityJson(), ContentType.Application.Json)
                    }
                    // Per-package vendor taming from the Vendor packages card. action=tame adds the package to
                    // the blocklist and tames it now; action=untame removes it and re-enables it. The work is
                    // privileged + slow, so it runs off-thread and the browser gets a short auto-reload back to
                    // the info page (the row's new state shows on reload).
                    post("/tame") {
                        val p = call.receiveParameters()
                        // One-click "Tame all recommended" (the profile's defaultTame set) — no pkg needed.
                        // Persists the actually-tamed packages so they re-apply on boot; tame() is guarded
                        // (hands over the home role + refuses to strand), so this never leaves the panel homeless.
                        if (p["action"]?.trim() == "recommended") {
                            scope.launch {
                                val tamed = tame.applyRecommended(tameProfileCandidates)
                                if (tamed.isNotEmpty()) {
                                    config.setTameVendorPackages((config.tameVendorPackages.toSet() + tamed).joinToString(" "))
                                    snapInvalidate()
                                }
                            }
                            call.respondText(
                                "<!doctype html><meta charset=utf-8><meta http-equiv=refresh content='2;url=/configure'>" +
                                    "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
                                    "applying recommended vendor-app taming…</body>",
                                ContentType.Text.Html,
                            )
                            return@post
                        }
                        val pkg = p["pkg"]?.trim().orEmpty()
                        val untame = p["action"]?.trim() == "untame"
                        // Re-enable is always allowed; taming is refused for protected packages (the brick-guard
                        // — critical AOSP names, vendor-renamed persistent system services, launchers, the IME)
                        // so a hand-typed package name can't disable something the panel needs.
                        if (pkg.isNotEmpty() && (untame || !tame.isProtected(pkg))) {
                            val next = config.tameVendorPackages.toMutableSet()
                            if (untame) next.remove(pkg) else next.add(pkg)
                            config.setTameVendorPackages(next.joinToString(" "))
                            snapInvalidate()
                            scope.launch { if (untame) tame.untame(pkg) else tame.tame(pkg) }
                        }
                        val verb = if (untame) "re-enabling" else "taming"
                        call.respondText(
                            "<!doctype html><meta charset=utf-8><meta http-equiv=refresh content='2;url=/configure'>" +
                                "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
                                "$verb ${esc(pkg)}…</body>",
                            ContentType.Text.Html,
                        )
                    }
                    // The "Find a package…" picker pop-up content: an on-demand, grouped list of packages a
                    // non-expert might want to control — Recommended (profile) / Other apps / Using the most
                    // CPU. Lazy (only built when the dialog opens) and excludes what's already tamed (the card).
                    get("/tame/suggest") {
                        PerfReader.touch()   // keep the CPU sampler warm so the "most CPU" group can populate
                        val groups = runCatching {
                            tame.suggestionGroups(tameProfileCandidates, config.tameVendorPackages.toSet(), PerfReader.topNames())
                        }.getOrDefault(emptyList())
                        val frag = if (groups.isEmpty())
                            """<p class="note">No other packages found — you can still tame one by name.</p>"""
                        else groups.joinToString("\n") { g ->
                            val items = if (g.items.isEmpty())
                                """<p class="note" style="margin:0 0 4px;color:#666">— none —</p>"""
                            else g.items.joinToString("\n") { tameRowHtml(it) }
                            """<h4 style="margin:14px 0 1px">${esc(g.title)}</h4>""" +
                                """<p class="note" style="margin:0 0 4px">${esc(g.hint)}</p>$items"""
                        }
                        // One-click "Tame all recommended", shown only when there's an active recommended pick.
                        val hasRec = groups.any { g -> g.items.any { it.recommended && !it.blocked && !it.disabled && it.installed } }
                        val recBtn = if (hasRec)
                            """<form method="post" action="/api/v1/tame" style="margin:0 0 12px"><input type="hidden" name="action" value="recommended"><button type="submit" style="background:#2e6b3f;border-color:#2e6b3f">✓ Tame all recommended</button> <span class="note" style="font-size:.8em">the badged first-picks below, in one click</span></form>"""
                            else ""
                        call.respondText(recBtn + frag, ContentType.Text.Html)
                    }
                    post("/display/density") {
                        val p = call.receiveParameters()
                        val action = p["action"]                          // "reset" | "rec" (buttons)
                        val d = p["density"]?.trim()?.toIntOrNull()       // custom density (Apply)
                        val f = p["font"]?.trim()?.toFloatOrNull()        // custom font scale (Apply)
                        val ok = when (action) {
                            "reset" -> density.reset() or density.resetFontScale()
                            "rec" -> (recommendedDensity?.let { density.set(it) } ?: false) or
                                (recommendedFontScale?.let { density.setFontScale(it) } ?: false)
                            else -> {  // Apply: set whichever fields were provided
                                (d?.let { density.set(it) } ?: false) or
                                    (f?.let { density.setFontScale(it) } ?: false)
                            }
                        }
                        snapInvalidate()
                        call.respondText(
                            "<!doctype html><meta charset=utf-8><meta http-equiv=refresh content='1;url=/configure'>" +
                                "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
                                (if (ok) "display density applied" else "density unchanged") + "…</body>",
                            ContentType.Text.Html,
                        )
                    }
                    // 1-click WebView DevTools: expose the dashboard's CDP socket to the LAN (root relay)
                    // so the user can chrome://inspect with no adb. See CdpRelay.
                    get("/inspect") {
                        call.respondText(inspectJson(if (CdpRelay.running) "started" else "off"), ContentType.Application.Json)
                    }
                    post("/inspect/start") {
                        call.respondText(inspectJson(CdpRelay.start(appContext)), ContentType.Application.Json)
                    }
                    post("/inspect/stop") {
                        CdpRelay.stop()
                        call.respondText(inspectJson("off"), ContentType.Application.Json)
                    }
                    post("/play") { handlePlay(call) }
                }
            }
        }
        // Guard the bind: a double am-start can race two instances onto :httpPort; a BindException
        // here must not crash the foreground service (START_STICKY would just relaunch into the same).
        try {
            server.start(wait = false)
            stopServer = { server.stop(500, 1500) }
            Log.i(TAG, "HTTP listening on :${config.httpPort}")
        } catch (e: Exception) {
            Log.e(TAG, "HTTP bind on :${config.httpPort} failed (already running?) — continuing", e)
        }
        // Pre-warm the probe snapshot so even the first dashboard visit usually renders complete.
        scope.launch(Dispatchers.IO) { runCatching { snapCache.get() } }
    }

    fun stop() {
        stopServer?.invoke()
        stopServer = null
    }

    // The panel's physical resolution as a CSS aspect-ratio (e.g. "750/1334") so the Screenshot card can
    // reserve the exact box and not reflow when the image arrives. Sane portrait fallback if unavailable.
    private fun screenAspectRatio(): String = try {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        if (dm.widthPixels > 0 && dm.heightPixels > 0) "${dm.widthPixels}/${dm.heightPixels}" else "3/4"
    } catch (e: Throwable) { "3/4" }

    /** Shared TTS/announce handler — served at both `/play` (external contract) and `/api/v1/play`. */
    private suspend fun handlePlay(call: ApplicationCall) {
        val body = call.receiveText()
        val url = urlRegex.find(body)?.value
        if (url == null) {
            call.respondText("no-url\n", status = HttpStatusCode.BadRequest)
            return
        }
        // Respond immediately; playback runs detached (mirrors socat + setsid nohup).
        call.respondText("playing\n")
        scope.launch { AudioPlayer.play(cacheDir, url) }
    }

    /** 308 for a legacy flat path — preserves method, body and query so pre-0.8.5 tooling keeps working. */
    private suspend fun legacy(call: ApplicationCall, new: String) {
        val q = call.request.uri.substringAfter('?', "")
        val loc = if (q.isEmpty()) new else "$new?$q"
        call.response.headers.append("Location", loc)
        call.respondText("moved-permanently: $loc\n", status = HttpStatusCode.PermanentRedirect)
    }

    /** Every pre-0.8.5 flat machine endpoint → its /api/v1 home. GET+POST both registered — 308
     *  preserves the method, so the right verb reaches the real handler either way. */
    private fun io.ktor.server.routing.Route.legacyRedirects() {
        val map = mapOf(
            "/perf" to "/api/v1/perf",
            "/instrumentation" to "/api/v1/instrumentation",
            "/action" to "/api/v1/action",
            "/diag" to "/api/v1/diag",
            "/sensortrace" to "/api/v1/sensortrace",
            "/screenshot.png" to "/api/v1/screenshot.png",
            "/openapi.json" to "/api/v1/openapi.json",
            "/proximity" to "/api/v1/proximity",
            "/proximity/capture" to "/api/v1/proximity/capture",
            "/proximity/threshold" to "/api/v1/proximity/threshold",
            "/proximity/sensitivity" to "/api/v1/proximity/sensitivity",
            "/proximity/reset" to "/api/v1/proximity/reset",
            "/config" to "/api/v1/config",
            "/tame" to "/api/v1/tame",
            "/tame/suggest" to "/api/v1/tame/suggest",
            "/density" to "/api/v1/display/density",
            "/inspect" to "/api/v1/inspect",
            "/inspect/start" to "/api/v1/inspect/start",
            "/inspect/stop" to "/api/v1/inspect/stop",
        )
        for ((old, new) in map) {
            get(old) { legacy(call, new) }
            post(old) { legacy(call, new) }
        }
    }

    // ---- live log stream (SSE) ----

    /** Tail a [LogCapture] to the client as Server-Sent Events. Backlog first (ring snapshot while
     *  capture is already running for the shipper / another viewer, else a one-shot `logcat -d`
     *  dump), then live lines. A per-connection drop-oldest channel means a stalled browser can
     *  never back-pressure the capture; a 15s `: ping` comment detects dead peers so the
     *  subscription (and with it the logcat subprocess) is released. */
    private suspend fun handleLogStream(call: ApplicationCall) {
        val cap = when (val src = call.request.queryParameters["source"] ?: "app") {
            "app" -> logApp
            "system" -> if (suCache.get()) logSystem else {
                call.respondText("system log needs root\n", status = HttpStatusCode.ServiceUnavailable)
                return
            }
            else -> {
                call.respondText("unknown source '$src' (app|system)\n", status = HttpStatusCode.BadRequest)
                return
            }
        }
        if (cap == null) {
            call.respondText("log viewer unavailable\n", status = HttpStatusCode.NotFound)
            return
        }
        // Backlog BEFORE subscribing: a few ms of lines can fall in the gap, which beats the visible
        // duplicates the opposite order produces (the dump overlaps the live stream's first lines).
        val backlog = withContext(Dispatchers.IO) { cap.snapshot().ifEmpty { cap.dump() } }
        val chan = Channel<String>(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val sub = cap.subscribe { chan.trySend(it) }
        try {
            call.response.headers.append("Cache-Control", "no-cache")
            call.respondTextWriter(ContentType.Text.EventStream) {
                for (line in backlog) write("data: $line\n\n")
                flush()
                while (true) {
                    val line = withTimeoutOrNull(15_000) { chan.receive() }
                    write(if (line == null) ": ping\n\n" else "data: $line\n\n")
                    flush()
                }
            }
        } finally {
            runCatching { sub.close() }
            chan.close()
        }
    }

    // ---- tabbed multi-page shell ----

    /** The shared tab bar; [active] highlights the current page. */
    private fun navBar(active: String): String {
        fun tab(id: String, href: String, label: String): String =
            """<a href="$href"${if (id == active) " class=\"active\"" else ""}>$label</a>"""
        return "<div class=\"nav\">" +
            tab("dashboard", "/", "Dashboard") +
            tab("configure", "/configure", "Configure") +
            tab("test", "/test", "Test") +
            tab("install", "/install", "Install") +
            tab("fleet", "/fleet", "Fleet") +
            tab("logs", "/logs", "Logs") +
            """<a href="/api">API</a></div>"""
    }

    /** Shared page shell (header + tab bar + body) for the non-dashboard tabs. */
    private fun page(active: String, title: String, body: String): String {
        val haLink = if (config.haDeviceUrl.isNotBlank())
            """<a class="pbtn" href="${esc(config.haDeviceUrl)}" target="_blank" rel="noopener">Open in HA</a>""" else ""
        return """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ha-paneld · ${esc(title)} · ${esc(config.panelId)}</title>
<link rel="icon" href="/icon.svg">
<link rel="stylesheet" href="/info.css"></head><body data-build="${buildToken()}"><div class="wrap">
<div class="hdr"><h1><img src="/icon.svg" class="logo" alt="">ha-paneld <small>· ${esc(config.panelId)}</small></h1>
 <span style="display:flex;gap:10px;align-items:center">$haLink</span></div>
${navBar(active)}
<div id="verbar" class="setup" style="display:none">⟳ A newer ha-paneld is installed — <a href="#" onclick="location.reload();return false">reload</a> to refresh this page.</div>
$body
<script src="/assets/buildwatch.js"></script>
</div></body></html>"""
    }

    /** Configure tab — schema-driven form (Basic/Advanced + inline expose pips) + bundle backup/restore. */
    private fun configureBody(): String = """
<div class="cfg-tabs"><button id="tab-basic" onclick="cfgTab(false)">Basic</button><button id="tab-adv" class="on" onclick="cfgTab(true)">Advanced</button></div>
<div id="cfg-status" class="muted" style="margin-bottom:10px">Loading settings…</div>
<div id="cfg-groups" class="cards"></div>
<div class="savebar"><button id="savebtn" disabled onclick="cfgSave()">Save changes</button><span id="cfg-msg" class="muted"></span></div>
<div class="cards">
${proximityCardHtml()}
${displayCardHtml()}
${tameCardHtml()}
<div class="card"><h2>Backup &amp; restore</h2>
<p class="note">Download this panel's configuration as a versioned bundle, or apply one — validated, all-or-nothing, with a change preview before it writes.</p>
<div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">
 <a class="pbtn" href="/api/v1/config/export">⭳ Export bundle</a>
 <a class="pbtn" href="/api/v1/config/export?include_secrets=1">⭳ Export incl. secrets</a>
 <label class="pbtn" style="cursor:pointer">⭱ Import…<input type="file" id="impfile" accept="application/json" style="display:none" onchange="cfgImport(this)"></label>
</div>
<pre id="imp-result" class="muted" style="white-space:pre-wrap;margin-top:10px"></pre></div></div>
<script src="/assets/configure.js"></script>
<script src="/assets/prox.js"></script>"""

    /** Test tab — interactive screenshot (View/Control), on-screen nav actions, TTS test. */
    private fun testBody(): String {
        val rootOk = rootOk()
        val cap = if (rootOk) "Captured on demand via root / the helper daemon."
        else "Screenshot + tap control need root or the helper daemon — unavailable on this panel."
        return """
<div class="cards">
<div class="card"><h2>Panel screen <small>· live</small></h2>
<div class="modebar">
 <span class="seg"><button id="m-view" class="on" onclick="tstMode('view')">👁 View</button><button id="m-ctl" onclick="tstMode('control')">✋ Control</button></span>
 <button class="pbtn" onclick="tstRefresh()">↻ Refresh</button>
</div>
<div id="shotwrap" class="screen-wrap"><img id="shot" alt="panel screen" style="aspect-ratio:${screenAspectRatio()}"></div>
<p class="note" id="ctl-hint" style="display:none">Control mode: click/tap the image to send a real touch to the panel. ~0.5–1.5s round trip — good for waking, navigating, dismissing dialogs; not smooth dragging.</p>
<p class="note">$cap</p></div>

<div class="card"><h2>On-screen actions</h2>
<div style="display:flex;gap:8px;flex-wrap:wrap">
 <button class="pbtn" onclick="tstAction('back')">← Back</button>
 <button class="pbtn" onclick="tstAction('recents')">▢ Recents</button>
 <button class="pbtn" onclick="tstAction('launcher')">⊞ Launcher</button>
 <button class="pbtn" onclick="tstAction('admin_launcher')">⚙ Admin</button>
 <button class="pbtn" onclick="tstAction('voldn')">Vol −</button>
 <button class="pbtn" onclick="tstAction('volup')">Vol +</button>
</div>
<p class="note">Drive the panel without touching it — handy while watching the screenshot above.</p></div>

<div class="card"><h2>TTS / audio test</h2>
<div style="display:flex;gap:8px;flex-wrap:wrap">
 <input id="tts-url" placeholder="https://…/clip.mp3" style="flex:1;min-width:200px">
 <button class="pbtn" onclick="tstPlay()">▶ Play</button>
</div>
<p class="note">Posts a URL to the announce contract (<code>/play</code>). <span id="tts-msg" class="muted"></span></p></div>

<div class="card"><h2>More</h2>
<p class="note">Proximity calibration is on the <a href="/" style="color:#9cf">Dashboard</a>. Full REST: <a href="/api" style="color:#9cf">API explorer</a>.</p></div>
</div>
<script src="/assets/test.js"></script>"""
    }

    /** Install tab — setup health warnings, capabilities, and config backup. */
    private fun installBody(): String {
        // Engine-aware WebView age check (a Cromite swap reports the stale OEM package version).
        val webViewStatus = PanelInfo.webViewStatus(appContext)
        val webViewVal = webViewStatus.display
        val tooOld = webViewStatus.tooOld
        val renderers = PanelInfo.dashboardRenderers(appContext, config.dashboardPackage)
        val capColor = mapOf("ok" to "#48c774", "degraded" to "#d9a528", "none" to "#d04a3b")
        val caps = DiagReader.capabilities(appContext).joinToString("\n") { c ->
            val col = capColor[c.status] ?: "#888"
            """<tr><th>${esc(c.name)}</th><td><span style="color:$col">●</span> ${esc(c.note)}</td></tr>"""
        }
        // Auto-heal offer: if the profile ships a known-good WebView and we have root/daemon to install it,
        // the too-old warning gets a one-tap "Update WebView now" button (POST /api/v1/webview/heal).
        val canHeal = tooOld && io.github.maxlyth.hapaneld.device.DeviceProfile.detect().recommendedWebView != null && rootOk()
        val warnings = buildString {
            if (io.github.maxlyth.hapaneld.PanelStatus.dashboardCrashLooping) append(
                """<div class="setup">⛔ <b>Dashboard app is crash-looping</b> — the watchdog stopped relaunching it """ +
                    """to avoid a restart storm. This usually means an incompatible dashboard-app update; reinstall or """ +
                    """downgrade the dashboard/Companion app (see <a href="/install">updates</a>), or reboot the panel.</div>""",
            )
            if (tooOld) append(
                """<div class="setup">⚠ <b>System WebView is too old</b> (${esc(webViewVal)}) — the Home Assistant """ +
                    """dashboard may render blank or broken. <a href="$WEBVIEW_DOC" target="_blank" rel="noopener">""" +
                    """How &amp; why to update</a> (target: Chromium ${PanelHealth.MIN_CHROMIUM}+).""" +
                    (if (canHeal) """<div style="margin-top:10px"><button class="pbtn" onclick="healWebView(this)">⬇ Update WebView now</button> <span id="wv-heal" class="muted"></span></div>""" else "") +
                    """</div>""",
            )
            if (renderers.isEmpty()) append(
                """<div class="setup">ℹ <b>No dashboard app detected</b> — install the HA Companion app, """ +
                    """<a href="https://www.fully-kiosk.com/" target="_blank" rel="noopener">Fully Kiosk</a>, or set a """ +
                    """dashboard package on <a href="/configure">Configure</a>.</div>""",
            )
            // Companion server row with a blank internal_url → HA 2026.7 rejects with "Missing Host header"
            // and the dashboard renders blank. Offer a one-tap repair (copy external_url into internal_url).
            companionUrlCache.get().let { u ->
                if (u.needsRepair) append(
                    """<div class="setup">⚠ <b>Home Assistant Companion has no internal URL</b> """ +
                        """(${u.affected} server${if (u.affected == 1) "" else "s"}) — the dashboard can fail to load """ +
                        """with <i>"Missing 'Host' header"</i>. Repair sets the internal URL to the external URL.""" +
                        """<div style="margin-top:10px"><button class="pbtn" onclick="repairCompUrl(this)">⚙ Repair internal URL</button> <span id="cu-fix" class="muted"></span></div>""" +
                        """</div>""",
                )
            }
            UpdateChecker.available.forEach { u ->
                append(
                    """<div class="setup">⬆ <b>${esc(u.label)}</b> ${esc(u.latestVersion)} is available """ +
                        """(installed: ${esc(u.currentVersion)}) — <a href="${esc(u.releaseUrl)}" target="_blank" rel="noopener">download</a></div>""",
                )
            }
        }
        val allGood = if (warnings.isEmpty()) """<div class="card"><p class="note">✓ No setup problems detected — this panel looks ready.</p></div>""" else ""
        return """$warnings
<div class="cards">
<div class="card"><h2>Capabilities</h2><table>
$caps
</table>
<p class="note"><a href="/api/v1/diag" target="_blank" style="color:#9cf">⭳ Diagnostics dump</a> — full hardware/firmware/SELinux/su report for bug reports.</p></div>
<div class="card"><h2>Backup this panel</h2>
<p class="note">Export the full configuration bundle for safe-keeping, or to clone settings to another panel (Configure → Import).</p>
<a class="pbtn" href="/api/v1/config/export">⭳ Export config bundle</a></div>
$allGood</div>
<script>
function healWebView(btn){btn.disabled=true;var s=document.getElementById('wv-heal');
 if(s)s.textContent='Downloading + installing… this takes a minute.';
 fetch('/api/v1/webview/heal',{method:'POST'}).then(function(r){return r.json();}).then(function(){
  if(s)s.textContent='Installing WebView — reload the dashboard, then refresh this page to confirm the new version.';
 }).catch(function(){if(s)s.textContent='Failed to start — check root/daemon.';btn.disabled=false;});}
function repairCompUrl(btn){btn.disabled=true;var s=document.getElementById('cu-fix');
 if(s)s.textContent='Repairing + relaunching the Companion…';
 fetch('/api/v1/companion/repair-url',{method:'POST'}).then(function(r){return r.json();}).then(function(){
  if(s)s.textContent='Repair started — the Companion will relaunch; refresh this page in a few seconds to confirm.';
 }).catch(function(){if(s)s.textContent='Failed to start — check root.';btn.disabled=false;});}
</script>"""
    }

    /** Logs tab — live log tail over SSE. App source always; system source needs root (gated live). */
    private fun logsBody(): String {
        val sysOk = suCache.get()
        val sysBtn = if (sysOk) """<button id="lg-src-system" onclick="lgSource('system')">System</button>"""
        else """<button id="lg-src-system" disabled title="Full system logcat needs root — unavailable on this panel">System</button>"""
        // Deliberately NOT inside a `.cards` masonry container — the log card wants the full page width.
        return """
<div class="card"><h2>Live logs <small id="lg-state" class="muted">· connecting…</small></h2>
<div class="modebar" style="flex-wrap:wrap;gap:8px">
 <span class="seg"><button id="lg-src-app" class="on" onclick="lgSource('app')">App</button>$sysBtn</span>
 <select id="lg-level" onchange="lgRender()" title="Minimum level">
  <option value="V" selected>Verbose+</option><option value="D">Debug+</option><option value="I">Info+</option>
  <option value="W">Warning+</option><option value="E">Error+</option>
 </select>
 <input id="lg-filter" placeholder="filter text…" oninput="lgRender()" style="flex:1;min-width:120px">
 <button id="lg-pause" class="pbtn" onclick="lgPause()">⏸ Pause</button>
 <button class="pbtn" onclick="lgClear()">Clear</button>
 <label class="muted" style="display:flex;align-items:center;gap:4px"><input type="checkbox" id="lg-follow" checked> Follow</label>
</div>
<div id="lg-out" class="logview" onscroll="lgScrolled()"></div>
<p class="note">App = ha-paneld's own process log (no root needed). System = the full device logcat (root).
Tokens/passwords are redacted before display; the stream is LAN-only and stops when this page closes.
Raw stream: <code>curl -N http://&lt;panel&gt;:${config.httpPort}/api/v1/logs/stream</code></p></div>
<script src="/assets/logs.js"></script>"""
    }

    /** Fleet tab — placeholder (discovery hooks exist; the roster lands later). */
    private fun fleetBody(): String = """
<div class="cards"><div class="card"><h2>Fleet overview <small>· coming soon</small></h2>
<p class="note">A multi-panel roster — name · IP · health, with a link to each panel's UI — will live here.
Every ha-paneld already advertises itself over mDNS (<code>${esc(Config.MDNS_SERVICE_TYPE)}</code>) and
publishes MQTT availability, so the discovery hooks are in place.</p>
<p class="note">For now, open another panel directly at <code>http://&lt;its-ip&gt;:${config.httpPort}/</code>.</p></div></div>"""

    /** Health + capabilities as JSON for the variant UIs. Warnings are ready-to-render HTML fragments. */
    private fun statusJson(): String {
        // Engine-aware WebView age check (a Cromite swap reports the stale OEM package version).
        val webViewStatus = PanelInfo.webViewStatus(appContext)
        val warns = mutableListOf<String>()
        if (io.github.maxlyth.hapaneld.PanelStatus.dashboardCrashLooping) warns.add(
            "⛔ <b>Dashboard app is crash-looping</b> — the watchdog stopped relaunching it to avoid a restart storm. " +
                "Reinstall or downgrade the dashboard/Companion app, or reboot the panel.",
        )
        if (webViewStatus.tooOld) warns.add(
            "⚠ <b>System WebView is too old</b> (${esc(webViewStatus.display)}) — the Home Assistant dashboard may render blank. " +
                "<a href=\"$WEBVIEW_DOC\" target=\"_blank\" rel=\"noopener\">How &amp; why to update</a> (target Chromium ${PanelHealth.MIN_CHROMIUM}+).",
        )
        if (PanelInfo.dashboardRenderers(appContext, config.dashboardPackage).isEmpty()) warns.add(
            "ℹ <b>No dashboard app detected</b> — install the HA Companion, Fully Kiosk, or set a dashboard package.",
        )
        companionUrlCache.get().let { u ->
            if (u.needsRepair) warns.add(
                "⚠ <b>Home Assistant Companion has no internal URL</b> (${u.affected} server${if (u.affected == 1) "" else "s"}) — " +
                    "the dashboard can fail with \"Missing 'Host' header\". Repair it on the Install tab.",
            )
        }
        UpdateChecker.available.forEach { u ->
            warns.add("⬆ <b>${esc(u.label)}</b> ${esc(u.latestVersion)} is available (installed ${esc(u.currentVersion)}) — " +
                "<a href=\"${esc(u.releaseUrl)}\" target=\"_blank\" rel=\"noopener\">download</a>")
        }
        val capColor = mapOf("ok" to "#48c774", "degraded" to "#d9a528", "none" to "#d04a3b")
        val caps = DiagReader.capabilities(appContext).joinToString(",") { c ->
            "{\"name\":${jsonStr(c.name)},\"note\":${jsonStr(c.note)},\"color\":${jsonStr(capColor[c.status] ?: "#888")}}"
        }
        return "{\"warnings\":[${warns.joinToString(",") { jsonStr(it) }}],\"capabilities\":[$caps]}"
    }


    /** Proximity tuning card — lives on the Configure tab; driven by /assets/prox.js. */
    private fun proximityCardHtml(): String = """<div class="card" id="cfg-proximity"><h2>Proximity tuning <small id="proxstate"></small></h2>
<div id="proxbox" style="display:none">
<canvas id="proxgauge" width="600" height="46" class="gradedonly" style="height:46px"></canvas>
<div style="font-size:.85rem;margin-bottom:8px">raw <b id="proxraw" style="color:#4a9eff">–</b>
 <span id="proxthwrap" class="gradedonly">· threshold <b id="proxth">–</b></span> · state <b id="proxnear">–</b></div>
<div class="gradedonly" style="display:flex;gap:6px;flex-wrap:wrap;align-items:center;font-size:.85rem;margin-bottom:8px">
 <span style="color:#9af">Capture:</span>
 <button type="button" class="pbtn" onclick="proxCap('near')">Near</button>
 <button type="button" class="pbtn" onclick="proxCap('far')">Far</button>
 <span class="gradedonly" style="color:#9af;margin-left:10px">Sensitivity:</span>
 <button type="button" class="pbtn psen gradedonly" data-s="HIGH" onclick="proxSen('HIGH')">High</button>
 <button type="button" class="pbtn psen gradedonly" data-s="MEDIUM" onclick="proxSen('MEDIUM')">Med</button>
 <button type="button" class="pbtn psen gradedonly" data-s="LOW" onclick="proxSen('LOW')">Low</button>
 <button type="button" class="pbtn" style="margin-left:10px" onclick="proxReset()">Reset</button></div>
<label class="gradedonly" style="font-size:.8rem;color:#9af">Threshold fine-tune
 <input type="range" id="proxslider" min="0" max="100" step="0.1" style="width:100%"
  oninput="document.getElementById('proxth').textContent=(+this.value).toFixed(1)"
  onchange="proxThSet(this.value)"></label>
<p id="proxhint" class="note"></p>
</div></div>"""

    /** The pencil that marks a value as CONFIGURABLE (vs a static fact) and deep-links to the exact
     *  setting/card on the Configure tab (`/configure#<anchor>` scrolls + flashes it). */
    private fun cfgIcon(anchor: String): String =
        """ <a class="cfglink" href="/configure#$anchor" title="Edit on the Configure tab" aria-label="Edit">✎</a>"""

    /** What the "auto" (blank) package settings actually resolved to — shown as `auto (pkg)` in the
     *  dashboard rows and as the Configure-field placeholder, so "auto" is never a mystery. When no
     *  launcher app resolves, the Launcher key falls back to ha-paneld's own admin launcher (see
     *  SystemController.launchLauncher) — say so instead of leaving a "—" that reads like a dead key. */
    private fun autoHints(): Map<String, String> = buildMap {
        system.resolveDashboard("").takeIf { it.isNotBlank() }?.let { put("dashboard_package", it) }
        put("launcher_package", system.resolvedLauncher("") ?: "ha-paneld admin launcher")
        // Unset home_dashboard = reload/boot land on whatever HA's frontend picks (the device-level
        // "set as default on this device" if ever used, else the HA user's profile default). The
        // Companion has no config of its own for this, so there's nothing more specific to read.
        put("home_dashboard", "HA default view")
    }

    /** One read-only dashboard row for a registry setting: label → current value + the edit pencil.
     *  Null when the setting doesn't exist on this panel (capability-gated). */
    private fun settingRowHtml(key: String, live: Map<String, String>, caps: Capabilities, hints: Map<String, String> = emptyMap()): String? {
        val spec = SettingsRegistry.spec(key) ?: return null
        if (!spec.availableWhen(caps)) return null
        val raw = effectiveValue(spec, live)
        val shown = when {
            spec.secret -> if (raw.isNotEmpty()) "set" else "—"
            spec.type == SettingType.BOOL -> if (raw.toBoolean()) "on" else "off"
            raw.isBlank() -> hints[key]?.let { "auto ($it)" } ?: "—"
            else -> raw
        }
        return """<tr><th>${esc(spec.label)}</th><td>${esc(shown)}${cfgIcon("cfg-$key")}</td></tr>"""
    }

    // ---- dashboard snapshot (probe results) + hydration ---------------------------------------------
    //
    // Rendering `/` used to gather every root/probe value inline — ~8 serialized su round-trips
    // (zigbee status, CPU tier, network-ADB ×2, touch sound, `wm density` ×3, su presence), a 12+s
    // blank page on PX30 panels. The probes now funnel through ONE cached snapshot: `/` renders
    // whatever is last known instantly (placeholders on a cold start) and the page hydrates from
    // GET /api/v1/info, which is the only place the probes actually run — at most once per TTL,
    // single-flight, pre-warmed at server start.

    /** Everything the dashboard shows that costs a root/probe round-trip, gathered once. */
    private class Snap(
        val facts: Map<String, String>,
        val live: Map<String, String>,
        val caps: Capabilities,
        val densityCur: Int?,
        val densityNat: Int?,
        val fontScale: Float,
        val rootOk: Boolean,
    )

    // The density trio is shared with the Configure tab's Display card (the bulk of ITS slow render).
    private val densityCache = Cached(DENSITY_TTL_MS) { Triple(density.current(), density.native(), density.fontScale()) }
    // su presence doesn't flap — cache the probe gating screenshot / controls / system logs / taming.
    private val suCache = Cached(SU_TTL_MS) { Su.available() }
    private fun rootOk(): Boolean = suCache.get() || HelperClient.available()

    // Companion internal_url health (root sqlite3 read). Cached so the polled /status endpoint doesn't
    // spawn su per request; only checked when su is present (the DB is app-private). Invalidated on repair.
    private val companionUrlCache = Cached(COMPANION_URL_TTL_MS) {
        if (suCache.get()) CompanionDb.internalUrlStatus(appContext, Su) else CompanionDb.UrlStatus(false, 0)
    }

    private val snapCache = Cached(SNAP_TTL_MS) {
        val d = densityCache.get()
        Snap(
            facts = info(),
            live = liveValues(),
            caps = capabilities(),
            densityCur = d.first, densityNat = d.second, fontScale = d.third,
            rootOk = rootOk(),
        )
    }

    /** Call after any write that changes probed state (config apply/import/restore, density, tame),
     *  so the next render doesn't show pre-write values for a TTL. */
    private fun snapInvalidate() {
        snapCache.invalidate()
        densityCache.invalidate()
    }

    /** Last-known snapshot with a background refresh when stale — never blocks once built, so the
     *  Configure endpoints (form values, schema capabilities, Display card) render instantly like
     *  the dashboard. Blocks only before the start-up pre-warm has ever completed. */
    private fun snapStaleOk(): Snap {
        val p = snapCache.peek() ?: return snapCache.get()
        if (snapCache.ageMs() > SNAP_TTL_MS) scope.launch(Dispatchers.IO) { runCatching { snapCache.get() } }
        return p
    }

    private val NET_KEYS = listOf("Local IP", "Local IPv6", "HTTP port", "MQTT", "mDNS", "Network ADB")
    // Rows whose values are DECLARED by the DeviceProfile, so wrong data points a contributor straight
    // at the fix: Platform=displayName/socClass, LED=ledMechanism, sensor tech=proximityTech/lightTech,
    // Zigbee=zigbeeGatewayDir, Relays=relayBase, CPU profile=cpuGovernors.
    private val PROF_KEYS = listOf("Platform", "LED", "Light sensor", "Proximity", "Zigbee", "Relays", "CPU profile")
    private fun infoKeys(s: Snap): List<String> = s.facts.keys.filter { it !in NET_KEYS && it !in PROF_KEYS }

    /** The setup / health / update banners — everything above the cards. Needs the facts map (MQTT
     *  state), so on a cold start it hydrates with the rest. */
    private fun bannersHtml(s: Snap): String {
        val mqtt = s.facts["MQTT"] ?: "disabled"
        // Pure decision (unit-tested in SetupBannerTest) — note a CONFIGURED broker that's merely
        // mid-(re)connect must not be reported as missing.
        val needs = SetupBanner.needs(mqtt, config.mqttBroker.isNotBlank(), config.panelIdIsDefault)
        val setup = if (needs.isNotEmpty())
            """<div class="setup">⚠ This panel needs <a href="/configure">${needs.joinToString(" and ")}</a> — set on the Configure tab.</div>"""
        else ""
        val update = UpdateChecker.available.joinToString("") { u ->
            """<div class="setup">⬆ <b>${esc(u.label)}</b> ${esc(u.latestVersion)} is available""" +
                """ (installed: ${esc(u.currentVersion)}) — """ +
                """<a href="${esc(u.releaseUrl)}" target="_blank" rel="noopener">download</a></div>"""
        }
        // Panel-health warnings: states that stop the panel rendering the dashboard as expected but that
        // the info map otherwise reports neutrally. Soft + best-effort — ha-paneld runs fine regardless.
        // Verdict from the REAL engine version (WebView UA), not the stamped package version — the
        // engine fetch is cached, so this call is cheap. See PanelInfo.webViewStatus.
        val health = buildString {
            if (PanelInfo.webViewStatus(appContext).tooOld) append(
                """<div class="setup">⚠ <b>System WebView is too old</b> (${esc(s.facts["System WebView"] ?: "")}) — the Home Assistant """ +
                    """dashboard may render blank or broken. <a href="$WEBVIEW_DOC" target="_blank" rel="noopener">""" +
                    """How &amp; why to update</a> (target: Chromium ${PanelHealth.MIN_CHROMIUM}+). """ +
                    """<small>Checked against the real engine version (from the WebView UA), not just the """ +
                    """stamped package version — so a Cromite/LineageOS SystemWebView won't trip this.</small></div>"""
            )
            if (PanelInfo.dashboardRenderers(appContext, config.dashboardPackage).isEmpty()) append(
                """<div class="setup">ℹ <b>No dashboard app detected</b> — install the HA Companion app, """ +
                    """<a href="https://www.fully-kiosk.com/" target="_blank" rel="noopener">Fully Kiosk</a>, or set a """ +
                    """dashboard package on <a href="/configure">Configure</a>, or this panel won't display a dashboard. """ +
                    """<small>(ha-paneld itself runs fine without one.)</small></div>"""
            )
        }
        return update + health + setup
    }

    /** Table rows for one facts card (Panel information / Networking / ha-paneld profile). */
    private fun factRowsHtml(s: Snap, keys: List<String>): String {
        val webViewTooOld = PanelInfo.webViewStatus(appContext).tooOld
        return keys.filter { s.facts.containsKey(it) }.joinToString("\n") { k ->
            val v = s.facts.getValue(k)
            // Version: plain text + a small "open releases" icon (a hyperlinked version reads ugly).
            val cell = if (k == "ha-paneld") {
                """${esc(v)} <a class="ext" href="$RELEASES_URL" target="_blank" rel="noopener" """ +
                    """title="Releases" aria-label="Releases"><svg viewBox="0 0 24 24"><path d="$EXT_ICON"/></svg></a>"""
            } else if (k == "Display") {
                displayCell(v)
            } else if (k == "System WebView" && webViewTooOld) {
                """<span style="color:#f5c451">${esc(v)} ⚠</span>"""
            } else if (k in SECRET_FIELDS || (k in ADDRESS_FIELDS && isRoutable(v))) {
                // Blurred by default so a casual screenshot doesn't leak it; "Reveal" un-blurs (screenshot
                // hygiene, not access control — the value is still in the page source).
                """<span class="secret">${esc(v)}</span>"""
            } else {
                esc(v)
            }
            // Facts backed by a setting get the ✎ marker (configurable vs static at a glance),
            // deep-linking to the exact row on the Configure tab.
            val edit = FACT_CFG[k]?.let { cfgIcon(it) } ?: ""
            "<tr><th>${esc(k)}</th><td>$cell$edit</td></tr>"
        }
    }

    // Live control states (what HA's control entities currently show) — controls, not config.
    private fun liveRowsHtml(): String {
        val led = config.lastLed.split(",").mapNotNull { it.toIntOrNull() }
        val ledShown = if (led.size == 5 && led[0] == 1) "on · rgb(${led[2]},${led[3]},${led[4]}) @ ${led[1]}" else "off"
        val brightnessShown = effectiveBrightness().takeIf { it >= 0 }?.toString() ?: runCatching {
            android.provider.Settings.System.getInt(appContext.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()?.toString() ?: "?"
        return listOf(
            "Screen brightness" to brightnessShown,
            "Volume" to "${volume.getPercent()}%",
            "Navigate" to config.lastNavigate.ifEmpty { "/" },
            "LED" to ledShown,
        ).joinToString("\n") { (k, v) -> """<tr><th>${esc(k)}</th><td>${esc(v)}</td></tr>""" }
    }

    private fun behaviourRowsHtml(s: Snap): String = listOf(
        "wake_on_wave", "prevent_idle_dim", "watchdog_enabled", "touch_sound",
        "silence_boot_chime", "keep_awake", "home_dashboard", "dashboard_package", "launcher_package",
    ).let { keys -> val hints = autoHints(); keys.mapNotNull { settingRowHtml(it, s.live, s.caps, hints) } }.joinToString("\n")

    // Display & tuning: registry values plus the Configure-card-backed ones (density / text /
    // proximity / tame), each deep-linking to its card.
    private fun displayRowsHtml(s: Snap): String {
        val proxShown = when {
            !sensors.hasProximity() -> null
            config.proximityCalibrated -> "calibrated · threshold ${"%.1f".format(config.proximityThreshold)}"
            else -> "not calibrated"
        }
        return listOf("auto_brightness", "brightness_bias").mapNotNull { settingRowHtml(it, s.live, s.caps) }
            .joinToString("\n") + "\n" + listOfNotNull(
            s.densityCur?.let { """<tr><th>Density</th><td>$it dpi (native ${s.densityNat ?: "?"})${cfgIcon("cfg-display")}</td></tr>""" },
            s.densityCur?.let { """<tr><th>Text size</th><td>${s.fontScale}${cfgIcon("cfg-display")}</td></tr>""" },
            proxShown?.let { """<tr><th>Proximity</th><td>${esc(it)}${cfgIcon("cfg-proximity")}</td></tr>""" },
            """<tr><th>Tamed packages</th><td>${esc(config.tameVendorPackagesRaw.ifBlank { "none" })}${cfgIcon("cfg-tame")}</td></tr>""",
        ).joinToString("\n")
    }

    private fun updatesRowsHtml(s: Snap): String = listOf("self_update", "update_channel", "companion_auto_update")
        .mapNotNull { settingRowHtml(it, s.live, s.caps) }.joinToString("\n")

    private fun capRowsHtml(): String {
        val capColor = mapOf("ok" to "#48c774", "degraded" to "#d9a528", "none" to "#d04a3b")
        return DiagReader.capabilities(appContext).joinToString("\n") { c ->
            val col = capColor[c.status] ?: "#888"
            """<tr><th>${esc(c.name)}</th><td><span style="color:$col">●</span> ${esc(c.note)}</td></tr>"""
        }
    }

    /** The Controls-card button rows. [s] null (cold shell) → everything disabled as "checking…";
     *  hydration swaps in the capability-gated real state. */
    private fun controlsHtml(s: Snap?): String {
        // Controls buttons: render but DISABLE (not hide, not silently-broken) when the action's capability
        // is missing — back/recents need the a11y service, launcher/reboot need root; volume always works.
        val a11yOk = s?.facts?.get("Nav actions (a11y)") == "yes"
        // Recents is only real where the firmware has an overview screen — KEYCODE_APP_SWITCH no-ops on
        // single-purpose panels (TPA10), so gate the button on the profile rather than show a dead one.
        val hasRecents = io.github.maxlyth.hapaneld.device.DeviceProfile.detect().hasRecents
        val rootOk = s?.rootOk == true
        val checking = s == null
        fun pbtn(action: String, label: String, ok: Boolean, needs: String, style: String = "", disabledTitle: String? = null): String {
            val dis = when {
                checking -> """ disabled title="checking capabilities…""""
                !ok -> """ disabled title="needs $needs""""
                disabledTitle != null -> """ disabled title="$disabledTitle""""
                else -> ""
            }
            return """<button class="pbtn"$style onclick="act('$action')"$dis>$label</button>"""
        }
        // "Launcher" opens the best real home-screen launcher; "Admin launcher" always opens ha-paneld's
        // own. When no separate launcher exists (e.g. the vendor kiosk is tamed), "Launcher" would just
        // fall through to the admin launcher — so DISABLE it rather than show two buttons that do the same
        // thing. resolvedLauncher() is a cheap PackageManager query (no root).
        val hasDistinctLauncher = !checking && system.resolvedLauncher(config.launcherPackage) != null
        return """<div style="display:flex;gap:8px;flex-wrap:wrap">
 ${pbtn("back", "← Back", a11yOk, "the accessibility service")}
 ${pbtn("recents", "▢ Recents", a11yOk && hasRecents, if (hasRecents) "the accessibility service" else "a Recents/overview screen (absent on this panel)")}
 ${pbtn("launcher", "⊞ Launcher", rootOk, "root (su or the helper daemon)", disabledTitle = if (hasDistinctLauncher) null else "No separate launcher on this panel — same as Admin launcher")}
 ${pbtn("admin_launcher", "⚙ Admin launcher", rootOk, "root (su or the helper daemon)")}
</div>
<div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:8px">
 ${pbtn("voldn", "Vol −", !checking, "")}
 ${pbtn("volup", "Vol +", !checking, "")}
 ${pbtn("reboot", "⟳ Reboot", rootOk, "root (su or the helper daemon)", """ style="margin-left:auto;border-color:#7a3a2a;color:#f5a08a"""")}
</div>"""
    }

    /** Hydration payload for the dashboard: ready-to-inject HTML fragments, rendered by the same
     *  functions as the warm server render so the two paths can't drift. Builds the snapshot (this
     *  is where the probe cost actually lands — once per TTL). */
    private fun infoJson(): String {
        val s = snapCache.get()
        val cards = listOf(
            "livetbl" to liveRowsHtml(),
            "behavtbl" to behaviourRowsHtml(s),
            "disptbl" to displayRowsHtml(s),
            "updtbl" to updatesRowsHtml(s),
            "infotbl" to factRowsHtml(s, infoKeys(s)),
            "nettbl" to factRowsHtml(s, NET_KEYS),
            "proftbl" to factRowsHtml(s, PROF_KEYS),
            "captbl" to capRowsHtml(),
        ).joinToString(",") { (k, v) -> "\"$k\":${jsonStr(v)}" }
        return """{"banners":${jsonStr(bannersHtml(s))},"shot":${s.rootOk},"controls":${jsonStr(controlsHtml(s))},"cards":{$cards}}"""
    }

    private fun infoHtml(): String {
        val pid = esc(config.panelId)
        // Stale-while-revalidate: render the last-known snapshot instantly (placeholders if none yet)
        // and let the page hydrate/refresh from /api/v1/info when the snapshot is missing or old.
        val s = snapCache.peek()
        val hydrate = s == null || snapCache.ageMs() > SNAP_TTL_MS
        val placeholder = """<tr><td style="color:#888">reading…</td></tr>"""
        // One facts/value card: cold → placeholder rows (hydration fills or hides); warm → rows, and
        // an EMPTY card is omitted exactly as before.
        fun tcard(id: String, title: String, rows: String?, pre: String = "", post: String = ""): String = when {
            rows == null -> """<div class="card"><h2>${esc(title)}</h2>$pre<table id="$id">$placeholder</table>$post</div>"""
            rows.isBlank() -> ""
            else -> """<div class="card"><h2>${esc(title)}</h2>$pre<table id="$id">$rows</table>$post</div>"""
        }
        val profNote = """<p class="note">Values declared by this panel's <a href="$REPO_URL/blob/main/docs/architecture/device-profiles.md" target="_blank" rel="noopener" style="color:#9cf">device profile</a> — if one looks wrong, that's where to correct it.</p>"""
        val capNote = """<p class="note"><a href="/api/v1/diag" target="_blank" style="color:#9cf">⭳ Diagnostics dump</a> — a copy-paste
report of this panel's hardware, firmware, SELinux, su and node probes for bug reports.</p>"""
        // Screenshot card: needs root, which the cold shell doesn't know yet — render it hidden with
        // the img src deferred (data-src) so a rootless panel never fires a doomed screencap request.
        val shotInner = { src: Boolean ->
            """<a class="shot" href="/api/v1/screenshot.png" target="_blank" rel="noopener" title="Open full size in a new window" style="aspect-ratio:${screenAspectRatio()}"><img ${if (src) "src=\"/api/v1/screenshot.png\"" else "data-src=\"/api/v1/screenshot.png\""} alt="panel screenshot" onload="this.parentElement.classList.add('loaded')" onerror="this.parentElement.classList.add('failed')"></a>
<p class="note"><a href="#" onclick="var s=this.closest('.card').querySelector('.shot');s.classList.remove('loaded','failed');s.querySelector('img').src='/api/v1/screenshot.png?t='+Date.now();return false" style="color:#9cf">↻ Refresh</a> · click the image to open it full size. Captured on demand via root (`screencap`); local-network only.</p>"""
        }
        val shotCard = when {
            s == null -> """<div class="card" id="shotcard" style="display:none"><h2>Screenshot <small>· live panel</small></h2>${shotInner(false)}</div>"""
            s.rootOk -> """<div class="card" id="shotcard"><h2>Screenshot <small>· live panel</small></h2>${shotInner(true)}</div>"""
            else -> ""
        }
        return """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ha-paneld · $pid</title>
<link rel="icon" href="/icon.svg">
<link rel="stylesheet" href="/info.css"></head><body data-ver="${Config.VERSION}" data-build="${buildToken()}" data-hydrate="${if (hydrate) "1" else "0"}"><div class="wrap">
<div class="hdr"><h1><img src="/icon.svg" class="logo" alt="">ha-paneld <small>· $pid</small></h1>
 <span style="display:flex;gap:10px;align-items:center">${if (config.haDeviceUrl.isNotBlank()) """<a class="pbtn" href="${esc(config.haDeviceUrl)}" target="_blank" rel="noopener" title="Open this panel's device page in Home Assistant">Open in HA</a>""" else ""}<button id="revbtn" class="pbtn" onclick="toggleReveal()" title="Show/hide blurred values for editing — they're blurred by default so screenshots don't leak them">Reveal</button>
 <a class="gh" href="$REPO_URL" target="_blank" rel="noopener" title="ha-paneld on GitHub" aria-label="GitHub"><svg viewBox="0 0 24 24"><path d="$GH_ICON"/></svg></a></span></div>
${navBar("dashboard")}
<div id="verbar" class="setup" style="display:none">⟳ A newer ha-paneld is installed — <a href="#" onclick="location.reload();return false">reload</a> to refresh this page.</div>
<div id="bannerzone">${s?.let { bannersHtml(it) } ?: ""}</div>
<div class="cards">
<div class="card"><h2>Controls <small>· software nav bar</small></h2>
<div id="ctlzone">${controlsHtml(s)}</div>
<p class="note">For panels with no physical nav bar. Back/Recents use the accessibility service; Launcher/Reboot need root — actions whose capability is missing are disabled (hover for why).</p></div>
$shotCard
${tcard("infotbl", "Panel information", s?.let { factRowsHtml(it, infoKeys(it)) })}
${tcard("nettbl", "Networking", s?.let { factRowsHtml(it, NET_KEYS) })}
${tcard("proftbl", "ha-paneld profile", s?.let { factRowsHtml(it, PROF_KEYS) }, post = profNote)}
${tcard("captbl", "Capabilities", if (s == null) null else capRowsHtml(), post = capNote)}
<div class="card"><h2>Responsiveness <small id="smhdr"></small></h2>
<canvas id="smchart" width="600" height="130" style="height:130px"></canvas>
<div class="leg">dashboard main-thread CPU (% of one core) ·
 <span style="color:#48c774">▬</span> snappy &lt;50% · <span style="color:#d9a528">▬</span> maxed &gt;85%</div>
<table id="smtbl"><tr><td style="color:#888">measuring…</td></tr></table></div>
<div class="card"><h2>Sensors <small id="sensage"></small></h2>
<table id="senstbl"><tr><td style="color:#888">reading…</td></tr></table>
<p class="note">Live readings from this panel’s sensors — shown even when a value is hidden from Home Assistant.</p></div>
<div class="card"><h2>Performance <small id="perfage"></small></h2>
<div style="display:flex;gap:6px;align-items:center;font-size:.78rem;margin-bottom:8px">
 <span style="color:#8a8">Instrumentation</span>
 <button type="button" class="pbtn" id="instron" onclick="instr(true)">On</button>
 <button type="button" class="pbtn" id="instroff" onclick="instr(false)">Off</button>
 <span style="color:#666">· samples only while this page is open; Off stops it entirely</span></div>
<canvas id="perfchart" width="600" height="96" style="height:96px"></canvas>
<div class="leg"><span style="color:#4a9eff">■</span> CPU&nbsp;&nbsp;<span style="color:#48c774">■</span> RAM&nbsp;&nbsp;<span style="color:#f5a623">■</span> GPU (% used) · ~4&nbsp;min</div>
<table id="perf"><tr><td style="color:#888">sampling…</td></tr></table></div>
<div class="card"><h2>Top processes <small>· by CPU</small></h2>
<table class="dt" id="topproc"><tr><td style="color:#888">top processes…</td></tr></table></div>
<div class="card"><h2>WebView debugging <small id="insthdr"></small></h2>
<div style="display:flex;gap:8px;margin-bottom:4px">
 <button type="button" class="pbtn" onclick="inspStart()">Enable</button>
 <button type="button" class="pbtn" onclick="inspStop()">Stop</button></div>
<p class="note" id="insthint"></p></div>
${tcard("livetbl", "Live state", if (s == null) null else liveRowsHtml(), pre = """<p class="note">What Home Assistant's control entities currently show.</p>""")}
${tcard("behavtbl", "Behaviour", s?.let { behaviourRowsHtml(it) })}
${tcard("disptbl", "Display & tuning", s?.let { displayRowsHtml(it) })}
${tcard("updtbl", "Updates", s?.let { updatesRowsHtml(it) })}
</div>
<p class="note" style="text-align:center;margin-top:18px"><a href="/api" style="color:#9cf">REST API explorer</a>
 · <a href="/api/v1/diag" target="_blank" style="color:#9cf">diagnostics</a></p>
<script src="/info.js"></script>
<script src="/assets/buildwatch.js"></script>
</div></body></html>"""
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** JSON-quote a string value (escapes backslash + double-quote). */
    private fun jsonStr(s: String): String = Json.str(s)

    /** Persist a new tame blocklist and apply the delta off-thread (tame additions, re-enable removals).
     *  Used by the fleet/JSON `tame_vendor_packages` path; the browser card uses per-package POST /tame. */
    private fun applyTameBlocklist(next: Set<String>) {
        val old = config.tameVendorPackages.toSet()
        if (next == old) return
        config.setTameVendorPackages(next.joinToString(" "))
        val add = next - old; val remove = old - next
        scope.launch { add.forEach { tame.tame(it) }; remove.forEach { tame.untame(it) } }
    }

    /**
     * Standalone "Vendor packages" card. Taming intrusive firmware apps is a distinct, deploy-time concept
     * — not part of basic configuration — so it gets its own card with **per-package action buttons**, not
     * a checkbox list behind a shared Save (which made "did it apply?" and "how do I remove one?" unclear).
     * Each row acts immediately via `POST /tame`: an active app offers **Tame**, a tamed/disabled one offers
     * **Re-enable**. A free-text box tames any package by name. Hidden where no privileged path exists (taming
     * needs root or the helper daemon). Critical / HA / own packages are never listed.
     */
    /** One Vendor-packages row: label + package id, an optional state badge, and the single action button.
     *  Shared by the card and the picker. [showState] is false on the card — every row there is already
     *  tamed (disabled), so the column is redundant and just crowds the layout. */
    private fun tameRowHtml(c: TameController.Candidate, showState: Boolean = true): String {
        val tamed = c.blocked || c.disabled
        val state = if (!showState) "" else when {
            !c.installed -> """<span style="width:80px;text-align:right;font-size:.85em;color:#888">not installed</span>"""
            c.disabled -> """<span style="width:80px;text-align:right;font-size:.85em;color:#d9a528">disabled</span>"""
            else -> """<span style="width:80px;text-align:right;font-size:.85em;color:#3fb950">active</span>"""
        }
        val action = if (tamed) "untame" else "tame"
        val label = if (tamed) "Re-enable" else "Tame"
        val btn = if (tamed) "" else "background:#7a2e2e;border-color:#7a2e2e"
        // Tags (authored or heuristic: core/vendor/user/overlay) after the label; note below the package id.
        val tags = c.tags.joinToString("") {
            """<span style="display:inline-block;background:#2a3340;color:#9cc;border-radius:4px;padding:0 6px;margin-left:5px;font-size:.7em;vertical-align:1px">${esc(it)}</span>"""
        }
        // A "recommended" badge marks the profile's defaultTame picks (safe first picks / the "Tame all
        // recommended" set) while they're still active.
        val recBadge = if (c.recommended && !tamed)
            """<span style="display:inline-block;background:#1f4d2e;color:#8fe0a8;border-radius:4px;padding:0 6px;margin-left:5px;font-size:.7em;vertical-align:1px">recommended</span>""" else ""
        val note = if (c.note.isNotBlank())
            """<br><small style="color:#9aa">${esc(c.note)}</small>""" else ""
        // A non-removable package (core Android / dashboard / ourselves) is shown for context with a muted
        // "protected" label where the action button would be — no way to disable it.
        val control = if (!c.removable)
            """<span style="font-size:.8em;color:#777;white-space:nowrap">protected</span>"""
        else
            """<form method="post" action="/api/v1/tame" style="margin:0"><input type="hidden" name="pkg" value="${esc(c.pkg)}"><input type="hidden" name="action" value="$action"><button type="submit" style="$btn;white-space:nowrap">$label</button></form>"""
        return """  <div style="display:flex;align-items:center;gap:10px;padding:9px 0;border-top:1px solid #222">
   <span style="flex:1;min-width:0;overflow:hidden">${esc(c.label)}$recBadge$tags<br><small style="color:#888">${esc(c.pkg)}</small>$note</span>
   $state
   $control
  </div>"""
    }

    private fun tameCardHtml(): String {
        if (!rootOk()) return ""   // no root/daemon → taming can't act
        // The card shows what's currently TAMED (the blocklist); discovery lives in the Find-a-package
        // picker. So a tamed package always has a visible Re-enable here.
        val cands = runCatching {
            tame.cardCandidates(config.tameVendorPackages, tameProfileCandidates)
        }.getOrDefault(emptyList())
        val rows = cands.joinToString("\n") { tameRowHtml(it, showState = false) }
        val body = rows.ifBlank {
            """<p class="note">Nothing tamed yet. Press <b>Find a package…</b> to see what's on this panel.</p>"""
        }
        return """<div class="card" id="cfg-tame"><h2>Vendor packages <small style="color:#d9a528">· experimental</small></h2>
<p class="note"><b>Tame</b> force-stops an app, stops it relaunching on boot, and blocks it drawing over the dashboard — applied immediately and on every boot. <b>Re-enable</b> undoes it. Critical system apps are never offered; nothing changes until you press a button.</p>
$body
<div style="display:flex;gap:8px;margin-top:12px">
 <button type="button" onclick="pkgPick()">Find a package…</button>
 <form method="post" action="/api/v1/tame" style="display:flex;gap:8px;flex:1;margin:0">
  <input name="pkg" autocapitalize="none" autocorrect="off" spellcheck="false" placeholder="…or tame a package by name" style="flex:1">
  <input type="hidden" name="action" value="tame">
  <button type="submit">Tame</button>
 </form>
</div>
<dialog id="pkgdlg" style="background:#1a1a1a;color:#eee;border:1px solid #333;border-radius:12px;max-width:520px;width:92%;padding:16px">
 <h3 style="margin:0 0 4px">Find a package to control</h3>
 <p class="note" style="margin:0 0 8px">Apps on this panel you might want to tame — pick one to act on it. Not every entry is unwanted; only tame things you recognise.</p>
 <div id="pkgdlgbody" style="max-height:55vh;overflow:auto">Loading…</div>
 <form method="dialog" style="margin-top:12px;text-align:right"><button>Close</button></form>
</dialog>
<script>function pkgPick(){var d=document.getElementById('pkgdlg');d.showModal();
document.getElementById('pkgdlgbody').innerHTML='Loading…';
fetch('/api/v1/tame/suggest').then(function(r){return r.text()}).then(function(t){document.getElementById('pkgdlgbody').innerHTML=t}).catch(function(){document.getElementById('pkgdlgbody').textContent='Could not list packages.'});}</script></div>"""
    }

    /** Display-sizing card (density + text scale). Empty when su isn't reachable (no control). */
    private fun displayCardHtml(): String {
        // From the shared snapshot (stale-ok + background refresh) — the density trio is three
        // `wm`/settings su round-trips and was most of the Configure tab's multi-second render.
        val snap = snapStaleOk()
        val nat = snap.densityNat
        val fs = snap.fontScale
        val cur = snap.densityCur ?: return ""
        val rec = if (recommendedDensity != null || recommendedFontScale != null)
            """ <button type="submit" name="action" value="rec" formnovalidate>HA-optimised</button>""" else ""
        return """<div class="card" id="cfg-display"><h2>Display sizing <small style="color:#d9a528">· experimental (R&amp;D)</small></h2>
<p class="note"><b>Pre-release / R&amp;D — the right values aren't dialled in yet; experiment at your own
pace.</b> Match an HA dashboard's size to a desktop browser. <b>Density</b> scales the whole layout
(lower dpi = more fits); <b>text size</b> scales WebView text. Panel firmware often ships these
mismatched to the physical screen. Applies live, persists across reboot; needs root or the helper daemon.</p>
<form method="post" action="/api/v1/display/density" style="display:flex;flex-direction:column;gap:10px">
 <label style="display:flex;flex-direction:row;justify-content:space-between;align-items:center;gap:12px">
  <span>Density (dpi) <small style="color:#888">· native ${nat ?: "?"}</small></span>
  <input name="density" type="number" min="${DensityController.MIN_DPI}" max="${DensityController.MAX_DPI}" value="$cur" style="width:96px">
 </label>
 <label style="display:flex;flex-direction:row;justify-content:space-between;align-items:center;gap:12px">
  <span>Text size <small style="color:#888">· default 1.0</small></span>
  <input name="font" type="number" step="0.05" min="${DensityController.MIN_FONT}" max="${DensityController.MAX_FONT}" value="$fs" style="width:96px">
 </label>
 <div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:2px">
  <button type="submit">Apply</button>$rec
  <button type="submit" name="action" value="reset" formnovalidate>Reset</button>
 </div>
</form></div>"""
    }

    /** Read a bundled static asset (info.js / info.css) as text. */
    private fun asset(name: String): String =
        appContext.assets.open(name).bufferedReader().use { it.readText() }

    private fun inspectJson(status: String): String =
        """{"running":${CdpRelay.running},"port":${CdpRelay.PORT},"status":"$status"}"""

    /**
     * Apply a POSTed config form/JSON (partial-merge), then live-reconfigure. Shared by the legacy
     * `/config` route and `/api/v1/config`. Fleet/JSON clients (Accept: application/json) get the new
     * config back; a browser form gets an HTML redirect to the info page. The bespoke handling of
     * identity/MQTT/logging/tame keys is preserved; the formerly MQTT-only behaviour keys are applied
     * through [applySetting] (same path as an HA command), and per-row HA-exposure toggles are stored.
     */
    private suspend fun handleConfigPost(call: ApplicationCall) {
        val p = call.receiveParameters()
        // Partial-merge: apply ONLY keys present, so a fleet tool can set one field without clobbering
        // the rest. The UI form sends every key (blank = clear), preserving its full-replace behaviour.
        // Validate panel_id up front so an invalid value is rejected before any write begins.
        val panelId = p["panel_id"]?.let {
            val slug = it.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            if (slug.isEmpty()) {
                call.respondText("invalid panel_id\n", status = HttpStatusCode.BadRequest)
                return
            }
            slug
        }
        // Persisted fields are staged into one editor and committed atomically, so a power loss mid-apply
        // can't leave a half-written config (e.g. broker set but credentials not). Live side-effects
        // (behaviour keys, reconfigure) run after the commit so they read freshly-committed state.
        config.applyBatch {
            panelId?.let { config.setPanelId(it) }
            p["friendly_name"]?.let { config.setFriendlyName(it.trim()) }
            p["dashboard_package"]?.let { config.setDashboardPackage(it.trim()) }
            p["launcher_package"]?.let { config.setLauncherPackage(it.trim()) }
            p["tame_vendor_packages"]?.let { raw ->
                applyTameBlocklist(raw.split(Regex("[\\s,]+")).map { it.trim() }
                    .filter { it.isNotEmpty() && !TameController.isCritical(it) }.toSet())
            }
            p["http_allowed_hosts"]?.let { config.setHttpAllowedHosts(it) }
            p["silence_boot_chime"]?.let { config.setSilenceBootChime(it.trim().equals("true", ignoreCase = true) || it.trim() == "1") }
            // Keep-awake (partial wakelock so SoC/network never suspend). Applied live by reconfigure().
            p["keep_awake"]?.let { config.setKeepAwake(it.trim().equals("true", ignoreCase = true) || it.trim() == "1") }
            val logEnabled = p["log_ship_enabled"]?.let { it.trim().equals("true", ignoreCase = true) || it.trim() == "1" }
            val logHost = p["log_ship_host"]?.trim()
            val logPort = p["log_ship_port"]?.trim()?.toIntOrNull()
            val logProto = p["log_ship_protocol"]?.trim()
            if (logEnabled != null || logHost != null || logPort != null || logProto != null) {
                config.setLogShipping(
                    logEnabled ?: config.logShipEnabled,
                    logHost ?: config.logShipHost,
                    logPort ?: config.logShipPort,
                    logProto ?: config.logShipProtocol,
                )
            }
            val mfr = p["manufacturer"]?.trim()
            val mdl = p["model"]?.trim()
            if (mfr != null || mdl != null) config.setHardware(
                (mfr ?: config.manufacturer).ifEmpty { "ha-paneld" },
                (mdl ?: config.model).ifEmpty { "panel agent" },
            )
            val broker = p["mqtt_broker"]?.trim()
            val user = p["mqtt_user"]?.trim()
            // Blank password keeps the current one; clearing the username clears the password too.
            val pw = if (user != null && user.isEmpty()) "" else p["mqtt_password"]?.takeIf { it.isNotEmpty() }
            if (broker != null || user != null || pw != null) config.setMqtt(
                broker ?: config.mqttBroker, user ?: config.mqttUser, pw,
            )
            // Per-row "expose to HA" toggles (ha_expose_<key>=true|false) — take effect on the reconfigure.
            for (name in p.names()) {
                if (name.startsWith("ha_expose_")) {
                    SettingValue.parseBool(p[name].orEmpty())?.let {
                        config.setHaExposed(name.removePrefix("ha_expose_"), it)
                    }
                }
            }
        }
        // Formerly MQTT-only behaviour settings — applied through the bridge's command path so HTTP
        // and HA behave identically. Registry-validated where the key is registered; invalid skipped.
        for (key in HTTP_LIVE_KEYS) {
            val raw = p[key] ?: continue
            val spec = SettingsRegistry.spec(key)
            val value = if (spec != null) {
                when (val v = SettingValue.validate(spec, raw)) {
                    is Validation.Ok -> v.normalized
                    is Validation.Bad -> continue
                }
            } else {
                raw.trim()
            }
            applySetting(key, value)
        }
        snapInvalidate()
        onReconfigure()
        if (call.request.headers["Accept"]?.contains("application/json") == true) {
            call.respondText(configJson(), ContentType.Application.Json)
        } else {
            call.respondText(
                "<!doctype html><meta charset=utf-8>" +
                    "<meta http-equiv=refresh content='2;url=/'>" +
                    "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
                    "settings saved — reconnecting…</body>",
                ContentType.Text.Html,
            )
        }
    }

    /** Behaviour keys settable over HTTP (routed through [applySetting] / the MQTT command path). */
    private val HTTP_LIVE_KEYS = listOf(
        "wake_on_wave", "prevent_idle_dim", "watchdog_enabled", "auto_brightness",
        "brightness_bias", "navbar_mode", "touch_sound", "cpu_governor",
        "network_adb", "zigbee_router", "ambient_lux",
        "companion_auto_update", "self_update", "update_channel", "home_dashboard",
    )

    /**
     * Registry metadata for generating the Configure form (type/group/tier/scope/options/range +
     * whether the setting is an HA entity and currently exposed), capability-gated to this panel.
     * Values themselves come from GET /config; this endpoint is metadata only.
     */
    private fun configSchemaJson(): String {
        fun s(v: String) = Json.str(v)
        val caps = snapStaleOk().caps   // capability probes (cpu/adb su) via the snapshot
        val hints = autoHints()   // what blank ("auto") package fields resolve to → field placeholder
        // Include the settable settings PLUS the read-only HA sensors (diagnostics): the latter carry
        // no editable value but still render an expose pip, so the user can opt them into HA.
        val schemaSpecs = SettingsRegistry.SPECS.filter { !it.readOnly || it.ha != null }
        val items = schemaSpecs.joinToString(",") { spec ->
            val opts = spec.options.joinToString(",") { s(it) }
            val isHa = spec.ha != null
            val placeholder = hints[spec.key]?.let { "auto ($it)" }
            "{" +
                "\"key\":${s(spec.key)}," +
                "\"type\":${s(spec.type.name)}," +
                "\"group\":${s(spec.group)}," +
                "\"label\":${s(spec.label)}," +
                "\"help\":${s(spec.help)}," +
                "\"default\":${s(spec.default)}," +
                "\"tier\":${s(spec.tier.name)}," +
                "\"scope\":${s(spec.scope.name)}," +
                "\"secret\":${spec.secret}," +
                "\"readOnly\":${spec.readOnly}," +
                "\"available\":${spec.availableWhen(caps)}," +
                "\"options\":[$opts]," +
                "\"min\":${spec.min?.toString() ?: "null"}," +
                "\"max\":${spec.max?.toString() ?: "null"}," +
                "\"step\":${spec.step?.toString() ?: "null"}," +
                "\"ha\":$isHa," +
                "\"exposed\":${if (isHa) config.haExposed(spec.key, spec.haExposedByDefault) else false}," +
                "\"placeholder\":${placeholder?.let { s(it) } ?: "null"}" +
                "}"
        }
        return "[$items]"
    }

    /** A setting's effective current value: controller-sourced live state where it exists, identity
     *  fields resolved (panel_id auto-derives when unset), else the persisted value. */
    private fun effectiveValue(spec: io.github.maxlyth.hapaneld.config.SettingSpec, live: Map<String, String>): String =
        live[spec.key] ?: when (spec.key) {
            "panel_id" -> config.panelId
            "friendly_name" -> config.friendlyName
            else -> config.getRaw(spec)
        }

    /** Registry-driven current values (typed JSON; secrets blanked) for the Configure form. */
    private fun settingsValuesJson(): String {
        fun s(v: String) = Json.str(v)
        val live = snapStaleOk().live   // controller-sourced keys via the snapshot, not fresh su probes
        val parts = SettingsRegistry.settable().joinToString(",") { spec ->
            val raw = effectiveValue(spec, live)
            val v = when {
                spec.secret -> "\"\""
                spec.type == SettingType.BOOL -> if (raw.toBoolean()) "true" else "false"
                spec.type == SettingType.INT -> raw.toLongOrNull()?.toString() ?: s(raw)
                spec.type == SettingType.FLOAT -> raw.toDoubleOrNull()?.toString() ?: s(raw)
                else -> s(raw)
            }
            "${s(spec.key)}:$v"
        }
        return "{$parts}"
    }

    /** Per-key HA-exposure flags for every HA-capable setting (for the inline expose pips). */
    private fun haExposeJson(): String {
        val parts = SettingsRegistry.SPECS.filter { it.ha != null }.joinToString(",") { spec ->
            "\"${spec.key}\":${config.haExposed(spec.key, spec.haExposedByDefault)}"
        }
        return "{$parts}"
    }

    /** Inject a tap at device pixel (x,y) for the interactive screenshot. su `input tap` where
     *  available; an accessibility-gesture fallback lands with the Test tab. */
    private fun injectTap(x: Float, y: Float): Boolean {
        val xi = x.toInt()
        val yi = y.toInt()
        return io.github.maxlyth.hapaneld.device.DeviceProfile.detect().appCanSu &&
            Su.run("input tap $xi $yi")
    }

    // ---- config bundles (export / transactional import) + on-panel revision history ----

    /** Current registry values as a flat map (skips transient inputs; controller-sourced settings
     *  read their live state). The basis for export, the pre-change snapshot, and the dry-run diff. */
    private fun currentValues(): Map<String, String> {
        val live = liveValues()
        val m = LinkedHashMap<String, String>()
        for (spec in SettingsRegistry.settable()) {
            if (spec.transient) continue
            m[spec.key] = live[spec.key] ?: config.getRaw(spec)
        }
        return m
    }

    /** Export a versioned config bundle. Secrets are excluded unless `?include_secrets=1`. */
    private suspend fun handleConfigExport(call: ApplicationCall) {
        val includeSecrets = call.request.queryParameters["include_secrets"] == "1"
        val live = liveValues()
        val values = LinkedHashMap<String, String>()
        for (spec in SettingsRegistry.settable()) {
            if (spec.transient) continue
            if (spec.secret && !includeSecrets) continue
            values[spec.key] = live[spec.key] ?: config.getRaw(spec)
        }
        val bundle = ConfigBundle.fromValues(
            values, exportedAt = System.currentTimeMillis().toString(), exportedBy = config.panelId,
        )
        call.response.headers.append("Content-Disposition", "attachment; filename=\"${config.panelId}-config.json\"")
        call.respondText(bundle.serialize(), ContentType.Application.Json)
    }

    /**
     * Bundle import — BEST-EFFORT by design (a bundle exported from different hardware or a different
     * ha-paneld version must still restore what it can). Parse → migrate to the current schema →
     * scope/secret filter (`?mode=fleet` applies only PORTABLE, non-secret keys; default `restore`
     * applies everything) → validate per-key against the registry: valid keys apply, invalid keys are
     * reported in `errors` and skipped, unknown keys warn and skip. `?strict=1` restores the old
     * all-or-nothing transactional behaviour. Apply itself is transactional (snapshot a revision →
     * commit the batch → live keys + reconfigure); `?dry_run=1` returns the diff without writing.
     * Status: "applied" (all valid), "partial" (some skipped as invalid), "rejected" (nothing usable
     * or strict mode with any error).
     */
    private suspend fun handleConfigImport(call: ApplicationCall) {
        val bundle = ConfigBundle.parse(call.receiveText())
        if (bundle == null) {
            call.respondText("""{"status":"bad-bundle"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return
        }
        val (migrated, warnings) = Migrations.migrate(bundle.schema, bundle.values)
        val fleet = call.request.queryParameters["mode"] == "fleet"
        val dryRun = call.request.queryParameters["dry_run"] == "1"
        val accepted = LinkedHashMap<String, String>()
        val skipped = ArrayList<String>()
        val errors = ArrayList<String>()
        val warn = warnings.toMutableList()
        for ((key, raw) in migrated) {
            val spec = SettingsRegistry.spec(key)
            if (spec == null) { warn.add("unknown key skipped: $key"); continue }
            if (spec.readOnly || spec.transient) { skipped.add(key); continue }
            if (fleet && (spec.scope != Scope.PORTABLE || spec.secret)) { skipped.add(key); continue }
            when (val v = SettingValue.validate(spec, raw)) {
                is Validation.Ok -> accepted[key] = v.normalized
                is Validation.Bad -> errors.add(v.reason)
            }
        }
        val strict = call.request.queryParameters["strict"] == "1"
        if ((strict && errors.isNotEmpty()) || (accepted.isEmpty() && errors.isNotEmpty())) {
            call.respondText(importJson("rejected", emptyList(), skipped, warn, errors), ContentType.Application.Json, HttpStatusCode.UnprocessableEntity)
            return
        }
        if (dryRun) {
            call.respondText(dryRunJson(ConfigDiff.diff(currentValues(), accepted), skipped, warn + errors.map { "would skip (invalid): $it" }), ContentType.Application.Json)
            return
        }
        applyAccepted(accepted)
        val status = if (errors.isEmpty()) "applied" else "partial"
        call.respondText(importJson(status, accepted.keys.toList(), skipped, warn, errors), ContentType.Application.Json)
    }

    /** Apply a validated value set transactionally: snapshot current → commit batch → run live-key
     *  side-effects → reconfigure. panel_id goes through its setter (cache invalidation). */
    private fun applyAccepted(accepted: Map<String, String>) {
        revisions.snapshot(
            ConfigBundle.fromValues(
                currentValues(), kind = ConfigBundle.KIND_REVISION,
                exportedAt = System.currentTimeMillis().toString(), exportedBy = config.panelId,
            ),
        )
        val editor = config.editor()
        val live = ArrayList<Pair<String, String>>()
        for ((key, value) in accepted) {
            when {
                key == "panel_id" -> config.setPanelId(value)
                key in HTTP_LIVE_KEYS -> live.add(key to value)
                else -> SettingsRegistry.spec(key)?.let { config.stage(editor, it, value) }
            }
        }
        editor.commit()
        for ((k, v) in live) applySetting(k, v)
        snapInvalidate()
        onReconfigure()
    }

    /** List on-panel revisions (newest first) as `[{id, exported_at, keys}]`. */
    private fun revisionsJson(): String =
        "[" + revisions.list().joinToString(",") { (id, b) ->
            "{\"id\":$id,\"exported_at\":\"${b.exportedAt}\",\"keys\":${b.values.size}}"
        } + "]"

    /** Roll back to a stored revision (itself recorded as a new revision, so restores are undoable). */
    private suspend fun handleRevisionRestore(call: ApplicationCall, id: Long) {
        val bundle = revisions.get(id)
        if (bundle == null) {
            call.respondText("""{"status":"not-found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            return
        }
        val (migrated, _) = Migrations.migrate(bundle.schema, bundle.values)
        val accepted = LinkedHashMap<String, String>()
        for ((key, raw) in migrated) {
            val spec = SettingsRegistry.spec(key) ?: continue
            if (spec.readOnly || spec.transient) continue
            (SettingValue.validate(spec, raw) as? Validation.Ok)?.let { accepted[key] = it.normalized }
        }
        applyAccepted(accepted)
        call.respondText(importJson("restored", accepted.keys.toList(), emptyList(), emptyList(), emptyList()), ContentType.Application.Json)
    }

    private fun jarr(items: List<String>): String =
        "[" + items.joinToString(",") { Json.str(it) } + "]"

    private fun importJson(status: String, applied: List<String>, skipped: List<String>, warnings: List<String>, errors: List<String>): String =
        "{\"status\":\"$status\",\"applied\":${jarr(applied)},\"skipped\":${jarr(skipped)},\"warnings\":${jarr(warnings)},\"errors\":${jarr(errors)}}"

    private fun dryRunJson(diff: List<ConfigDiff.Change>, skipped: List<String>, warnings: List<String>): String {
        fun q(v: String) = Json.str(v)
        val changes = diff.joinToString(",") { c ->
            "{\"key\":${q(c.key)},\"from\":${c.from?.let { q(it) } ?: "null"},\"to\":${q(c.to)}}"
        }
        return "{\"status\":\"dry_run\",\"changes\":[$changes],\"skipped\":${jarr(skipped)},\"warnings\":${jarr(warnings)}}"
    }

    /** Full config as JSON for fleet management. The MQTT password is never emitted — only a boolean
     *  saying whether one is set. `http_port` is read-only (changing it needs a restart). */
    private fun configJson(): String {
        fun s(v: String) = Json.str(v)
        return "{" +
            "\"panel_id\":${s(config.panelId)}," +
            "\"friendly_name\":${s(config.friendlyName)}," +
            "\"manufacturer\":${s(config.manufacturer)}," +
            "\"model\":${s(config.model)}," +
            "\"http_port\":${config.httpPort}," +
            "\"mqtt_broker\":${s(config.mqttBroker)}," +
            "\"mqtt_user\":${s(config.mqttUser)}," +
            "\"mqtt_password_set\":${config.mqttPassword.isNotEmpty()}," +
            "\"dashboard_package\":${s(config.dashboardPackage)}," +
            "\"launcher_package\":${s(config.launcherPackage)}," +
            "\"tame_vendor_packages\":${s(config.tameVendorPackagesRaw)}," +
            "\"silence_boot_chime\":${config.silenceBootChime}," +
            "\"keep_awake\":${config.keepAwake}," +
            "\"log_ship_enabled\":${config.logShipEnabled}," +
            "\"log_ship_host\":${s(config.logShipHost)}," +
            "\"log_ship_port\":${config.logShipPort}," +
            "\"log_ship_protocol\":${s(config.logShipProtocol)}," +
            "\"version\":${s(Config.VERSION)}," +
            "\"proximity\":${sensors.proximityJson()}," +
            // Registry-driven current values + per-key HA-exposure flags for the Configure form.
            "\"settings\":${settingsValuesJson()}," +
            "\"ha_expose\":${haExposeJson()}" +
            "}"
    }

    companion object {
        private const val TAG = "ha-paneld/http"

        // Probe-cache TTLs: the dashboard renders from the snapshot, so these bound both staleness
        // and how often the su round-trips can run. Density/su flap even less than the rest.
        private const val SNAP_TTL_MS = 15_000L
        private const val DENSITY_TTL_MS = 30_000L
        private const val SU_TTL_MS = 60_000L
        private const val COMPANION_URL_TTL_MS = 60_000L

        // Dashboard fact rows that are BACKED BY A SETTING → the Configure anchor the ✎ marker
        // deep-links to. Facts absent here are static (hardware/runtime) and get no marker.
        private val FACT_CFG = mapOf(
            "panel_id" to "cfg-panel_id",
            "Friendly name" to "cfg-friendly_name",
            "MQTT" to "cfg-mqtt_broker",
            "Navbar" to "cfg-navbar_mode",
            "Zigbee" to "cfg-zigbee_router",
            "CPU profile" to "cfg-cpu_governor",
            "Network ADB" to "cfg-network_adb",
            "Log shipping" to "cfg-log_ship_enabled",
        )
        private const val RELEASES_URL = "https://github.com/maxlyth/ha-paneld/releases"
        private const val REPO_URL = "https://github.com/maxlyth/ha-paneld"
        private const val WEBVIEW_DOC = "https://github.com/maxlyth/ha-paneld/blob/main/docs/hardware/README.md#updating-the-system-webview"
        // GitHub mark (official, CC0 simple-icons) + Material "open in new" glyph — icon links in the UI.
        private const val GH_ICON = "M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"
        private const val EXT_ICON = "M14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3m-2 16H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7z"
    }
}
