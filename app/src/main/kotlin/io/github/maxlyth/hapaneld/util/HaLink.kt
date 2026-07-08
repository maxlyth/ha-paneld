package io.github.maxlyth.hapaneld.util

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Best-effort resolution of this panel's Home Assistant **device-settings URL**, so the info page can offer
 * a one-click "Open in Home Assistant". HA's device-registry id is random (not derivable) and never reported
 * back over MQTT, so it must be read from HA's API.
 *
 * We authenticate with the panel's **MQTT username/password** (with the built-in Mosquitto add-on these are
 * usually a real HA account). The device id then comes from the WebSocket command
 * `config/entity_registry/list_for_display` — the SAME command the frontend uses to render entities for
 * **every** user, so it works for **non-admin** accounts too (unlike the admin-only `/api/template`). We find
 * this panel's own entity by its entity_id prefix (the device-name slug) and take its `di` (device id).
 *
 * Anonymous brokers and any failure (bad creds, MFA, a non-HA broker login, API unreachable, no matching
 * entity) return null and the link is hidden.
 */
object HaLink {
    private const val TAG = "HaLink"
    private const val JSON = "application/json"
    private const val FORM = "application/x-www-form-urlencoded"

    /** @param base HA origin from zeroconf, e.g. "https://hass.example". @return device-page URL or null. */
    fun resolve(base: String, user: String, pass: String, deviceName: String): String? {
        if (user.isBlank() || pass.isBlank()) return null // anonymous broker → can't auth
        return runCatching {
            val token = login(base, user, pass) ?: return null
            val slug = slug(deviceName)
            val devId = deviceIdViaWs(base, token, slug) ?: run { Log.i(TAG, "no HA entity matching '$slug'"); return null }
            // Build the link off HA's canonical internal_url (so logging in via the broker host still yields a
            // tidy hass.example link), falling back to the URL we logged in at.
            val linkBase = internalUrl(base, token) ?: base
            "${linkBase.trimEnd('/')}/config/devices/device/$devId".also { Log.i(TAG, "HA device link resolved") }
        }.onFailure { Log.i(TAG, "resolve failed: ${it.message}") }.getOrNull()
    }

    /** An access token plus its remaining lifetime in seconds (as HA's /auth/token reports `expires_in`). */
    data class TokenSet(val accessToken: String, val expiresInSec: Long)

    /**
     * Exchange a refresh token for a fresh access token (OAuth `grant_type=refresh_token`, the same call
     * the HA Companion makes). Returns null on any failure (revoked/invalid refresh token, HA
     * unreachable) so the caller can fall back to a still-valid cached token or fail closed. Blocking
     * HTTP — call it off the main thread (the renderer's JS-bridge thread is fine).
     */
    fun refreshAccessToken(base: String, refreshToken: String, clientId: String = ""): TokenSet? = runCatching {
        // client_id must match the one the refresh token was issued for. Default = HA origin (the
        // frontend's own client_id); override to reuse a token from another client (e.g. the Companion).
        val cid = clientId.ifBlank { "${base.trimEnd('/')}/" }
        val json = JSONObject(
            post(
                "${base.trimEnd('/')}/auth/token", null,
                "grant_type=refresh_token&refresh_token=${enc(refreshToken)}&client_id=${enc(cid)}", FORM,
            ),
        )
        val access = json.optString("access_token").takeIf { it.isNotBlank() } ?: return@runCatching null
        TokenSet(access, json.optLong("expires_in", 1800L))
    }.onFailure { Log.i(TAG, "refresh failed: ${it.message}") }.getOrNull()

    /** HA frontend login flow with username/password → short-lived access token, or null. */
    private fun login(base: String, user: String, pass: String): String? {
        val cid = "$base/" // client_id = HA's own origin (the frontend does the same), so no extra fetch
        val flow = JSONObject(
            post(
                "$base/auth/login_flow", null,
                JSONObject().put("client_id", cid)
                    .put("handler", JSONArray().put("homeassistant").put(JSONObject.NULL))
                    .put("redirect_uri", cid).toString(),
                JSON,
            ),
        ).getString("flow_id")
        val code = JSONObject(
            post(
                "$base/auth/login_flow/$flow", null,
                JSONObject().put("client_id", cid).put("username", user).put("password", pass).toString(), JSON,
            ),
        ).optString("result")
        if (code.isBlank()) { Log.i(TAG, "login: no code (MFA / invalid creds / not an HA user)"); return null }
        return JSONObject(
            post("$base/auth/token", null, "grant_type=authorization_code&code=${enc(code)}&client_id=${enc(cid)}", FORM),
        ).getString("access_token")
    }

    /**
     * Read `config/entity_registry/list_for_display` over the WebSocket API (non-admin-accessible) and return
     * the device id of the first entity whose entity_id object-part starts with [deviceSlug]. Each entry is
     * compact: `ei` = entity_id, `di` = device id.
     */
    private fun deviceIdViaWs(base: String, token: String, deviceSlug: String): String? = runBlocking {
        val wsUrl = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/websocket"
        val client = HttpClient(CIO) { install(WebSockets) { maxFrameSize = Long.MAX_VALUE } }
        try {
            var devId: String? = null
            client.webSocket(wsUrl) {
                (incoming.receive() as? Frame.Text)?.readText() // auth_required
                send(Frame.Text(JSONObject().put("type", "auth").put("access_token", token).toString()))
                if (!(incoming.receive() as Frame.Text).readText().contains("auth_ok")) return@webSocket
                send(Frame.Text("""{"id":1,"type":"config/entity_registry/list_for_display"}"""))
                // Read frames until the id:1 result (skip any interleaved events/pongs).
                repeat(8) {
                    val msg = (incoming.receive() as? Frame.Text)?.readText() ?: return@repeat
                    if (msg.contains("\"id\":1")) { devId = matchDeviceId(msg, deviceSlug); return@webSocket }
                }
            }
            devId
        } finally {
            client.close()
        }
    }

    /** Find the `di` of the first entity whose entity_id object-part starts with [deviceSlug]. */
    private fun matchDeviceId(resp: String, deviceSlug: String): String? {
        val result = JSONObject(resp).opt("result")
        val arr: JSONArray = when (result) {
            is JSONArray -> result
            is JSONObject -> result.optJSONArray("entities") ?: return null
            else -> return null
        }
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val di = e.optString("di").takeIf { it.isNotBlank() } ?: continue
            val obj = e.optString("ei").substringAfter('.', "")
            if (obj == deviceSlug || obj.startsWith(deviceSlug + "_")) return di
        }
        return null
    }

    /** Slugify a device name the way HA derives entity_ids (lowercase, non-alphanumerics → underscore). */
    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    /** HA's canonical base from /api/config (internal_url, else external_url); null if unavailable. */
    private fun internalUrl(base: String, token: String): String? = runCatching {
        val c = JSONObject(get("$base/api/config", token))
        c.optString("internal_url").ifBlank { c.optString("external_url") }.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun post(url: String, token: String?, body: String, ctype: String) = req(url, "POST", token, body, ctype)
    private fun get(url: String, token: String?) = req(url, "GET", token, null, null)

    private fun req(url: String, method: String, token: String?, body: String?, ctype: String?): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 5000; readTimeout = 5000
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) { doOutput = true; ctype?.let { setRequestProperty("Content-Type", it) } }
        }
        body?.let { c.outputStream.use { os -> os.write(it.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        c.disconnect()
        if (code !in 200..299) throw RuntimeException("$method -> $code")
        return text
    }
}
