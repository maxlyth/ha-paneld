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
        val server = embeddedServer(CIO, port = config.httpPort) {
            routing {
                get("/") {
                    call.respondText(infoHtml(), ContentType.Text.Html)
                }
                get("/perf") {
                    call.respondText(PerfReader.json(), ContentType.Application.Json)
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
                    val pw = p["mqtt_password"]?.takeIf { it.isNotEmpty() } // blank/absent => unchanged
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
        val rows = info().entries.joinToString("\n") { (k, v) ->
            // Link the ha-paneld version to its GitHub releases page.
            val cell = if (k == "ha-paneld") {
                """<a href="$RELEASES_URL" target="_blank" rel="noopener">${esc(v)}</a>"""
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
<link rel="stylesheet" href="/info.css"></head><body><div class="wrap">
<h1>ha-paneld <small>· $pid</small></h1>
<h2>Panel information</h2>
<table>
$rows
</table>
<h2>Capabilities</h2>
<table>
$capRows
</table>
<p class="note"><a href="/diag" target="_blank" style="color:#9cf">⭳ Diagnostics dump</a> — a copy-paste
report of this panel's hardware, firmware, SELinux, su and node probes. Paste it into a bug report so
the maintainer can help with your hardware/firmware combination without owning it.</p>
<h2>Responsiveness <small id="smhdr" style="color:#8a8;font-weight:400"></small></h2>
<canvas id="smchart" width="600" height="130"
 style="width:100%;max-width:600px;height:130px;background:#181818;border-radius:8px;display:block;margin-bottom:6px"></canvas>
<div style="font-size:.72rem;color:#8a8;margin-bottom:6px">dashboard main-thread CPU (% of one core), over time ·
 <span style="color:#48c774">▬</span> snappy under 50% · <span style="color:#d9a528">▬</span> maxed over 85%</div>
<table id="smtbl"><tr><td style="color:#888">measuring…</td></tr></table>
<h2>Performance <small id="perfage" style="color:#8a8;font-weight:400"></small></h2>
<canvas id="perfchart" width="600" height="96"
 style="width:100%;max-width:600px;background:#181818;border-radius:8px;display:block;margin-bottom:8px"></canvas>
<div style="font-size:.75rem;color:#8a8;margin-bottom:6px">
 <span style="color:#4a9eff">■</span> CPU&nbsp;&nbsp;<span style="color:#48c774">■</span> RAM&nbsp;&nbsp;<span style="color:#f5a623">■</span> GPU (% used) · ~4&nbsp;min</div>
<table id="perf"><tr><td style="color:#888">sampling…</td></tr></table>
<table id="topproc" style="margin-top:12px"><tr><td style="color:#888">top processes…</td></tr></table>
<h2>Proximity tuning <small id="proxstate" style="color:#8a8;font-weight:400"></small></h2>
<div id="proxbox" style="display:none">
<canvas id="proxgauge" width="600" height="46" class="gradedonly"
 style="width:100%;max-width:600px;background:#181818;border-radius:8px;display:block;margin-bottom:6px"></canvas>
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
</div>
<h2>WebView debugging <small id="insthdr" style="color:#8a8;font-weight:400"></small></h2>
<div style="display:flex;gap:8px;margin-bottom:4px">
 <button type="button" class="pbtn" onclick="inspStart()">Enable</button>
 <button type="button" class="pbtn" onclick="inspStop()">Stop</button></div>
<p class="note" id="insthint"></p>
<h2>Configuration</h2>
<form method="post" action="/config">
 <label>Panel id <small>(entity_ids / MQTT topics)</small>
  <input name="panel_id" value="$pid" pattern="[a-z0-9_]+" title="lowercase letters, digits, underscore" required></label>
 <label>Friendly name <small>(HA device name)</small>
  <input name="friendly_name" value="${esc(config.friendlyName)}" placeholder="Office Dash"></label>
 <label>Manufacturer <small>(HA device card)</small>
  <input name="manufacturer" value="${esc(config.manufacturer)}" placeholder="Sonoff"></label>
 <label>Model <small>(HA device card)</small>
  <input name="model" value="${esc(config.model)}" placeholder="NSPanel Pro 120"></label>
 <label>MQTT broker
  <input name="mqtt_broker" value="${esc(config.mqttBroker)}" placeholder="tcp://192.168.1.10:1883"></label>
 <label>MQTT username
  <input name="mqtt_user" value="${esc(config.mqttUser)}" placeholder="(optional)" autocomplete="off"></label>
 <label>MQTT password
  <input name="mqtt_password" type="password" value="" placeholder="(unchanged)" autocomplete="new-password"></label>
 <label>Dashboard package <small>(for the Reload button; blank = disabled)</small>
  <input name="dashboard_package" value="${esc(config.dashboardPackage)}" placeholder="io.homeassistant.companion.android"></label>
 <label>Launcher package <small>(for the Launcher button; blank = auto-detect)</small>
  <input name="launcher_package" value="${esc(config.launcherPackage)}" placeholder="auto"></label>
 <button type="submit">Save</button>
</form>
<p class="note">Leave the broker blank to run HTTP/TTS-only (MQTT disabled). The password field is
never shown — leave it blank to keep the current one. Saving reconnects MQTT and re-publishes Home
Assistant discovery; changing the panel id may leave the old device in HA to remove manually.
Served over plain HTTP on the LAN.</p>
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
    }
}
