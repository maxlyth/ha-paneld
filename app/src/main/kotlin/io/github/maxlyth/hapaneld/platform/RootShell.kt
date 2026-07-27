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

    /** Run exactly one command attempt, waiting at most [timeoutMs]. Implementations must not retry
     * [cmd] after an ambiguous timeout. The default preserves test/legacy implementations. */
    fun runSingleAttempt(cmd: String, timeoutMs: Long = 5_000L): Boolean = run(cmd)

    /** Run [cmd] as root and return its stdout, or null on failure / no su. */
    fun runOutput(cmd: String): String?

    /** Bounded diagnostic output on a lane independent of interactive hardware control. Production
     * implementations must enforce [timeoutMs]; the default keeps test/legacy fakes compatible. */
    fun runOutputIsolatedBounded(cmd: String, maxBytes: Long, timeoutMs: Long = 10_000L): String? =
        runOutput(cmd)?.takeIf { it.toByteArray(Charsets.UTF_8).size.toLong() <= maxBytes }

    /** Run [cmd] as root and return its raw stdout bytes (e.g. a screenshot), or null. */
    fun runBytes(cmd: String): ByteArray?

    /** Bounded raw-byte variant. Implementations that can enforce the ceiling while streaming should
     * override it; the default still prevents oversized data from escaping test/legacy fakes. */
    fun runBytesBounded(cmd: String, maxBytes: Long): ByteArray? =
        runBytes(cmd)?.takeIf { it.size.toLong() <= maxBytes }

    /** Submit [cmd] as root without waiting (for commands like `reboot` that kill the process).
     *  Returns true only when a root process was successfully started. */
    fun fireAndForget(cmd: String): Boolean

    /** Typed sysfs boundary for profile-selected hardware nodes. Callers never compose shell text. */
    fun listSysfs(path: String): String? {
        require(safeSysfsPath(path))
        return runOutput("ls $path 2>/dev/null")
    }

    fun readSysfs(path: String): String? {
        require(safeSysfsPath(path))
        return runOutput("cat $path 2>/dev/null")
    }

    fun writeSysfs(path: String, value: String): Boolean {
        require(safeSysfsPath(path))
        require(value.matches(Regex("[A-Za-z0-9_-]{1,32}")))
        return run("printf '%s' '$value' > $path")
    }

    fun prepareOutputGpio(gpio: Int): Boolean {
        require(gpio in 0..4095)
        val dir = "/sys/class/gpio/gpio$gpio"
        return runOutput(
            "{ [ -e $dir ] || printf '%s' '$gpio' > /sys/class/gpio/export 2>/dev/null; } && " +
                "[ -e $dir/direction ] && direction=\$(cat $dir/direction 2>/dev/null) && " +
                "{ [ \"\$direction\" = out ] || printf '%s' out > $dir/direction 2>/dev/null; } && " +
                "[ \"\$(cat $dir/direction 2>/dev/null)\" = out ] && [ -w $dir/value ] && printf ready",
        )?.trim() == "ready"
    }

    private fun safeSysfsPath(path: String): Boolean =
        path.startsWith("/sys/") && path.length <= 256 && path.matches(Regex("/[A-Za-z0-9_./-]+"))
}
