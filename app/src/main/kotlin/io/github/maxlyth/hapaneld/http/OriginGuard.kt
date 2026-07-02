package io.github.maxlyth.hapaneld.http

/**
 * Cross-origin (CSRF) guard for the unauthenticated `:8888` control surface.
 *
 * The surface is LAN-source-gated but has no token (the turnkey-UX / LAN-trust model — see
 * `docs/architecture/security.md`). The realistic residual threat is *browser-mediated*: a person on
 * the LAN visits a malicious web page, whose JavaScript silently POSTs to the panel's IP to drive a
 * state-changing endpoint — e.g. `POST /config` to repoint MQTT (entity takeover), `/action` to
 * reboot, or `/play` to inject audio. The cross-origin *read* is already blocked by the browser's
 * same-origin policy (no permissive CORS headers are sent), but the *side effect* of a CSRF write
 * still lands.
 *
 * So we refuse a **state-changing** request whose `Origin` (or, as a fallback, `Referer`) is present
 * and does **not** match the request's own `Host`:
 *  - The panel's own web UI issues same-origin `fetch`es (`Origin` == this panel) → allowed.
 *  - Non-browser API clients (curl, HA `rest_command`, monitors) send no `Origin`/`Referer` → allowed;
 *    that's the LAN API contract, and browsers always attach `Origin` on cross-origin writes.
 *  - A malicious cross-origin page carries its own `Origin` → refused.
 *
 * Scope note: this stops classic CSRF (a page POSTing to the panel's address). It does **not** stop
 * active DNS-rebinding (which makes the request genuinely same-origin) — that needs a `Host`
 * allowlist, tracked separately, and the higher-assurance path is HA-token auth (security decision 3).
 * GETs are not guarded: they're idempotent, and cross-origin reads are already CORS-blocked.
 */
object OriginGuard {
    private val MUTATING = setOf("POST", "PUT", "PATCH", "DELETE")

    fun isStateChanging(method: String): Boolean = method.uppercase() in MUTATING

    /**
     * True = allow the request. [origin] / [referer] / [host] are the raw header values (any may be
     * null). A state-changing request is refused only when a browser origin is present and its
     * `host[:port]` differs from [host].
     */
    fun allowed(method: String, origin: String?, referer: String?, host: String?): Boolean {
        if (!isStateChanging(method)) return true
        val src = origin?.takeIf { it.isNotBlank() } ?: referer?.takeIf { it.isNotBlank() } ?: return true
        val srcAuthority = authorityOf(src) ?: return false // present-but-unparseable origin → refuse
        val hostAuthority = host?.trim()?.ifEmpty { null } ?: return false // mutation with no Host → refuse
        return srcAuthority.equals(hostAuthority, ignoreCase = true)
    }

    /** Extract `host[:port]` from an Origin (`scheme://host[:port]`) or a Referer (full URL). */
    private fun authorityOf(url: String): String? {
        val afterScheme = url.substringAfter("://", "").ifEmpty { return null }
        return afterScheme.substringBefore('/').substringBefore('?').ifEmpty { null }
    }
}
