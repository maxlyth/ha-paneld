package io.github.maxlyth.hapaneld.http

import android.util.Log
import io.github.maxlyth.hapaneld.AudioPlayer
import io.github.maxlyth.hapaneld.Config
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
                post("/config") {
                    val p = call.receiveParameters()
                    val slug = p["panel_id"].orEmpty().lowercase()
                        .replace(Regex("[^a-z0-9]+"), "_").trim('_')
                    if (slug.isEmpty()) {
                        call.respondText("invalid panel_id\n", status = HttpStatusCode.BadRequest)
                        return@post
                    }
                    // Persist directly (this server holds Config); the service then reconfigures.
                    config.setPanelId(slug)
                    config.setFriendlyName(p["friendly_name"].orEmpty().trim())
                    config.setMqtt(
                        p["mqtt_broker"].orEmpty().trim(),
                        p["mqtt_user"].orEmpty().trim(),
                        // Blank password field => keep the stored one (the form never echoes it).
                        p["mqtt_password"].orEmpty().ifEmpty { null },
                    )
                    config.setDashboardPackage(p["dashboard_package"].orEmpty().trim())
                    config.setLauncherPackage(p["launcher_package"].orEmpty().trim())
                    config.setHardware(
                        p["manufacturer"].orEmpty().trim().ifEmpty { "ha-paneld" },
                        p["model"].orEmpty().trim().ifEmpty { "panel agent" },
                    )
                    onReconfigure()
                    call.respondText(
                        "<!doctype html><meta charset=utf-8>" +
                            "<meta http-equiv=refresh content='2;url=/'>" +
                            "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
                            "settings saved — reconnecting…</body>",
                        ContentType.Text.Html,
                    )
                }
                get("/health") {
                    call.respondText("ha-paneld ${Config.VERSION} panel=${config.panelId}\n")
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
        server.start(wait = false)
        stopServer = { server.stop(500, 1500) }
        Log.i(TAG, "HTTP listening on :${config.httpPort}")
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
        return """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ha-paneld · $pid</title>
<style>
 body{font-family:system-ui,-apple-system,sans-serif;margin:0;background:#111;color:#eee}
 .wrap{max-width:680px;margin:0 auto;padding:20px}
 h1{font-size:1.3rem;margin:0 0 .2rem} h1 small{color:#9af;font-weight:400}
 h2{font-size:.95rem;margin:1.6rem 0 .4rem;color:#9cf;text-transform:uppercase;letter-spacing:.04em}
 table{border-collapse:collapse;width:100%}
 th,td{text-align:left;padding:7px 8px;border-bottom:1px solid #2a2a2a;vertical-align:top}
 th{color:#9af;width:42%;font-weight:600}
 form{margin-top:10px;display:flex;flex-direction:column;gap:12px;max-width:380px}
 label{display:flex;flex-direction:column;gap:4px;font-size:.8rem;color:#9af}
 input,button{font-size:1rem;padding:9px 12px;border-radius:8px;border:1px solid #444;background:#1c1c1c;color:#eee}
 button{background:#2557a7;border-color:#2557a7;cursor:pointer;align-self:flex-start;padding:9px 22px}
 .note{color:#8a8;margin-top:10px;font-size:.85rem}
</style></head><body><div class="wrap">
<h1>ha-paneld <small>· $pid</small></h1>
<h2>Panel information</h2>
<table>
$rows
</table>
<h2>Performance <small id="perfage" style="color:#8a8;font-weight:400"></small></h2>
<canvas id="perfchart" width="600" height="96"
 style="width:100%;max-width:600px;background:#181818;border-radius:8px;display:block;margin-bottom:8px"></canvas>
<div style="font-size:.75rem;color:#8a8;margin-bottom:6px">
 <span style="color:#4a9eff">■</span> CPU&nbsp;&nbsp;<span style="color:#48c774">■</span> RAM&nbsp;&nbsp;<span style="color:#f5a623">■</span> GPU (% used) · ~4&nbsp;min</div>
<table id="perf"><tr><td style="color:#888">sampling…</td></tr></table>
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
<script>
var cpuH=[],ramH=[],gpuH=[],MAX=120;  // ~4 min at 2s
function row(k,v){return '<tr><th>'+k+'</th><td>'+v+'</td></tr>';}
function draw(){
 var c=document.getElementById('perfchart'),x=c.getContext('2d'),W=c.width,H=c.height;
 x.clearRect(0,0,W,H);
 x.strokeStyle='#2a2a2a';x.lineWidth=1;
 [0.25,0.5,0.75].forEach(function(f){var y=H-f*H;x.beginPath();x.moveTo(0,y);x.lineTo(W,y);x.stroke();});
 function line(a,col){
  if(a.length<2)return;
  x.strokeStyle=col;x.lineWidth=2;x.beginPath();
  var n=a.length,sx=W/(MAX-1);
  for(var i=0;i<n;i++){var px=W-(n-1-i)*sx,py=H-(a[i]/100)*H;i?x.lineTo(px,py):x.moveTo(px,py);}
  x.stroke();
 }
 line(gpuH,'#f5a623');line(ramH,'#48c774');line(cpuH,'#4a9eff');
}
async function perf(){
 try{
  var d=await (await fetch('/perf')).json();
  if(d.hist){cpuH=d.hist.cpu||[];ramH=d.hist.ram||[];gpuH=d.hist.gpu||[];}  // server FIFO
  draw();
  var ramPct=d.memTotalMb?Math.round(d.memUsedMb*100/d.memTotalMb):0;
  var peak=(d.cores&&d.cores.length)?Math.max.apply(null,d.cores):d.cpu;
  var h=row('CPU',d.cpu+'%  <span style="color:#8a8">peak core '+peak+'%</span>');
  if(d.gpu!=null)h+=row('GPU',d.gpu+'%'+(d.gpuMhz?'  <span style="color:#8a8">'+d.gpuMhz+' MHz</span>':''));
  h+=row('RAM',d.memUsedMb+' / '+d.memTotalMb+' MB ('+ramPct+'%)');
  if(d.load&&d.load.length)h+=row('Load avg',d.load.join('  '));
  if(d.tempC!=null)h+=row('Temperature',d.tempC.toFixed(1)+' °C');
  document.getElementById('perf').innerHTML=h;
  document.getElementById('perfage').textContent='· live';
 }catch(e){document.getElementById('perfage').textContent='· unavailable';}
}
perf();setInterval(perf,2000);
</script>
</div></body></html>"""
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        private const val TAG = "ha-paneld/http"
        private const val RELEASES_URL = "https://github.com/maxlyth/ha-paneld/releases"
    }
}
