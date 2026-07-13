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

    /**
     * Send a long-running command with a caller-selected read timeout. [DaemonLongResult.NotSubmitted]
     * proves no command reached the daemon; [DaemonLongResult.Indeterminate] means it may still be
     * running, so caller-owned inputs must remain valid.
     */
    fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult

    /** Send one command and read the full binary reply (e.g. a `SCREENCAP` PNG), or null. */
    fun sendBytes(cmd: String): ByteArray?
}

sealed interface DaemonLongResult {
    /** The daemon returned a terminal reply for this command. */
    data class Reply(val value: String) : DaemonLongResult

    /** Socket connection failed before command submission began. */
    data object NotSubmitted : DaemonLongResult

    /** Submission began but EOF, timeout, or I/O failure prevented a terminal reply. */
    data object Indeterminate : DaemonLongResult
}
