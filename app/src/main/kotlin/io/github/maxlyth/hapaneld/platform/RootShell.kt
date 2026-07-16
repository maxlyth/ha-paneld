package io.github.maxlyth.hapaneld.platform

/**
 * The root-command boundary — a thin seam over [io.github.maxlyth.hapaneld.control.Su] so callers can
 * depend on an interface (and be unit-tested with a fake) instead of the concrete su executor. Production
 * injects the `Su` object; tests inject a fake. Behaviour is unchanged; this only adds a test seam.
 */
interface RootShell {
    /** True if any su form works (a `su true` succeeds). */
    fun available(): Boolean

    /** Run [cmd] as root, waiting for completion; true on exit 0. */
    fun run(cmd: String): Boolean

    /** Run [cmd] as root and return its stdout, or null on failure / no su. */
    fun runOutput(cmd: String): String?

    /** Run [cmd] as root and return its raw stdout bytes (e.g. a screenshot), or null. */
    fun runBytes(cmd: String): ByteArray?

    /** Bounded raw-byte variant. Implementations that can enforce the ceiling while streaming should
     * override it; the default still prevents oversized data from escaping test/legacy fakes. */
    fun runBytesBounded(cmd: String, maxBytes: Long): ByteArray? =
        runBytes(cmd)?.takeIf { it.size.toLong() <= maxBytes }

    /** Submit [cmd] as root without waiting (for commands like `reboot` that kill the process).
     *  Returns true only when a root process was successfully started. */
    fun fireAndForget(cmd: String): Boolean
}
