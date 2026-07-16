package io.github.maxlyth.hapaneld.control

import android.util.Log
import io.github.maxlyth.hapaneld.platform.RootShell
import io.github.maxlyth.hapaneld.util.BoundedStreams
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.Writer
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Root command execution.
 *
 * Two su syntaxes across the fleet: toolbox `su -c '<cmd>'` (Sonoff PX30) and Android `su 0 sh -c
 * '<cmd>'` (Tuya TPA10 userdebug). A working form is cached; a negative probe is retried by later
 * one-shot operations because the root manager may become ready after boot. Graceful: returns
 * false/null if no su works (a panel without root just loses the root-gated capabilities).
 *
 * **Persistent shell (0.8.3).** [run]/[runOutput] are piped into a single long-lived root shell rather
 * than forking `su` per call. A fresh `su` fork+auth costs ~200–300 ms, which made the navbar's
 * Back/Recents (root `input keyevent`) feel unresponsive. The shell is opened lazily, reused, and each
 * command is wrapped with a sentinel so its stdout and exit code are recovered over the one pipe. If the
 * shell is absent, has died, or a command wedges past [CMD_TIMEOUT_MS], it transparently falls back to a
 * per-call `su` exec — **never worse than the pre-0.8.3 behaviour**. All entry points are `@Synchronized`,
 * so the single pipe is strictly one command at a time.
 *
 * [fireAndForget] (e.g. `reboot`) and the form probe always use one-shot execs — a `reboot` must not be
 * fed into the shared shell, and the probe is what discovers/caches the form the shell is opened with.
 */
object Su : RootShell {
    private const val TAG = "ha-paneld/su"
    private const val SENTINEL = "__hapaneld_done__"
    private const val CMD_TIMEOUT_MS = 5000L

    // A successful dialect is sticky. NONE_LAST_PROBE records diagnostics only; it must not suppress
    // later one-shot probes because root-manager readiness can change during the process lifetime.
    @Volatile
    private var form: Int = SuFormPolicy.UNPROBED

    private var shell: ShellHandle? = null

    private class ShellHandle(
        val process: Process,
        val stdin: Writer,
        val stdout: BufferedReader,
        val io: ExecutorService,
    )

    private fun argvOneShot(f: Int, cmd: String): Array<String> = when (f) {
        0 -> arrayOf("su", "-c", cmd)
        else -> arrayOf("su", "0", "sh", "-c", cmd)
    }

    private fun argvShell(f: Int): Array<String> = when (f) {
        0 -> arrayOf("su")
        else -> arrayOf("su", "0", "sh")
    }

    /** Run [cmd] as root, waiting for completion. Returns true on exit 0. */
    @Synchronized
    override fun run(cmd: String): Boolean {
        piped(cmd)?.let { return it.second == 0 }
        return oneShotRun(cmd)
    }

    /** Run [cmd] as root and return its stdout, or null if no su form works / it exits non-zero. */
    @Synchronized
    override fun runOutput(cmd: String): String? {
        piped(cmd)?.let { return if (it.second == 0) it.first else null }
        return oneShotOutput(cmd)
    }

    /** Fire [cmd] as root without waiting (for commands like `reboot` that kill the process). Always a
     *  one-shot — never sent into the shared persistent shell (it would take the shell down with it). */
    override fun fireAndForget(cmd: String): Boolean {
        val forms = SuFormPolicy.candidates(form)
        for (f in forms) {
            try {
                Runtime.getRuntime().exec(argvOneShot(f, cmd))
                return true
            } catch (e: Exception) {
                Log.d(TAG, "su fire form $f failed", e)
            }
        }
        return false
    }

    /** Run [cmd] as root and return its raw stdout **bytes** (one-shot — the persistent shell's sentinel
     *  protocol is text-only, so binary output like `screencap -p` needs a dedicated exec). Not
     *  synchronized: it doesn't touch the shared shell, so a screenshot won't stall navbar root actions.
     *  Null on failure / no su. */
    override fun runBytes(cmd: String): ByteArray? = runBytesBounded(cmd, Long.MAX_VALUE - 1L)

    override fun runBytesBounded(cmd: String, maxBytes: Long): ByteArray? {
        val forms = SuFormPolicy.candidates(form)
        for (f in forms) {
            val bytes = runBounded("bytes", argvOneShot(f, cmd)) { p ->
                // binary-safe; drain before waitFor to avoid a full pipe deadlock. The bounded reader
                // probes one byte beyond the ceiling, then runBounded kills the producer on overflow.
                val b = BoundedStreams.readBytes(p.inputStream, maxBytes)
                if (p.waitFor() == 0) b else null
            }
            if (bytes != null) { form = f; return bytes }
        }
        if (form == SuFormPolicy.UNPROBED) form = SuFormPolicy.NONE_LAST_PROBE
        return null
    }

    /** Stream binary stdout directly to [target] under a hard byte ceiling. This is for larger root
     * artifacts such as Companion databases where a ByteArray would double peak heap before staging.
     * A failed/non-zero/oversized command leaves no partial file. */
    fun runToFileBounded(cmd: String, target: File, maxBytes: Long, timeoutMs: Long = 30_000L): Long? {
        val forms = SuFormPolicy.candidates(form)
        for (f in forms) {
            val written = runBounded("file", argvOneShot(f, cmd), timeoutMs) { p ->
                val count = target.outputStream().use { output ->
                    BoundedStreams.copy(p.inputStream, output, maxBytes)
                }
                if (p.waitFor() == 0) count else null
            }
            if (written != null) {
                form = f
                return written
            }
            target.delete()
        }
        if (form == SuFormPolicy.UNPROBED) form = SuFormPolicy.NONE_LAST_PROBE
        target.delete()
        return null
    }

    /** One-shot, bounded text command for diagnostics. Unlike [runOutput], this never waits behind the
     * synchronized persistent control shell, so a slow perf probe cannot add seconds of latency to a
     * navbar/screen/hardware command. */
    fun runOutputIsolatedBounded(cmd: String, maxBytes: Long, timeoutMs: Long = CMD_TIMEOUT_MS): String? {
        val forms = SuFormPolicy.candidates(form)
        for (f in forms) {
            val out = runBounded("isolated-out", argvOneShot(f, cmd), timeoutMs) { p ->
                val bytes = BoundedStreams.readBytes(p.inputStream, maxBytes)
                if (p.waitFor() == 0) String(bytes, Charsets.UTF_8) else null
            }
            if (out != null) {
                form = f
                return out
            }
        }
        if (form == SuFormPolicy.UNPROBED) form = SuFormPolicy.NONE_LAST_PROBE
        return null
    }

    fun availableIsolated(): Boolean = runOutputIsolatedBounded("true", 1L) != null

    override fun available(): Boolean = run("true")

    // --- persistent shell ---

    /** Send [cmd] through the persistent root shell; returns (stdout, exitCode), or null if the shell
     *  path is unavailable/broke — the caller then falls back to a one-shot exec. */
    private fun piped(cmd: String): Pair<String, Int>? {
        if (form == SuFormPolicy.NONE_LAST_PROBE) return null
        val sh = ensureShell() ?: return null
        return try {
            sh.io.submit(Callable { transact(sh, cmd) }).get(CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // timeout / EOF / broken pipe / desync — drop the shell; the caller falls back to one-shot.
            Log.d(TAG, "persistent shell transact failed; falling back to one-shot", e)
            closeShell()
            null
        }
    }

    /** One round-trip on the shared shell. Runs [cmd] (its stderr suppressed), then echoes the sentinel
     *  plus the exit code so both stdout and status are recoverable over the single pipe. The `{ …; }`
     *  group tolerates multi-statement (and even multi-line) commands. */
    private fun transact(sh: ShellHandle, cmd: String): Pair<String, Int> {
        sh.stdin.write("{ $cmd ; } 2>/dev/null; echo $SENTINEL:$?\n")
        sh.stdin.flush()
        val out = StringBuilder()
        while (true) {
            val line = sh.stdout.readLine() ?: throw IOException("persistent su shell closed")
            if (line.startsWith(SENTINEL)) {
                val rc = line.substringAfter(':').trim().toIntOrNull() ?: -1
                return out.toString() to rc
            }
            out.append(line).append('\n')
        }
    }

    private fun ensureShell(): ShellHandle? {
        shell?.let { if (it.process.isAlive) return it }
        closeShell()
        if (!SuFormPolicy.working(form)) {
            oneShotRun("true")              // probe + cache a working form, or record a negative probe
            if (!SuFormPolicy.working(form)) return null
        }
        return try {
            val p = Runtime.getRuntime().exec(argvShell(form))
            drainStderr(p)
            ShellHandle(
                process = p,
                stdin = p.outputStream.bufferedWriter(),
                stdout = p.inputStream.bufferedReader(),
                io = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "ha-paneld-su").apply { isDaemon = true }
                },
            ).also { shell = it }
        } catch (e: Exception) {
            Log.d(TAG, "could not open persistent su shell", e)
            null
        }
    }

    private fun closeShell() {
        shell?.let {
            runCatching { it.stdin.close() }
            runCatching { it.process.destroy() }
            runCatching { it.io.shutdownNow() }
        }
        shell = null
    }

    /** Discard the shell's own stderr in the background so a write there can't fill the pipe and wedge it. */
    private fun drainStderr(p: Process) {
        Thread {
            runCatching { p.errorStream.bufferedReader().forEachLine { /* discard */ } }
        }.apply { isDaemon = true; name = "ha-paneld-su-err" }.start()
    }

    // --- one-shot fallback (pre-0.8.3 behaviour; also the form probe) ---

    /**
     * Run a one-shot `su` [argv], bounding the whole call (exec + [reader]) to [CMD_TIMEOUT_MS] so a `su`
     * that hangs on auth can never block the caller for the life of the process — the same guarantee the
     * persistent shell already has via its timed future. [reader] consumes the process (wait / read stdout)
     * on a throwaway daemon thread; on timeout or failure the child is force-killed and null is returned.
     */
    private fun <T> runBounded(label: String, argv: Array<String>, timeoutMs: Long = CMD_TIMEOUT_MS, reader: (Process) -> T): T? {
        val p = try {
            Runtime.getRuntime().exec(argv)
        } catch (e: Exception) {
            Log.d(TAG, "su $label exec failed: ${argv.joinToString(" ")}", e)
            return null
        }
        val ex = Executors.newSingleThreadExecutor { r ->
            Thread(r, "ha-paneld-su-1shot").apply { isDaemon = true }
        }
        return try {
            ex.submit(Callable { reader(p) }).get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // timeout / broken pipe / reader failure — kill the child so it can't linger, then give up.
            Log.d(TAG, "su $label timed out or failed", e)
            runCatching { p.destroyForcibly() }
            null
        } finally {
            ex.shutdownNow()
        }
    }

    private fun oneShotRun(cmd: String): Boolean {
        val forms = SuFormPolicy.candidates(form)
        for (f in forms) {
            if (runBounded("run", argvOneShot(f, cmd)) { it.waitFor() } == 0) { form = f; return true }
        }
        if (form == SuFormPolicy.UNPROBED) form = SuFormPolicy.NONE_LAST_PROBE
        return false
    }

    /** Long-running one-shot su [cmd] (exit 0 → true), bounded to [timeoutMs]. Always a one-shot — a
     *  minutes-long op (e.g. staging + installing a large APK) must NOT occupy the shared persistent
     *  shell, whose sentinel protocol is bounded to the short [CMD_TIMEOUT_MS]. */
    fun runLong(cmd: String, timeoutMs: Long): Boolean {
        val forms = SuFormPolicy.candidates(form)
        for (f in forms) {
            if (runBounded("run-long", argvOneShot(f, cmd), timeoutMs) { it.waitFor() } == 0) { form = f; return true }
        }
        if (form == SuFormPolicy.UNPROBED) form = SuFormPolicy.NONE_LAST_PROBE
        return false
    }

    private data class StdinResult(val stdout: String, val exitCode: Int)

    private fun runWithStdinLongResult(cmd: String, input: java.io.File, timeoutMs: Long): StdinResult? {
        val forms = SuFormPolicy.candidates(form)
        for (f in forms) {
            val out = runBounded("stdin-long", argvOneShot(f, cmd), timeoutMs) { p ->
                val feeder = Thread {
                    runCatching { p.outputStream.use { os -> input.inputStream().use { it.copyTo(os) } } }
                }.apply { isDaemon = true; start() }
                val text = p.inputStream.bufferedReader().readText()  // read before waitFor (avoid deadlock)
                feeder.join(timeoutMs)
                StdinResult(text, p.waitFor())
            }
            if (out != null) { form = f; return out }
        }
        if (form == SuFormPolicy.UNPROBED) form = SuFormPolicy.NONE_LAST_PROBE
        return null
    }

    /** Long one-shot command with streamed stdin. Returns stdout even when the child exits non-zero. */
    fun runWithStdinLong(cmd: String, input: java.io.File, timeoutMs: Long): String? =
        runWithStdinLongResult(cmd, input, timeoutMs)?.stdout

    /** Checked variant for data restoration: non-zero exit is failure even when the command wrote stdout. */
    fun runWithStdinLongChecked(cmd: String, input: java.io.File, timeoutMs: Long): String? =
        runWithStdinLongResult(cmd, input, timeoutMs)?.takeIf { it.exitCode == 0 }?.stdout

    /** Long-running one-shot su [cmd] returning stdout (null on non-zero/failure), bounded to [timeoutMs]. */
    fun runOutputLong(cmd: String, timeoutMs: Long): String? {
        val forms = SuFormPolicy.candidates(form)
        for (f in forms) {
            val out = runBounded("out-long", argvOneShot(f, cmd), timeoutMs) { p ->
                val text = p.inputStream.bufferedReader().readText()
                if (p.waitFor() == 0) text else null
            }
            if (out != null) { form = f; return out }
        }
        if (form == SuFormPolicy.UNPROBED) form = SuFormPolicy.NONE_LAST_PROBE
        return null
    }

    private fun oneShotOutput(cmd: String): String? {
        val forms = SuFormPolicy.candidates(form)
        for (f in forms) {
            val out = runBounded("out", argvOneShot(f, cmd)) { p ->
                val text = p.inputStream.bufferedReader().readText() // read before waitFor (avoid deadlock)
                if (p.waitFor() == 0) text else null
            }
            if (out != null) { form = f; return out }
        }
        if (form == SuFormPolicy.UNPROBED) form = SuFormPolicy.NONE_LAST_PROBE
        return null
    }
}
