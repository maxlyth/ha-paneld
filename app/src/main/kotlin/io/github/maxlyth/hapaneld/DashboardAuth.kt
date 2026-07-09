package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.util.HaLink

/**
 * Decides which access token the built-in renderer hands the HA frontend, and when to refresh — the
 * panel-side of the external-auth contract, matching how the HA Companion's `ensureValidSession` works.
 *
 * Two models, chosen by whether a refresh token is present:
 *  - **static** (no refresh token): [access] is a long-lived token; hand it back with a long advertised
 *    life. Simplest; the token is a standing credential on the panel.
 *  - **refresh** (refresh token present): [access] is a short-lived token. Reuse it while it has
 *    comfortable life left, otherwise mint a new one from the refresh token — so no 10-year token lives
 *    on the panel and the credential is revocable by removing the refresh token in HA.
 *
 * [resolve] is pure and fully unit-testable: it takes the current clock and a `refresher` and returns
 * the session to reply with plus an optional (access, expiry) pair the caller must persist. The Android
 * glue (reading [Config], persisting, calling [HaLink]) lives in [forConfig].
 */
object DashboardAuth {

    /** The reply material for one external-auth handshake. */
    data class Session(val accessToken: String, val expiresInSec: Long)

    /** Outcome of [resolve]: a session to reply with (or null = fail closed), when a refresh happened
     *  the (access, epoch-expiry) to persist so the next handshake reuses it, and [revoked] when the
     *  server *definitively* refused the refresh token — the caller should surface that (latch/warn)
     *  rather than retry, because no amount of retrying revives a revoked credential. A transient
     *  refresh failure never sets [revoked]. */
    data class Result(val session: Session?, val persist: Pair<String, Long>? = null, val revoked: Boolean = false)

    /** Comfortable life a cached access token must have left to be reused rather than refreshed. */
    const val REFRESH_SKEW_SEC = 60L

    /** Life advertised for a static long-lived token (~10 years) — the frontend only uses it to know
     *  when to ask again, so a large value means it never re-asks within a session. */
    const val STATIC_TTL_SEC = 315_360_000L

    /**
     * @param url          HA base URL (blank => no renderer configured => null session).
     * @param access       current access token (static LLAT, or the last short-lived token).
     * @param refreshToken OAuth refresh token, or blank for the static model.
     * @param expiryEpochSec epoch-seconds expiry of [access] (refresh model); 0 => unknown.
     * @param nowSec       current epoch seconds.
     * @param force        the frontend's force flag: bypass the cached token (it was just rejected) and
     *                     refresh; on a refresh failure, fail closed rather than re-hand the dead token.
     * @param refresher    (url, refreshToken) -> classified refresh outcome (see [HaLink.Refresh]).
     */
    fun resolve(
        url: String,
        access: String,
        refreshToken: String,
        expiryEpochSec: Long,
        nowSec: Long,
        force: Boolean,
        refresher: (String, String) -> HaLink.Refresh,
    ): Result {
        if (url.isBlank()) return Result(null)
        // Static model: no refresh token → the access token is long-lived, hand it back as-is. (Nothing
        // to refresh, so `force` can't produce a different token — the LLAT is all there is.)
        if (refreshToken.isBlank()) {
            return if (access.isBlank()) Result(null)
            else Result(Session(access, STATIC_TTL_SEC))
        }
        // Refresh model: reuse the cached access token while it has comfortable life left — unless the
        // frontend forced a refresh (the cached token was just rejected server-side).
        val ttl = expiryEpochSec - nowSec
        if (!force && access.isNotBlank() && ttl > REFRESH_SKEW_SEC) return Result(Session(access, ttl))
        // Expired / near-expiry / unknown / forced → mint a new one.
        return when (val fresh = refresher(url, refreshToken)) {
            is HaLink.Refresh.Success ->
                Result(
                    Session(fresh.tokens.accessToken, fresh.tokens.expiresInSec),
                    fresh.tokens.accessToken to (nowSec + fresh.tokens.expiresInSec),
                )
            // Definitive server refusal: the refresh token is revoked. Fail closed AND say so, so the
            // renderer can latch a clear "token revoked" state instead of retry-looping forever.
            is HaLink.Refresh.Rejected -> Result(null, revoked = true)
            // Transient (HA down / network blip): says nothing about the token. On a forced refresh the
            // cached token was rejected — re-handing it just loops, so fail closed (but NOT revoked — the
            // frontend will re-ask and a recovered network can still succeed). On a non-forced
            // near-expiry refresh, reuse the cached token while it has any life left.
            is HaLink.Refresh.Transient ->
                if (!force && access.isNotBlank() && ttl > 0) Result(Session(access, ttl)) else Result(null)
        }
    }

    /** Android glue: resolve against [config], persist a refreshed token, and return the full result
     *  (the caller reads `.session` for the reply and `.revoked` for the latch signal). */
    fun forConfig(config: Config, nowSec: Long = System.currentTimeMillis() / 1000, force: Boolean = false): Result {
        val r = resolve(
            config.haUrl, config.haToken, config.haRefreshToken, config.haTokenExpiry, nowSec, force,
            { url, refresh -> HaLink.refreshAccessToken(url, refresh, config.haClientId) },
        )
        r.persist?.let { (access, expiry) -> config.setHaRefreshedToken(access, expiry) }
        return r
    }
}
