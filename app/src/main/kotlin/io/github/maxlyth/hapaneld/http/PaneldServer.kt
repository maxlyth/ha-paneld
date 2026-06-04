package io.github.maxlyth.hapaneld.http

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.AudioPlayer
import io.github.maxlyth.hapaneld.Config
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
        val capColor = mapOf("ok" to "#48c774", "degraded" to "#d9a528", "none" to "#d04a3b")
        val capRows = DiagReader.capabilities(appContext).joinToString("\n") { c ->
            val col = capColor[c.status] ?: "#888"
            """<tr><th>${esc(c.name)}</th><td><span style="color:$col">●</span> ${esc(c.note)}</td></tr>"""
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
 .binary .gradedonly{display:none}
 .pbtn{font-size:.8rem;padding:6px 12px;background:#1c1c1c;border-color:#444;cursor:pointer}
 .pbtn.on{background:#2557a7;border-color:#2557a7}
</style></head><body><div class="wrap">
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
<canvas id="smchart" width="600" height="60"
 style="width:100%;max-width:600px;background:#181818;border-radius:8px;display:block;margin-bottom:6px"></canvas>
<div style="font-size:.72rem;color:#8a8;margin-bottom:6px">% of slow screen updates, over time ·
 <span style="color:#48c774">▬</span> snappy under 5% · <span style="color:#d9a528">▬</span> laggy over 15%</div>
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
function drawSm(hist){
 var c=document.getElementById('smchart'),x=c.getContext('2d'),W=c.width,H=c.height;
 x.clearRect(0,0,W,H);
 [[5,'#2a4a32'],[15,'#4a3f1a']].forEach(function(t){var y=H-(t[0]/100)*H;x.strokeStyle=t[1];x.beginPath();x.moveTo(0,y);x.lineTo(W,y);x.stroke();});
 if(!hist||hist.length<2)return;
 var n=hist.length,sx=W/(MAX-1);x.lineWidth=2;x.beginPath();
 for(var i=0;i<n;i++){var px=W-(n-1-i)*sx,py=H-(Math.min(100,hist[i])/100)*H;i?x.lineTo(px,py):x.moveTo(px,py);}
 var last=hist[n-1];x.strokeStyle=last<5?'#48c774':(last<15?'#d9a528':'#d04a3b');x.stroke();
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
  var tp=document.getElementById('topproc');
  if(d.top&&d.top.length){
   var t='<tr><th>Top process</th><th>% total CPU</th></tr>';
   d.top.forEach(function(p){t+='<tr><td style="color:#ccc;font-weight:400">'+p.name+'</td><td>'+p.cpu+'%</td></tr>';});
   tp.innerHTML=t;
  }else tp.innerHTML='<tr><th>Top processes</th><td style="color:#888">needs root (su)</td></tr>';
  var r=d.render,smh=document.getElementById('smhdr'),smt=document.getElementById('smtbl');
  if(r==null){smh.textContent='· needs root';smt.innerHTML=row('Responsiveness','<span style="color:#888">needs root to measure</span>');drawSm([]);}
  else if(r==='noconfig'){smh.textContent='';smt.innerHTML=row('Responsiveness','<span style="color:#888">no Home Assistant app found on this panel</span>');drawSm([]);}
  else if(r.idle){smh.textContent='· idle';smt.innerHTML=row('Responsiveness','<span style="color:#888">dashboard is idle — nothing is being drawn right now</span>');drawSm([]);}
  else{
   drawSm(r.hist);
   var col=r.verdict==='smooth'?'#48c774':(r.verdict==='occasional'?'#d9a528':'#d04a3b');
   var v=r.verdict==='smooth'?'Snappy':(r.verdict==='occasional'?'Sluggish':'Laggy');
   smh.textContent='· '+r.pkg.split('.').pop();
   smt.innerHTML=row('How it feels','<span style="color:'+col+'">●</span> <b>'+v+'</b>')
    +row('Slow screen updates',r.jankPct+'% <span style="color:#888">of the last '+r.frames+' frames</span>')
    +row('Worst response',r.p99+' ms <span style="color:#888">(under ~100 ms feels instant)</span>')
    +row('App too busy to respond',r.slowUi+'× <span style="color:#888">in that window</span>');
  }
  document.getElementById('perfage').textContent='· live';
 }catch(e){document.getElementById('perfage').textContent='· unavailable';}
}
perf();setInterval(perf,2000);
var proxMax=100,proxDrag=false;
function proxDraw(d){
 var c=document.getElementById('proxgauge'),x=c.getContext('2d'),W=c.width,H=c.height;
 x.clearRect(0,0,W,H);
 var gm=Math.max(d.max||0,d.nearRaw||0,d.farRaw||0,d.raw||0,1)*1.1;proxMax=gm;
 function px(v){return Math.max(0,Math.min(W,(v/gm)*W));}
 if(d.calibrated&&d.threshold!=null){
  var tx=px(d.threshold);
  x.fillStyle='rgba(74,158,255,0.13)';
  if(d.nearBelow)x.fillRect(0,0,tx,H);else x.fillRect(tx,0,W-tx,H);
  if(d.margin){x.fillStyle='rgba(245,166,35,0.20)';x.fillRect(px(d.threshold-d.margin),0,px(d.threshold+d.margin)-px(d.threshold-d.margin),H);}
  x.strokeStyle='#f5a623';x.lineWidth=2;x.beginPath();x.moveTo(tx,0);x.lineTo(tx,H);x.stroke();
 }
 if(d.raw!=null){var rx=px(d.raw);x.fillStyle=d.near?'#48c774':'#888';x.beginPath();x.arc(rx,H/2,7,0,7);x.fill();}
}
function proxApply(d){
 document.getElementById('proxbox').className=d.graded?'':'binary'; // hide gauge/slider/sensitivity on binary sensors
 proxDraw(d);
 document.getElementById('proxth').textContent=d.threshold==null?'–':d.threshold.toFixed(1);
 document.getElementById('proxstate').textContent=(d.graded?'· graded sensor':'· binary sensor')+(d.calibrated?' · calibrated':'');
 document.querySelectorAll('.psen').forEach(function(b){b.className='pbtn psen gradedonly'+(b.dataset.s===d.sensitivity?' on':'');});
 var hint=document.getElementById('proxhint');
 if(d.indistinct)hint.textContent='⚠ near and far captures are too close — recapture with a clearer gap.';
 else if(!d.graded)hint.textContent='Binary near/far sensor — nothing to tune. Press Capture near with your hand at the panel, then Capture far, to set which reading means "near".';
 else hint.textContent=d.calibrated?'':'Hold your hand at the panel and press Capture near, then move away and press Capture far.';
}
async function prox(){
 try{
  var d=await (await fetch('/proximity')).json();
  var box=document.getElementById('proxbox'),st=document.getElementById('proxstate');
  if(!d.present){box.style.display='none';st.textContent='· not present';return;}
  box.style.display='block';
  document.getElementById('proxraw').textContent=d.raw==null?'–':d.raw.toFixed(1);
  var nb=document.getElementById('proxnear');nb.textContent=d.near?'NEAR':'FAR';nb.style.color=d.near?'#48c774':'#888';
  if(!proxDrag){var s=document.getElementById('proxslider');s.max=proxMax.toFixed(1);if(d.threshold!=null)s.value=d.threshold;}
  proxApply(d);
 }catch(e){}
}
function proxPost(u,b){fetch(u,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b}).then(function(r){return r.json();}).then(proxApply).catch(function(){});}
function proxCap(s){proxPost('/proximity/capture','step='+s);}
function proxSen(s){proxPost('/proximity/sensitivity','s='+s);}
function proxReset(){proxPost('/proximity/reset','');}
function proxThSet(v){proxDrag=false;proxPost('/proximity/threshold','v='+v);}
(function(){var s=document.getElementById('proxslider');s.addEventListener('mousedown',function(){proxDrag=true;});s.addEventListener('touchstart',function(){proxDrag=true;});})();
prox();setInterval(prox,400);
</script>
</div></body></html>"""
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

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
