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

    /** Outcome of [resolve]: a session to reply with (or null = fail closed), and — when a refresh
     *  happened — the (access, epoch-expiry) to persist so the next handshake reuses it. */
    data class Result(val session: Session?, val persist: Pair<String, Long>? = null)

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
     * @param refresher    (url, refreshToken) -> new token set, or null on failure.
     */
    fun resolve(
        url: String,
        access: String,
        refreshToken: String,
        expiryEpochSec: Long,
        nowSec: Long,
        refresher: (String, String) -> HaLink.TokenSet?,
    ): Result {
        if (url.isBlank()) return Result(null)
        // Static model: no refresh token → the access token is long-lived, hand it back as-is.
        if (refreshToken.isBlank()) {
            return if (access.isBlank()) Result(null)
            else Result(Session(access, STATIC_TTL_SEC))
        }
        // Refresh model: reuse the cached access token while it has comfortable life left.
        val ttl = expiryEpochSec - nowSec
        if (access.isNotBlank() && ttl > REFRESH_SKEW_SEC) return Result(Session(access, ttl))
        // Expired / near-expiry / unknown → mint a new one.
        val fresh = refresher(url, refreshToken)
        if (fresh != null) {
            return Result(Session(fresh.accessToken, fresh.expiresInSec), fresh.accessToken to (nowSec + fresh.expiresInSec))
        }
        // Refresh failed: fall back to a still-usable cached token, else fail closed.
        return if (access.isNotBlank() && ttl > 0) Result(Session(access, ttl)) else Result(null)
    }

    /** Android glue: resolve against [config], persist a refreshed token, and return the session. */
    fun forConfig(config: Config, nowSec: Long = System.currentTimeMillis() / 1000): Session? {
        val r = resolve(
            config.haUrl, config.haToken, config.haRefreshToken, config.haTokenExpiry, nowSec,
            HaLink::refreshAccessToken,
        )
        r.persist?.let { (access, expiry) -> config.setHaRefreshedToken(access, expiry) }
        return r.session
    }
}
