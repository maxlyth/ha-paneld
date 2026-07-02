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
 * Scope note: [allowed] stops classic CSRF (a page POSTing to the panel's address) but not active
 * DNS-rebinding (which makes the request genuinely same-origin); [hostAllowed] covers rebinding via a
 * `Host` allowlist. GETs aren't origin-guarded (idempotent + cross-origin reads are CORS-blocked), but
 * they *are* host-guarded, since rebinding also enables reads. The higher-assurance path for an
 * untrusted network remains HA-token auth (security decision 3), deferred under the LAN-trust model.
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

    /**
     * Anti-DNS-rebinding guard: the `Origin` check above is defeated by active DNS-rebinding (the
     * attacker's page rebinds its own name to the panel's IP, so the request becomes genuinely
     * same-origin). The defence is to pin the `Host` header to values that can't be rebound onto the
     * panel: **IP literals** (v4/v6 — reached directly, no DNS name to rebind), `localhost`, and
     * `*.local` (mDNS, LAN-only resolution), plus any operator-configured [allowedNames]. Any other
     * DNS hostname in `Host` is refused. Applies to every method (rebinding also enables reads —
     * `GET /config/export`, `/screenshot`). A missing `Host` is not a rebinding vector, so it passes.
     *
     * [allowedNames] must be lowercased by the caller ([io.github.maxlyth.hapaneld.Config.httpAllowedHosts]).
     */
    fun hostAllowed(host: String?, allowedNames: Set<String>): Boolean {
        val raw = host?.trim()?.ifEmpty { null } ?: return true // no Host header — not a rebinding vector
        val name = hostnameOf(raw).lowercase()
        if (name.isEmpty()) return true
        return isIpLiteral(name) || name == "localhost" || name.endsWith(".local") || name in allowedNames
    }

    /** The hostname part of a `Host` header — strips the port and IPv6 brackets. */
    private fun hostnameOf(host: String): String {
        if (host.startsWith("[")) { // [ipv6]:port
            val end = host.indexOf(']')
            return if (end > 1) host.substring(1, end) else host.removePrefix("[")
        }
        // 2+ colons ⇒ a bare (unbracketed) IPv6 literal, which has no port suffix; keep it whole.
        return if (host.count { it == ':' } >= 2) host else host.substringBefore(':')
    }

    /** True if [hostname] is an IPv4 dotted-quad or an IPv6 literal (both are unrebindable). */
    private fun isIpLiteral(hostname: String): Boolean {
        if (hostname.contains(':')) return true // IPv6
        val octets = hostname.split('.')
        return octets.size == 4 && octets.all { o -> o.toIntOrNull()?.let { it in 0..255 } == true }
    }
}
