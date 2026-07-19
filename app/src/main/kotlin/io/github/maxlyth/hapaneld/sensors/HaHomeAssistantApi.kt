package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.DashboardAuth
import io.github.maxlyth.hapaneld.dashboard.EntityFilterProtocol
import io.github.maxlyth.hapaneld.util.BoundedStreams
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class HaApiSession(
    val baseUrl: String,
    val accessToken: String?,
    val rejected: Boolean = false,
)

internal fun interface HaApiSessionProvider {
    fun resolve(force: Boolean): HaApiSession
}

/** Reuses the renderer's refresh-safe credential owner instead of maintaining another token cache. */
internal class DashboardHaApiSessionProvider(private val config: Config) : HaApiSessionProvider {
    override fun resolve(force: Boolean): HaApiSession {
        val expectedUrl = config.haUrl.trim().trimEnd('/')
        val result = DashboardAuth.forConfig(
            config = config,
            force = force,
            stillCurrent = { config.haUrl.trim().trimEnd('/') == expectedUrl },
        )
        return HaApiSession(expectedUrl, result.session?.accessToken, result.rejected)
    }
}

internal sealed interface HaSocketMessage {
    data class State(val json: JSONObject) : HaSocketMessage
    data class Pong(val id: Int) : HaSocketMessage
    data object SourceMissing : HaSocketMessage
    data object Other : HaSocketMessage
}

internal interface HaTriggerConnection {
    suspend fun receive(): HaSocketMessage
    suspend fun ping(id: Int)
    suspend fun close()
}

internal interface HaAmbientTransport {
    suspend fun subscribe(baseUrl: String, accessToken: String, entityId: String): HaTriggerConnection
    suspend fun state(baseUrl: String, accessToken: String, entityId: String): JSONObject?
    suspend fun states(baseUrl: String, accessToken: String): JSONArray
    suspend fun config(baseUrl: String, accessToken: String): JSONObject
}

internal class HaAuthenticationException(message: String) : RuntimeException(message)

internal class HaProtocolException(message: String) : RuntimeException(message)

internal class KtorHaAmbientTransport : HaAmbientTransport {
    override suspend fun subscribe(
        baseUrl: String,
        accessToken: String,
        entityId: String,
    ): HaTriggerConnection = withContext(Dispatchers.IO) {
        val client = HttpClient(CIO) {
            install(WebSockets) { maxFrameSize = MAX_WS_FRAME_BYTES }
        }
        var socket: DefaultClientWebSocketSession? = null
        try {
            val active = withTimeout(CONNECT_TIMEOUT_MS) {
                client.webSocketSession(EntityFilterProtocol.upstreamWebSocketUrl(baseUrl))
            }
            socket = active
            withTimeout(AUTH_TIMEOUT_MS) {
                val required = active.readJson()
                if (required.optString("type") != "auth_required") {
                    throw HaProtocolException("Home Assistant did not request WebSocket authentication")
                }
                active.send(Frame.Text(JSONObject().put("type", "auth").put("access_token", accessToken).toString()))
                when (active.readJson().optString("type")) {
                    "auth_ok" -> Unit
                    "auth_invalid" -> throw HaAuthenticationException("Home Assistant rejected the access token")
                    else -> throw HaProtocolException("Unexpected Home Assistant authentication response")
                }
            }

            val command = HaAmbientLuxProtocol.subscribeTrigger(entityId, SUBSCRIPTION_ID)
            active.send(Frame.Text(command.toString()))
            withTimeout(SUBSCRIBE_TIMEOUT_MS) {
                repeat(MAX_HANDSHAKE_FRAMES) {
                    val response = active.readJson()
                    if (response.optString("type") == "result" && response.optInt("id") == SUBSCRIPTION_ID) {
                        if (!response.optBoolean("success")) {
                            val message = response.optJSONObject("error")?.optString("message")
                                ?.take(MAX_ERROR_CHARS).orEmpty()
                            throw HaProtocolException(message.ifBlank { "Home Assistant rejected the state trigger" })
                        }
                        return@withTimeout
                    }
                }
                throw HaProtocolException("Home Assistant did not confirm the state trigger")
            }
            KtorHaTriggerConnection(client, active)
        } catch (error: Throwable) {
            runCatching { socket?.close() }
            client.close()
            throw error
        }
    }

    override suspend fun state(baseUrl: String, accessToken: String, entityId: String): JSONObject? =
        restGet(baseUrl, accessToken, "/api/states/$entityId", MAX_STATE_BYTES, missingIsNull = true)
            ?.let(::JSONObject)

    override suspend fun states(baseUrl: String, accessToken: String): JSONArray =
        JSONArray(checkNotNull(restGet(baseUrl, accessToken, "/api/states", MAX_STATES_BYTES)))

    override suspend fun config(baseUrl: String, accessToken: String): JSONObject =
        JSONObject(checkNotNull(restGet(baseUrl, accessToken, "/api/config", MAX_CONFIG_BYTES)))

    private suspend fun restGet(
        baseUrl: String,
        accessToken: String,
        path: String,
        maxBytes: Long,
        missingIsNull: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl.trim().trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = HTTP_CONNECT_TIMEOUT_MS
            readTimeout = HTTP_READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            when {
                code == HttpURLConnection.HTTP_NOT_FOUND && missingIsNull -> null
                code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN ->
                    throw HaAuthenticationException("Home Assistant rejected the REST access token")
                code !in 200..299 -> throw HaProtocolException("Home Assistant REST request failed (HTTP $code)")
                else -> connection.inputStream.use { input ->
                    String(BoundedStreams.readBytes(input, maxBytes), Charsets.UTF_8)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private class KtorHaTriggerConnection(
        private val client: HttpClient,
        private val socket: DefaultClientWebSocketSession,
    ) : HaTriggerConnection {
        override suspend fun receive(): HaSocketMessage {
            while (true) {
                val frame = socket.incoming.receive()
                if (frame !is Frame.Text) continue
                val json = JSONObject(frame.readText())
                return when (json.optString("type")) {
                    "event" -> HaAmbientLuxProtocol.triggerState(json)?.let(HaSocketMessage::State)
                        ?: HaSocketMessage.SourceMissing
                    "pong" -> HaSocketMessage.Pong(json.optInt("id", -1))
                    else -> HaSocketMessage.Other
                }
            }
        }

        override suspend fun ping(id: Int) {
            socket.send(Frame.Text(JSONObject().put("id", id).put("type", "ping").toString()))
        }

        override suspend fun close() {
            runCatching { socket.close() }
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
        const val SUBSCRIPTION_ID = 1
        const val MAX_HANDSHAKE_FRAMES = 8
        const val MAX_ERROR_CHARS = 240
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val AUTH_TIMEOUT_MS = 15_000L
        const val SUBSCRIBE_TIMEOUT_MS = 15_000L
        const val HTTP_CONNECT_TIMEOUT_MS = 8_000
        const val HTTP_READ_TIMEOUT_MS = 8_000
        const val MAX_WS_FRAME_BYTES = 2L * 1024L * 1024L
        const val MAX_STATE_BYTES = 256L * 1024L
        const val MAX_CONFIG_BYTES = 256L * 1024L
        const val MAX_STATES_BYTES = 64L * 1024L * 1024L
    }
}
