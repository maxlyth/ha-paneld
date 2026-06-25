package io.github.maxlyth.hapaneld.http

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.AudioPlayer
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.control.CdpRelay
import io.github.maxlyth.hapaneld.control.DensityController
import io.github.maxlyth.hapaneld.control.Su
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.control.TameController
import io.github.maxlyth.hapaneld.control.VolumeController
import io.github.maxlyth.hapaneld.input.PanelAccessibilityService
import io.github.maxlyth.hapaneld.sensors.SensorReporter
import io.github.maxlyth.hapaneld.util.HelperClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    private val info: () -> Map<String, String>,
    // Per-panel "HA-optimised" density + text-scale suggestions (DeviceProfile), or null.
    private val recommendedDensity: Int? = null,
    private val recommendedFontScale: Float? = null,
    // Vendor-taming: the controller (applies on Save), the profile's curated candidate suggestions, and
    // whether to enumerate live (Generic panel) vs show the curated list (a profiled panel).
    private val tame: TameController,
    private val tameProfileCandidates: List<String> = emptyList(),
    private val tameEnumerate: Boolean = false,
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
            }
            routing {
                get("/") {
                    call.respondText(infoHtml(), ContentType.Text.Html)
                }
                get("/perf") {
                    PerfReader.touch() // mark the page as being viewed so the sampler runs (idle otherwise)
                    call.respondText(PerfReader.json(), ContentType.Application.Json)
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
                        // hard, useful action is escaping TO the system launcher to reach Settings/config apps.
                        "launcher" -> { system.launchLauncher(config.launcherPackage); true }
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
                get("/diag") {
                    call.respondText(DiagReader.dump(appContext, info()), ContentType.Text.Plain)
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
                // Self-contained REST API explorer (no Swagger-UI CDN bundle) + the OpenAPI spec it
                // renders — the spec also imports into Swagger/Postman for fleet tooling.
                get("/api") {
                    call.respondText(asset("api.html"), ContentType.Text.Html)
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
                // Machine-readable config for fleet management (password redacted to a boolean).
                get("/config") {
                    call.respondText(configJson(), ContentType.Application.Json)
                }
                post("/config") {
                    val p = call.receiveParameters()
                    // Partial-merge: apply ONLY keys present in the request, so a fleet tool can set a
                    // single field without clobbering the rest. The UI form sends every key (blank =
                    // clear), so its full-replace behaviour is preserved.
                    p["panel_id"]?.let {
                        val slug = it.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                        if (slug.isEmpty()) {
                            call.respondText("invalid panel_id\n", status = HttpStatusCode.BadRequest)
                            return@post
                        }
                        config.setPanelId(slug)
                    }
                    p["friendly_name"]?.let { config.setFriendlyName(it.trim()) }
                    p["dashboard_package"]?.let { config.setDashboardPackage(it.trim()) }
                    p["launcher_package"]?.let { config.setLauncherPackage(it.trim()) }
                    // Vendor taming via the config page is per-package on its own card (POST /tame); the
                    // browser form no longer carries it. A fleet/JSON tool may still set the whole blocklist
                    // by raw `tame_vendor_packages`, applying the delta off-thread (tame added / re-enable
                    // removed).
                    p["tame_vendor_packages"]?.let { raw ->
                        applyTameBlocklist(raw.split(Regex("[\\s,]+")).map { it.trim() }
                            .filter { it.isNotEmpty() && !TameController.isCritical(it) }.toSet())
                    }
                    val mfr = p["manufacturer"]?.trim()
                    val mdl = p["model"]?.trim()
                    if (mfr != null || mdl != null) config.setHardware(
                        (mfr ?: config.manufacturer).ifEmpty { "ha-paneld" },
                        (mdl ?: config.model).ifEmpty { "panel agent" },
                    )
                    val broker = p["mqtt_broker"]?.trim()
                    val user = p["mqtt_user"]?.trim()
                    // Blank password normally means "keep the current one" (so you don't re-type it).
                    // EXCEPTION: clearing the username clears the password too — otherwise there's no
                    // way to drop auth, and an empty user with a stale password gets rejected.
                    val pw = if (user != null && user.isEmpty()) "" // clear both → anonymous
                    else p["mqtt_password"]?.takeIf { it.isNotEmpty() } // blank/absent => unchanged
                    if (broker != null || user != null || pw != null) config.setMqtt(
                        broker ?: config.mqttBroker, user ?: config.mqttUser, pw,
                    )
                    onReconfigure()
                    // Fleet tools (Accept: application/json) get the new config back; the browser form
                    // gets an HTML redirect to the info page.
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
                // Per-package vendor taming from the Vendor packages card. action=tame adds the package to
                // the blocklist and tames it now; action=untame removes it and re-enables it. The work is
                // privileged + slow, so it runs off-thread and the browser gets a short auto-reload back to
                // the info page (the row's new state shows on reload).
                post("/tame") {
                    val p = call.receiveParameters()
                    val pkg = p["pkg"]?.trim().orEmpty()
                    val untame = p["action"]?.trim() == "untame"
                    if (pkg.isNotEmpty() && !TameController.isCritical(pkg)) {
                        val next = config.tameVendorPackages.toMutableSet()
                        if (untame) next.remove(pkg) else next.add(pkg)
                        config.setTameVendorPackages(next.joinToString(" "))
                        scope.launch { if (untame) tame.untame(pkg) else tame.tame(pkg) }
                    }
                    val verb = if (untame) "re-enabling" else "taming"
                    call.respondText(
                        "<!doctype html><meta charset=utf-8><meta http-equiv=refresh content='2;url=/'>" +
                            "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
                            "$verb ${esc(pkg)}…</body>",
                        ContentType.Text.Html,
                    )
                }
                // The "Find a package…" picker pop-up content: an on-demand enumeration of likely
                // controllable apps (so users who don't know package names can pick from a list). Lazy —
                // only enumerated when the dialog is opened — and excludes packages already in the card.
                get("/tame/suggest") {
                    val shown = (tameProfileCandidates + config.tameVendorPackages).toSet()
                    val sugg = runCatching { tame.suggestions(shown) }.getOrDefault(emptyList())
                    val frag = if (sugg.isEmpty())
                        """<p class="note">No other packages found.</p>"""
                    else sugg.joinToString("\n") { tameRowHtml(it) }
                    call.respondText(frag, ContentType.Text.Html)
                }
                post("/density") {
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
                    call.respondText(
                        "<!doctype html><meta charset=utf-8><meta http-equiv=refresh content='1;url=/'>" +
                            "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
                            (if (ok) "display density applied" else "density unchanged") + "…</body>",
                        ContentType.Text.Html,
                    )
                }
                get("/health") {
                    call.respondText("ha-paneld ${Config.VERSION} panel=${config.panelId} build=${buildToken()}\n")
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
                post("/play") {
                    val body = call.receiveText()
                    val url = urlRegex.find(body)?.value
                    if (url == null) {
                        call.respondText("no-url\n", status = HttpStatusCode.BadRequest)
                        return@post
                    }
                    // Respond immediately; playback runs detached (mirrors socat + setsid nohup).
                    call.respondText("playing\n")
                    scope.launch { AudioPlayer.play(cacheDir, url) }
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

    private fun infoHtml(): String {
        val pid = esc(config.panelId)
        // Banner reflects the LIVE MQTT state (from the info map), not just whether a broker string is
        // set — so auto-discovery + a real connection clears it, and an auth rejection says so.
        val facts = info()
        val mqtt = facts["MQTT"] ?: "disabled"
        // Pure decision (unit-tested in SetupBannerTest) — note a CONFIGURED broker that's merely
        // mid-(re)connect must not be reported as missing.
        val needs = SetupBanner.needs(mqtt, config.mqttBroker.isNotBlank(), config.panelIdIsDefault)
        val setupBanner = if (needs.isNotEmpty())
            """<div class="setup">⚠ This panel needs <a href="#config">${needs.joinToString(" and ")}</a> — set below.</div>"""
        else ""
        // Split the flat fact map into separate cards so it renders ACROSS masonry columns instead of one
        // ever-growing tall card. Networking + ha-paneld-profile are carved out; everything else (device /
        // OS facts, plus any future key) falls through to "Panel information".
        val netKeys = listOf("Local IP", "Local IPv6", "HTTP port", "MQTT", "mDNS", "Network ADB")
        // Rows whose values are DECLARED by the DeviceProfile, so wrong data points a contributor straight
        // at the fix: Platform=displayName/socClass, LED=ledMechanism, sensor tech=proximityTech/lightTech,
        // Zigbee=zigbeeGatewayDir, Relays=relayBase, CPU profile=cpuGovernors. (panel_id / Friendly name are
        // user config and Accessibility nav is runtime state — they fall through to Panel information.)
        val profKeys = listOf("Platform", "LED", "Light sensor", "Proximity", "Zigbee", "Relays", "CPU profile")
        val grouped = (netKeys + profKeys).toSet()
        val infoKeys = facts.keys.filter { it !in grouped }
        fun factRows(keys: List<String>): String =
            keys.filter { facts.containsKey(it) }.joinToString("\n") { k ->
                val v = facts.getValue(k)
                // Version: plain text + a small "open releases" icon (a hyperlinked version reads ugly).
                val cell = if (k == "ha-paneld") {
                    """${esc(v)} <a class="ext" href="$RELEASES_URL" target="_blank" rel="noopener" """ +
                        """title="Releases" aria-label="Releases"><svg viewBox="0 0 24 24"><path d="$EXT_ICON"/></svg></a>"""
                } else if (k == "Display") {
                    displayCell(v)
                } else if (k in SECRET_FIELDS || (k in ADDRESS_FIELDS && isRoutable(v))) {
                    // Blurred by default so a casual screenshot doesn't leak it; "Reveal" un-blurs (screenshot
                    // hygiene, not access control — the value is still in the page source).
                    """<span class="secret">${esc(v)}</span>"""
                } else {
                    esc(v)
                }
                "<tr><th>${esc(k)}</th><td>$cell</td></tr>"
            }
        fun factCard(title: String, keys: List<String>, note: String = ""): String {
            val r = factRows(keys)
            return if (r.isBlank()) "" else """<div class="card"><h2>${esc(title)}</h2><table>$r</table>$note</div>"""
        }
        // Controls buttons: render but DISABLE (not hide, not silently-broken) when the action's capability
        // is missing — back/recents need the a11y service, launcher/reboot need root; volume always works.
        val a11yOk = facts["Accessibility nav"] == "yes"
        // Recents is only real where the firmware has an overview screen — KEYCODE_APP_SWITCH no-ops on
        // single-purpose panels (TPA10), so gate the button on the profile rather than show a dead one.
        val hasRecents = io.github.maxlyth.hapaneld.device.DeviceProfile.detect().hasRecents
        val rootOk = Su.available() || HelperClient.available()
        fun pbtn(action: String, label: String, ok: Boolean, needs: String, style: String = ""): String {
            val dis = if (ok) "" else """ disabled title="needs $needs""""
            return """<button class="pbtn"$style onclick="act('$action')"$dis>$label</button>"""
        }
        val capColor = mapOf("ok" to "#48c774", "degraded" to "#d9a528", "none" to "#d04a3b")
        val capRows = DiagReader.capabilities(appContext).joinToString("\n") { c ->
            val col = capColor[c.status] ?: "#888"
            """<tr><th>${esc(c.name)}</th><td><span style="color:$col">●</span> ${esc(c.note)}</td></tr>"""
        }
        return """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ha-paneld · $pid</title>
<link rel="icon" href="/icon.svg">
<link rel="stylesheet" href="/info.css"></head><body data-ver="${Config.VERSION}" data-build="${buildToken()}"><div class="wrap">
<div class="hdr"><h1><img src="/icon.svg" class="logo" alt="">ha-paneld <small>· $pid</small></h1>
 <span style="display:flex;gap:10px;align-items:center">${if (config.haDeviceUrl.isNotBlank()) """<a class="pbtn" href="${esc(config.haDeviceUrl)}" target="_blank" rel="noopener" title="Open this panel's device page in Home Assistant">Open in HA</a>""" else ""}<button id="revbtn" class="pbtn" onclick="toggleReveal()" title="Show/hide blurred values for editing — they're blurred by default so screenshots don't leak them">Reveal</button>
 <a class="gh" href="$REPO_URL" target="_blank" rel="noopener" title="ha-paneld on GitHub" aria-label="GitHub"><svg viewBox="0 0 24 24"><path d="$GH_ICON"/></svg></a></span></div>
<div id="verbar" class="setup" style="display:none">⟳ A newer ha-paneld is installed — <a href="#" onclick="location.reload();return false">reload</a> to refresh this page.</div>
$setupBanner
<div class="cards">
<div class="card"><h2>Controls <small>· software nav bar</small></h2>
<div style="display:flex;gap:8px;flex-wrap:wrap">
 ${pbtn("back", "← Back", a11yOk, "the accessibility service")}
 ${pbtn("recents", "▢ Recents", a11yOk && hasRecents, if (hasRecents) "the accessibility service" else "a Recents/overview screen (absent on this panel)")}
 ${pbtn("launcher", "⊞ Launcher", rootOk, "root (su or the helper daemon)")}
</div>
<div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:8px">
 ${pbtn("voldn", "Vol −", true, "")}
 ${pbtn("volup", "Vol +", true, "")}
 ${pbtn("reboot", "⟳ Reboot", rootOk, "root (su or the helper daemon)", """ style="margin-left:auto;border-color:#7a3a2a;color:#f5a08a"""")}
</div>
<p class="note">For panels with no physical nav bar. Back/Recents use the accessibility service; Launcher/Reboot need root — actions whose capability is missing are disabled (hover for why).</p></div>
${if (rootOk) """<div class="card"><h2>Screenshot <small>· live panel</small></h2>
<a class="shot" href="/screenshot.png" target="_blank" rel="noopener" title="Open full size in a new window" style="aspect-ratio:${screenAspectRatio()}"><img src="/screenshot.png" alt="panel screenshot" onload="this.parentElement.classList.add('loaded')" onerror="this.parentElement.classList.add('failed')"></a>
<p class="note"><a href="#" onclick="var s=this.closest('.card').querySelector('.shot');s.classList.remove('loaded','failed');s.querySelector('img').src='/screenshot.png?t='+Date.now();return false" style="color:#9cf">↻ Refresh</a> · click the image to open it full size. Captured on demand via root (`screencap`); local-network only.</p></div>""" else ""}
${factCard("Panel information", infoKeys)}
${factCard("Networking", netKeys)}
${factCard("ha-paneld profile", profKeys, """<p class="note">Values declared by this panel's <a href="$REPO_URL/blob/main/docs/architecture/device-profiles.md" target="_blank" rel="noopener" style="color:#9cf">device profile</a> — if one looks wrong, that's where to correct it.</p>""")}
<div class="card"><h2>Capabilities</h2><table>
$capRows
</table>
<p class="note"><a href="/diag" target="_blank" style="color:#9cf">⭳ Diagnostics dump</a> — a copy-paste
report of this panel's hardware, firmware, SELinux, su and node probes for bug reports.</p></div>
<div class="card"><h2>Responsiveness <small id="smhdr"></small></h2>
<canvas id="smchart" width="600" height="130" style="height:130px"></canvas>
<div class="leg">dashboard main-thread CPU (% of one core) ·
 <span style="color:#48c774">▬</span> snappy &lt;50% · <span style="color:#d9a528">▬</span> maxed &gt;85%</div>
<table id="smtbl"><tr><td style="color:#888">measuring…</td></tr></table></div>
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
<div class="card"><h2>Proximity tuning <small id="proxstate"></small></h2>
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
</div></div>
<div class="card"><h2>WebView debugging <small id="insthdr"></small></h2>
<div style="display:flex;gap:8px;margin-bottom:4px">
 <button type="button" class="pbtn" onclick="inspStart()">Enable</button>
 <button type="button" class="pbtn" onclick="inspStop()">Stop</button></div>
<p class="note" id="insthint"></p></div>
${displayCardHtml()}
${tameCardHtml()}
<div class="card"><h2 id="config">Configuration</h2>
<form method="post" action="/config">
 <label>Panel id <small>(entity_ids / MQTT topics)</small>
  <input name="panel_id" autocapitalize="none" autocorrect="off" spellcheck="false" value="$pid" pattern="[a-z0-9_]+" title="lowercase letters, digits, underscore" required></label>
 <label>Friendly name <small>(HA device name)</small>
  <input name="friendly_name" value="${esc(config.friendlyName)}" placeholder="Office Dash"></label>
 <label>Manufacturer <small>(HA device card; blank = ${esc(config.manufacturer)})</small>
  <input name="manufacturer" value="${esc(config.manufacturerRaw)}" placeholder="${esc(config.manufacturer)}"></label>
 <label>Model <small>(HA device card; blank = ${esc(config.model)})</small>
  <input name="model" value="${esc(config.modelRaw)}" placeholder="${esc(config.model)}"></label>
 <label>MQTT broker
  <input class="secret" name="mqtt_broker" autocapitalize="none" autocorrect="off" spellcheck="false" value="${esc(config.mqttBroker)}" placeholder="blank = auto-discover Home Assistant on the LAN"></label>
 <label>MQTT username
  <input class="secret" name="mqtt_user" autocapitalize="none" autocorrect="off" spellcheck="false" value="${esc(config.mqttUser)}" placeholder="blank if the broker needs no login" autocomplete="off"></label>
 <label>MQTT password
  <input name="mqtt_password" type="password" value="" placeholder="blank keeps it; clear the username to remove auth" autocomplete="new-password"></label>
 <label>Dashboard package <small>(Reload button; blank = auto-detect Companion)</small>
  <input name="dashboard_package" autocapitalize="none" autocorrect="off" spellcheck="false" value="${esc(config.dashboardPackage)}" placeholder="io.homeassistant.companion.android"></label>
 <label>Launcher package <small>(blank = auto-detect)</small>
  <input name="launcher_package" autocapitalize="none" autocorrect="off" spellcheck="false" value="${esc(config.launcherPackage)}" placeholder="auto"></label>
 <button type="submit">Save</button>
</form>
<p class="note">Leave the broker blank to auto-discover Home Assistant on the LAN (via mDNS) and use its
MQTT broker on :1883; set it explicitly if your broker is elsewhere, on a non-HA host, or if your
network has more than one Home Assistant instance. Leave username/password blank if the broker allows
anonymous connections; if it needs a login (e.g. the HA Mosquitto add-on), enter your MQTT credentials —
the MQTT line above shows <b>auth rejected</b> until they're correct. Password never shown — blank keeps
the current one. Changing the panel id may leave the old device in HA to remove manually.</p></div>
</div>
<p class="note" style="text-align:center;margin-top:18px"><a href="/api" style="color:#9cf">REST API explorer</a>
 · <a href="/diag" target="_blank" style="color:#9cf">diagnostics</a></p>
<script src="/info.js"></script>
</div></body></html>"""
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

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
    /** One Vendor-packages row: label + package id, a state badge, and the single action button. Shared by
     *  the card and the picker pop-up so they stay visually identical. */
    private fun tameRowHtml(c: TameController.Candidate): String {
        val tamed = c.blocked || c.disabled
        val state = when {
            !c.installed -> """<span style="color:#888">not installed</span>"""
            c.disabled -> """<span style="color:#d9a528">disabled</span>"""
            else -> """<span style="color:#3fb950">active</span>"""
        }
        val action = if (tamed) "untame" else "tame"
        val label = if (tamed) "Re-enable" else "Tame"
        val btn = if (tamed) "" else "background:#7a2e2e;border-color:#7a2e2e"
        return """  <div style="display:flex;align-items:center;gap:10px;padding:9px 0;border-top:1px solid #222">
   <span style="flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis">${esc(c.label)}<br><small style="color:#888">${esc(c.pkg)}</small></span>
   <span style="width:80px;text-align:right;font-size:.85em">$state</span>
   <form method="post" action="/tame" style="margin:0"><input type="hidden" name="pkg" value="${esc(c.pkg)}"><input type="hidden" name="action" value="$action"><button type="submit" style="$btn">$label</button></form>
  </div>"""
    }

    private fun tameCardHtml(): String {
        if (!Su.available() && !HelperClient.available()) return ""   // no root/daemon → taming can't act
        val cands = runCatching {
            tame.candidates(tameProfileCandidates, config.tameVendorPackages, tameEnumerate)
        }.getOrDefault(emptyList())
        val rows = cands.joinToString("\n") { tameRowHtml(it) }
        val hint = if (tameEnumerate)
            "Apps on this panel that look like vendor add-ons (they have a launcher icon or can draw over the dashboard)."
        else
            "Known intrusive packages for this panel."
        val body = rows.ifBlank {
            """<p class="note">No vendor packages flagged${if (tameEnumerate) "" else " for this panel"}. Use <b>Find a package…</b> below, or add one by name.</p>"""
        }
        return """<div class="card"><h2>Vendor packages <small style="color:#d9a528">· experimental</small></h2>
<p class="note">$hint <b>Tame</b> force-stops the app, stops it relaunching on boot, and blocks it drawing over the dashboard — applied immediately and on every boot. <b>Re-enable</b> undoes it. Critical system apps are never listed; nothing changes until you press a button.</p>
$body
<div style="display:flex;gap:8px;margin-top:12px">
 <button type="button" onclick="pkgPick()">Find a package…</button>
 <form method="post" action="/tame" style="display:flex;gap:8px;flex:1;margin:0">
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
fetch('/tame/suggest').then(function(r){return r.text()}).then(function(t){document.getElementById('pkgdlgbody').innerHTML=t}).catch(function(){document.getElementById('pkgdlgbody').textContent='Could not list packages.'});}</script></div>"""
    }

    /** Display-sizing card (density + text scale). Empty when su isn't reachable (no control). */
    private fun displayCardHtml(): String {
        val cur = density.current() ?: return ""
        val nat = density.native()
        val fs = density.fontScale()
        val rec = if (recommendedDensity != null || recommendedFontScale != null)
            """ <button type="submit" name="action" value="rec" formnovalidate>HA-optimised</button>""" else ""
        return """<div class="card"><h2>Display sizing <small style="color:#d9a528">· experimental (R&amp;D)</small></h2>
<p class="note"><b>Pre-release / R&amp;D — the right values aren't dialled in yet; experiment at your own
pace.</b> Match an HA dashboard's size to a desktop browser. <b>Density</b> scales the whole layout
(lower dpi = more fits); <b>text size</b> scales WebView text. Panel firmware often ships these
mismatched to the physical screen. Applies live, persists across reboot; needs root or the helper daemon.</p>
<form method="post" action="/density" style="display:flex;flex-direction:column;gap:10px">
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

    /** Full config as JSON for fleet management. The MQTT password is never emitted — only a boolean
     *  saying whether one is set. `http_port` is read-only (changing it needs a restart). */
    private fun configJson(): String {
        fun s(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
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
            "\"version\":${s(Config.VERSION)}," +
            "\"proximity\":${sensors.proximityJson()}" +
            "}"
    }

    companion object {
        private const val TAG = "ha-paneld/http"
        private const val RELEASES_URL = "https://github.com/maxlyth/ha-paneld/releases"
        private const val REPO_URL = "https://github.com/maxlyth/ha-paneld"
        // GitHub mark (official, CC0 simple-icons) + Material "open in new" glyph — icon links in the UI.
        private const val GH_ICON = "M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"
        private const val EXT_ICON = "M14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3m-2 16H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7z"
    }
}
