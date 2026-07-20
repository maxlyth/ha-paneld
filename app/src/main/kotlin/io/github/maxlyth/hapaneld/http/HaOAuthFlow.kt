package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.HaAuthOwner
import io.github.maxlyth.hapaneld.HaOAuthAttemptAuthority
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

internal const val HA_OAUTH_CALLBACK_PATH = "/api/v1/ha/oauth/callback"

/** A browser login attempt contains authority, not credentials. It is process-local, short-lived and
 * consumed exactly once before an authorization code is sent back to Home Assistant. */
internal data class HaOAuthAttempt(
    val haUrl: String,
    val panelOrigin: String,
    val clientId: String,
    val redirectUri: String,
    val expectedOwner: HaAuthOwner,
    val expectedEpoch: Long,
)

internal data class HaOAuthStart(val authorizationUrl: String)

internal sealed class HaOAuthClaim {
    data class Claimed(val attempt: HaOAuthAttempt) : HaOAuthClaim()
    object Invalid : HaOAuthClaim()
    object WrongOrigin : HaOAuthClaim()
}

internal class HaOAuthFlow(
    private val nowMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val stateToken: () -> String = ::secureHaOAuthState,
    private val ttlMillis: Long = 10 * 60 * 1_000L,
) {
    private data class Pending(val attempt: HaOAuthAttempt, val expiresAtMillis: Long)

    private val pending = LinkedHashMap<String, Pending>()

    init {
        require(ttlMillis in 1..(60 * 60 * 1_000L))
    }

    @Synchronized
    fun start(haUrl: String, panelOrigin: String, authority: HaOAuthAttemptAuthority): HaOAuthStart {
        purgeExpired()
        // Only the most recently requested login remains usable. The config-owned epoch also blocks
        // an older callback which was claimed before this map could invalidate its state.
        pending.clear()
        val state = generateUniqueState()
        val clientId = "$panelOrigin/"
        val redirectUri = "$panelOrigin$HA_OAUTH_CALLBACK_PATH"
        val attempt = HaOAuthAttempt(
            haUrl,
            panelOrigin,
            clientId,
            redirectUri,
            authority.owner,
            authority.epoch,
        )
        pending[state] = Pending(attempt, nowMillis() + ttlMillis)
        val authorizationUrl = "$haUrl/auth/authorize?" + listOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "state" to state,
        ).joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
        return HaOAuthStart(authorizationUrl)
    }

    /** Consume before token exchange. A wrong-origin callback also burns the disclosed state instead of
     * leaving a valid bearer-like login capability available for a second request. */
    @Synchronized
    fun claim(state: String, panelOrigin: String): HaOAuthClaim {
        if (!validHaOAuthState(state)) return HaOAuthClaim.Invalid
        purgeExpired()
        val found = pending.remove(state) ?: return HaOAuthClaim.Invalid
        return if (found.attempt.panelOrigin == panelOrigin) HaOAuthClaim.Claimed(found.attempt)
        else HaOAuthClaim.WrongOrigin
    }

    @Synchronized
    internal fun pendingCount(): Int {
        purgeExpired()
        return pending.size
    }

    private fun purgeExpired() {
        val now = nowMillis()
        pending.entries.removeAll { it.value.expiresAtMillis <= now }
    }

    private fun generateUniqueState(): String {
        repeat(4) {
            val candidate = stateToken()
            require(validHaOAuthState(candidate)) { "OAuth state generator returned an invalid token" }
            if (candidate !in pending) return candidate
        }
        error("OAuth state generator repeated a live token")
    }
}

internal fun validHaOAuthState(value: String): Boolean =
    value.length in 43..86 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

private fun secureHaOAuthState(): String = ByteArray(32)
    .also(SecureRandom()::nextBytes)
    .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

/** Strictly turn the request Host header into this panel's HTTP origin. The OAuth client and callback
 * must share this exact host and port; accepting path/userinfo/query ambiguity would weaken that bind. */
internal fun panelHttpOrigin(hostHeader: String?, panelPort: Int): String? {
    if (panelPort !in 1..65_535) return null
    val raw = hostHeader?.trim()?.takeIf { it.isNotEmpty() && it.length <= 512 } ?: return null
    if (raw.any { it <= ' ' || it == '\u007f' } || raw.any { it in "/?#@" }) return null
    val uri = runCatching { URI("http://$raw") }.getOrNull() ?: return null
    if (uri.rawAuthority != raw || uri.userInfo != null || uri.rawPath.isNotEmpty() ||
        uri.rawQuery != null || uri.rawFragment != null
    ) return null
    val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()
        ?.takeIf(String::isNotBlank) ?: return null
    val port = when {
        uri.port == -1 -> panelPort
        uri.port in 1..65_535 -> uri.port
        else -> return null
    }
    val authorityHost = if (':' in host) "[$host]" else host
    val portSuffix = if (port == 80) "" else ":$port"
    return "http://$authorityHost$portSuffix"
}
