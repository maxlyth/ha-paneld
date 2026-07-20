package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.Config
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal sealed interface HaCurrentUserStatus {
    data class Connected(val displayName: String?) : HaCurrentUserStatus
    data object NotConfigured : HaCurrentUserStatus
    data object Rejected : HaCurrentUserStatus
    data object Unavailable : HaCurrentUserStatus
}

internal fun interface HaCurrentUserTransport {
    suspend fun read(baseUrl: String, accessToken: String): JSONObject
}

internal class HaCurrentUserClient(
    private val auth: HaApiSessionProvider,
    private val transport: HaCurrentUserTransport = KtorHaCurrentUserTransport(),
) {
    constructor(config: Config) : this(DashboardHaApiSessionProvider(config))

    suspend fun status(): HaCurrentUserStatus {
        var session = auth.resolve(false)
        if (session.rejected) return HaCurrentUserStatus.Rejected
        if (session.baseUrl.isBlank() || session.accessToken.isNullOrBlank()) return HaCurrentUserStatus.NotConfigured
        val result = try {
            transport.read(session.baseUrl, checkNotNull(session.accessToken))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HaAuthenticationException) {
            session = auth.resolve(true)
            if (session.rejected || session.accessToken.isNullOrBlank()) return HaCurrentUserStatus.Rejected
            try {
                transport.read(session.baseUrl, checkNotNull(session.accessToken))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: HaAuthenticationException) {
                return HaCurrentUserStatus.Rejected
            } catch (_: Throwable) {
                return HaCurrentUserStatus.Unavailable
            }
        } catch (_: Throwable) {
            return HaCurrentUserStatus.Unavailable
        }
        return HaCurrentUserStatus.Connected(sanitizeName(result.optString("name")))
    }

    private fun sanitizeName(raw: String): String? = raw
        .replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_NAME_CHARS)
        .takeIf(String::isNotBlank)

    private companion object {
        const val MAX_NAME_CHARS = 96
    }
}

private class KtorHaCurrentUserTransport : HaCurrentUserTransport {
    override suspend fun read(baseUrl: String, accessToken: String): JSONObject = withContext(Dispatchers.IO) {
        val client = HttpClient(CIO) { install(WebSockets) { maxFrameSize = MAX_FRAME_BYTES } }
        var socket: DefaultClientWebSocketSession? = null
        try {
            val active = withTimeout(TIMEOUT_MS) {
                client.webSocketSession(io.github.maxlyth.hapaneld.dashboard.EntityFilterProtocol.upstreamWebSocketUrl(baseUrl))
            }
            socket = active
            withTimeout(TIMEOUT_MS) {
                if (active.readJson().optString("type") != "auth_required") {
                    throw HaProtocolException("Home Assistant did not request WebSocket authentication")
                }
                active.send(Frame.Text(JSONObject().put("type", "auth").put("access_token", accessToken).toString()))
                when (active.readJson().optString("type")) {
                    "auth_ok" -> Unit
                    "auth_invalid" -> throw HaAuthenticationException("Home Assistant rejected the access token")
                    else -> throw HaProtocolException("Unexpected Home Assistant authentication response")
                }
                active.send(Frame.Text(JSONObject().put("id", REQUEST_ID).put("type", "auth/current_user").toString()))
                val response = active.readJson()
                if (response.optString("type") != "result" || response.optInt("id") != REQUEST_ID ||
                    !response.optBoolean("success")
                ) throw HaProtocolException("Home Assistant rejected the current-user request")
                response.optJSONObject("result")
                    ?: throw HaProtocolException("Home Assistant returned an invalid current user")
            }
        } finally {
            runCatching { socket?.close() }
            client.close()
        }
    }

    private suspend fun DefaultClientWebSocketSession.readJson(): JSONObject {
        while (true) {
            val frame = incoming.receive()
            if (frame is Frame.Text) return JSONObject(frame.readText())
        }
    }

    private companion object {
        const val REQUEST_ID = 1
        const val TIMEOUT_MS = 15_000L
        const val MAX_FRAME_BYTES = 256L * 1024L
    }
}
