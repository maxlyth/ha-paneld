package io.github.maxlyth.hapaneld.http

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.AudioPlayer
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.control.CdpRelay
import io.github.maxlyth.hapaneld.sensors.SensorReporter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
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
    // Called after this server has written new settings to [config]; the service rebuilds MQTT/mDNS.
    private val onReconfigure: () -> Unit,
    private val info: () -> Map<String, String>,
) {
    private val urlRegex = Regex("""https?://[^\s"']+""")
    // Stored as a stop lambda over a type-inferred server local, so we never have to name Ktor's
    // EmbeddedServer<TEngine, TConfiguration> generic type (which shifts between Ktor versions).
    private var stopServer: (() -> Unit)? = null

    fun start() {
        // Bind the IPv6 wildcard "::" — on Android this is dual-stack (net.ipv6.bindv6only=0), so the
        // server answers on both IPv6 and IPv4, instead of the IPv4-only default 0.0.0.0.
        val server = embeddedServer(CIO, port = config.httpPort, host = "::") {
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
                        if (on) PerfReader.touch()
                        call.respondText("""{"enabled":$on}""", ContentType.Application.Json)
                    }
                }
                get("/diag") {
                    call.respondText(DiagReader.dump(appContext), ContentType.Text.Plain)
                }
                // Static front-end assets (externalised from the Kotlin string so CI can lint them).
                get("/info.js") {
                    call.respondText(asset("info.js"), ContentType.Application.JavaScript)
                }
                get("/info.css") {
                    call.respondText(asset("info.css"), ContentType.Text.CSS)
                }
                get("/icon.svg") {
                    call.respondText(asset("icon.svg"), ContentType.Image.SVG)
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
                get("/health") {
                    call.respondText("ha-paneld ${Config.VERSION} panel=${config.panelId}\n")
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

    private fun infoHtml(): String {
        val pid = esc(config.panelId)
        // Banner reflects the LIVE MQTT state (from the info map), not just whether a broker string is
        // set — so auto-discovery + a real connection clears it, and an auth rejection says so.
        val facts = info()
        val mqtt = facts["MQTT"] ?: "disabled"
        val needs = mutableListOf<String>()
        if (config.panelIdIsDefault) needs.add("a panel id")
        when {
            mqtt.contains("connected") || mqtt.contains("connecting") -> {} // connected / transient — fine
            mqtt.contains("auth rejected") -> needs.add("valid MQTT credentials (the broker rejected them)")
            mqtt.contains("unreachable") -> needs.add("a reachable MQTT broker")
            else -> needs.add("the MQTT broker")
        }
        val setupBanner = if (needs.isNotEmpty())
            """<div class="setup">⚠ This panel needs <a href="#config">${needs.joinToString(" and ")}</a> — set below.</div>"""
        else ""
        val rows = facts.entries.joinToString("\n") { (k, v) ->
            // Version: plain text + a small "open releases" icon (a hyperlinked version reads ugly).
            val cell = if (k == "ha-paneld") {
                """${esc(v)} <a class="ext" href="$RELEASES_URL" target="_blank" rel="noopener" """ +
                    """title="Releases" aria-label="Releases"><svg viewBox="0 0 24 24"><path d="$EXT_ICON"/></svg></a>"""
            } else {
                esc(v)
            }
            "<tr><th>${esc(k)}</th><td>$cell</td></tr>"
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
<link rel="stylesheet" href="/info.css"></head><body><div class="wrap">
<div class="hdr"><h1><img src="/icon.svg" class="logo" alt="">ha-paneld <small>· $pid</small></h1>
 <a class="gh" href="$REPO_URL" target="_blank" rel="noopener" title="ha-paneld on GitHub" aria-label="GitHub"><svg viewBox="0 0 24 24"><path d="$GH_ICON"/></svg></a></div>
$setupBanner
<div class="cards">
<div class="card"><h2>Panel information</h2><table>
$rows
</table></div>
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
<div style="display:flex;gap:6px;flex-wrap:wrap;align-items:center;font-size:.85rem;margin-bottom:8px">
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
<div class="card"><h2 id="config">Configuration</h2>
<form method="post" action="/config">
 <label>Panel id <small>(entity_ids / MQTT topics)</small>
  <input name="panel_id" autocapitalize="none" autocorrect="off" spellcheck="false" value="$pid" pattern="[a-z0-9_]+" title="lowercase letters, digits, underscore" required></label>
 <label>Friendly name <small>(HA device name)</small>
  <input name="friendly_name" value="${esc(config.friendlyName)}" placeholder="Office Dash"></label>
 <label>Manufacturer <small>(HA device card)</small>
  <input name="manufacturer" value="${esc(config.manufacturer)}" placeholder="Sonoff"></label>
 <label>Model <small>(HA device card)</small>
  <input name="model" value="${esc(config.model)}" placeholder="NSPanel Pro 120"></label>
 <label>MQTT broker
  <input name="mqtt_broker" autocapitalize="none" autocorrect="off" spellcheck="false" value="${esc(config.mqttBroker)}" placeholder="blank = auto-discover Home Assistant on the LAN"></label>
 <label>MQTT username
  <input name="mqtt_user" autocapitalize="none" autocorrect="off" spellcheck="false" value="${esc(config.mqttUser)}" placeholder="blank if the broker needs no login" autocomplete="off"></label>
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
