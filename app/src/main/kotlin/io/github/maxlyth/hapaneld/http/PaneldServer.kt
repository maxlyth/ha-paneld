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
    private val onConfig: (panelId: String, broker: String, user: String, password: String?) -> Unit,
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
                post("/config") {
                    val p = call.receiveParameters()
                    val slug = p["panel_id"].orEmpty().lowercase()
                        .replace(Regex("[^a-z0-9]+"), "_").trim('_')
                    if (slug.isEmpty()) {
                        call.respondText("invalid panel_id\n", status = HttpStatusCode.BadRequest)
                        return@post
                    }
                    val broker = p["mqtt_broker"].orEmpty().trim()
                    val user = p["mqtt_user"].orEmpty().trim()
                    // Blank password field => keep the stored one (the form never echoes it).
                    val password = p["mqtt_password"].orEmpty().ifEmpty { null }
                    onConfig(slug, broker, user, password)
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
            "<tr><th>${esc(k)}</th><td>${esc(v)}</td></tr>"
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
<h2>Configuration</h2>
<form method="post" action="/config">
 <label>Panel id
  <input name="panel_id" value="$pid" pattern="[a-z0-9_]+" title="lowercase letters, digits, underscore" required></label>
 <label>MQTT broker
  <input name="mqtt_broker" value="${esc(config.mqttBroker)}" placeholder="tcp://192.168.1.10:1883"></label>
 <label>MQTT username
  <input name="mqtt_user" value="${esc(config.mqttUser)}" placeholder="(optional)" autocomplete="off"></label>
 <label>MQTT password
  <input name="mqtt_password" type="password" value="" placeholder="(unchanged)" autocomplete="new-password"></label>
 <button type="submit">Save</button>
</form>
<p class="note">Leave the broker blank to run HTTP/TTS-only (MQTT disabled). The password field is
never shown — leave it blank to keep the current one. Saving reconnects MQTT and re-publishes Home
Assistant discovery; changing the panel id may leave the old device in HA to remove manually.
Served over plain HTTP on the LAN.</p>
</div></body></html>"""
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        private const val TAG = "ha-paneld/http"
    }
}
