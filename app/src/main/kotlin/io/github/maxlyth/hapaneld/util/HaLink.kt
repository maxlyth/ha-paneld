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
 * The built-in renderer path authenticates with its existing HA access token. Older/external-renderer
 * installations can fall back to the panel's **MQTT username/password** when those credentials are also
 * a real HA account. The device id then comes from the WebSocket command
 * `config/entity_registry/list_for_display` — the SAME command the frontend uses to render entities for
 * **every** user, so it works for **non-admin** accounts too (unlike the admin-only `/api/template`). We find
 * this panel's own entity by its stable panel-id prefix, with its historical friendly-name prefix as a
 * compatibility fallback, and take the entry's `di` (device id).
 *
 * Anonymous brokers and any failure (bad creds, MFA, a non-HA broker login, API unreachable, no matching
 * entity) return null and the link is hidden.
 */
object HaLink {
    private const val TAG = "HaLink"
    private const val JSON = "application/json"
    private const val FORM = "application/x-www-form-urlencoded"

    /** @param base HA origin from zeroconf, e.g. "https://hass.example". @return device-page URL or null. */
    fun resolve(base: String, user: String, pass: String, deviceNames: Collection<String>): String? {
        if (user.isBlank() || pass.isBlank()) return null // anonymous broker → can't auth
        return runCatching {
            val token = login(base, user, pass) ?: return null
            val slugs = deviceNames.map(::slug).filter(String::isNotBlank).distinct()
            val devId = deviceIdViaWs(base, token, slugs)
                ?: run { Log.i(TAG, "no HA entity matching ${slugs.joinToString()}"); return null }
            // Build the link off HA's canonical internal_url (so logging in via the broker host still yields a
            // tidy hass.example link), falling back to the URL we logged in at.
            val linkBase = internalUrl(base, token) ?: base
            "${linkBase.trimEnd('/')}/config/devices/device/$devId".also { Log.i(TAG, "HA device link resolved") }
        }.onFailure { Log.i(TAG, "resolve failed: ${it.message}") }.getOrNull()
    }

    /** Resolve through the built-in renderer's already-authenticated HA connection. The returned link is
     * deliberately based on [base], not an unrelated advertised internal/external URL: the same native
     * endpoint which rendered the panel is the authoritative server and reverse-proxy base path. */
    fun resolveWithAccessToken(base: String, token: String, deviceNames: Collection<String>): String? {
        if (base.isBlank() || token.isBlank()) return null
        return runCatching {
            val normalized = base.trim().trimEnd('/')
            val slugs = deviceNames.map(::slug).filter(String::isNotBlank).distinct()
            val devId = deviceIdViaWs(normalized, token, slugs)
                ?: run { Log.i(TAG, "no HA entity matching ${slugs.joinToString()}"); return null }
            "$normalized/config/devices/device/$devId".also { Log.i(TAG, "native HA device link resolved") }
        }.onFailure { Log.i(TAG, "native resolve failed: ${it.message}") }.getOrNull()
    }

    /** Cache owner for a resolution. It includes panel identity because the HA device can change without
     * the renderer endpoint changing (for example after reprovisioning with another panel id). */
    internal fun resolutionTarget(base: String, panelId: String): String =
        "${base.trim().trimEnd('/')}\u0000${slug(panelId)}"

    /** An access token plus its remaining lifetime in seconds (as HA's /auth/token reports `expires_in`). */
    data class TokenSet(val accessToken: String, val expiresInSec: Long)

    /**
     * Outcome of a refresh-token exchange. The distinction matters on an unattended panel: a
     * [Rejected] means the server terminally refused the unchanged refresh request (invalid/revoked
     * token, wrong required client id, or inactive user), while a [Transient] failure (HA
     * restarting, network blip, timeout, 5xx) says nothing about the token and must never be treated
     * as a revocation — nuking a valid login over a flaky moment leaves a wall panel dead until an
     * admin re-provisions it.
     */
    sealed class Refresh {
        data class Success(val tokens: TokenSet) : Refresh()
        object Rejected : Refresh()
        object Transient : Refresh()
    }

    /**
     * Exchange a refresh token for a fresh access token (OAuth `grant_type=refresh_token`, the same call
     * the HA Companion makes). Classifies failures — see [Refresh]. Blocking HTTP — call it off the main
     * thread (the renderer's JS-bridge thread is fine).
     */
    fun refreshAccessToken(base: String, refreshToken: String, clientId: String = ""): Refresh = try {
        // client_id must match the one the refresh token was issued for. Default = HA origin (the
        // frontend's own client_id); override to reuse a token from another client (e.g. the Companion).
        val cid = clientId.ifBlank { "${base.trimEnd('/')}/" }
        val json = JSONObject(
            post(
                "${base.trimEnd('/')}/auth/token", null,
                "grant_type=refresh_token&refresh_token=${enc(refreshToken)}&client_id=${enc(cid)}", FORM,
            ),
        )
        val access = json.optString("access_token").takeIf { it.isNotBlank() }
        // A 200 without a token is a server oddity, not a revocation — treat as transient.
        if (access == null) Refresh.Transient else Refresh.Success(TokenSet(access, json.optLong("expires_in", 1800L)))
    } catch (e: HttpError) {
        Log.i(TAG, "refresh failed: HTTP ${e.code}")
        // Home Assistant uses 400 for invalid refresh requests, including a wrong required client id,
        // and 403 for an inactive user. These unchanged requests cannot recover by retrying; 5xx from a
        // restart or proxy and 404 from a wrong endpoint remain transient/configuration evidence.
        if (e.code in intArrayOf(400, 401, 403)) Refresh.Rejected else Refresh.Transient
    } catch (e: Exception) {
        Log.i(TAG, "refresh failed: ${e.message}")
        Refresh.Transient
    }

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
     * the device id of the first entity whose entity_id object-part starts with a requested slug. Each entry is
     * compact: `ei` = entity_id, `di` = device id.
     */
    private fun deviceIdViaWs(base: String, token: String, deviceSlugs: Collection<String>): String? = runBlocking {
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
                    if (msg.contains("\"id\":1")) { devId = matchDeviceId(msg, deviceSlugs); return@webSocket }
                }
            }
            devId
        } finally {
            client.close()
        }
    }

    /** Find the `di` of the first entity whose entity_id object-part starts with one of [deviceSlugs]. */
    internal fun matchDeviceId(resp: String, deviceSlugs: Collection<String>): String? {
        val result = JSONObject(resp).opt("result")
        val arr: JSONArray = when (result) {
            is JSONArray -> result
            is JSONObject -> result.optJSONArray("entities") ?: return null
            else -> return null
        }
        val entities = buildList {
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val di = e.optString("di").takeIf { it.isNotBlank() } ?: continue
                val obj = e.optString("ei").substringAfter('.', "")
                add(obj to di)
            }
        }
        // Candidate order is meaningful: stable panel_id first, historical friendly-name fallback second.
        for (slug in deviceSlugs) {
            entities.firstOrNull { (obj, _) -> obj == slug || obj.startsWith(slug + "_") }
                ?.let { return it.second }
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

    /** Non-2xx HTTP response — typed so callers can tell a definitive server refusal from a network fault. */
    class HttpError(val code: Int, method: String) : RuntimeException("$method -> $code")

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
        if (code !in 200..299) throw HttpError(code, method)
        return text
    }
}
