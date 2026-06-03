package io.github.maxlyth.hapaneld.http

import android.util.Log
import io.github.maxlyth.hapaneld.AudioPlayer
import io.github.maxlyth.hapaneld.Config
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Ktor CIO HTTP command surface on :8888. v0.1.0 exposes the TTS-announce contract that the
 * bash reference implementation (`packages/panel_audio_receiver`) established, so the HA-side
 * `pyscript.tts_announce` POST is a drop-in swap with no contract change.
 *
 * Contract (matches the bash receiver):
 *   POST /play   body contains a URL (raw or `{"url":"…"}`) -> 200 "playing", play in background.
 *                no URL -> 400 "no-url".
 *   GET  /health -> 200 with version + panel id.
 */
class PaneldServer(
    private val config: Config,
    private val cacheDir: File,
    private val scope: CoroutineScope,
) {
    private val urlRegex = Regex("""https?://[^\s"']+""")
    // Stored as a stop lambda over a type-inferred server local, so we never have to name Ktor's
    // EmbeddedServer<TEngine, TConfiguration> generic type (which shifts between Ktor versions).
    private var stopServer: (() -> Unit)? = null

    fun start() {
        val server = embeddedServer(CIO, port = config.httpPort) {
            routing {
                get("/health") {
                    call.respondText("ha-paneld ${Config.VERSION} panel=${config.panelId}\n")
                }
                post("/play") {
                    val body = call.receiveText()
                    val url = urlRegex.find(body)?.value
                    if (url == null) {
                        call.respondText("no-url\n", status = io.ktor.http.HttpStatusCode.BadRequest)
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

    companion object {
        private const val TAG = "ha-paneld/http"
    }
}
