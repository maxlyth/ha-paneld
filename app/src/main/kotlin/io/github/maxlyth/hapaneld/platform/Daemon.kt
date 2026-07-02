package io.github.maxlyth.hapaneld.platform

/**
 * The root-helper-daemon boundary — a thin seam over [io.github.maxlyth.hapaneld.util.HelperClient] so
 * callers can depend on an interface (and be unit-tested with a fake) instead of the concrete socket
 * client. Production injects the `HelperClient` object; tests inject a fake. Behaviour is unchanged.
 */
interface Daemon {
    /** True when the daemon answers `PING`. */
    fun available(): Boolean

    /** Send one command; return the daemon's reply line (trimmed), or null if unreachable. */
    fun send(cmd: String): String?

    /** Send one command and read the full binary reply (e.g. a `SCREENCAP` PNG), or null. */
    fun sendBytes(cmd: String): ByteArray?
}
