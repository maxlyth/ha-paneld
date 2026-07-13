package io.github.maxlyth.hapaneld.logship

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared, demand-driven logcat capture. ONE subprocess and ONE redaction pass feed every consumer —
 * the remote [LogShipper] and any live viewers on the `:8888` Logs tab — plus a bounded ring of
 * recent lines so a viewer that attaches while capture is already running gets instant backlog.
 *
 * Runs only while it has subscribers (idle-stop): with log shipping off and no viewer connected
 * there is no logcat subprocess at all. Two sources exist as separate instances:
 *  - [app]: ha-paneld's own-process logcat (its `Log.*` output + the Ktor/HiveMQ SLF4J lines) —
 *    own-uid, so readable with no `READ_LOGS` permission and no root.
 *  - [system]: the full system logcat via `su -c logcat` — root panels only; callers gate on
 *    `Su.available()`.
 *
 * Every line is [redact]ed for tokens / passwords / URL secrets as it is captured, so both the
 * remote sink AND the local browser view only ever see scrubbed lines.
 */
class LogCapture(
    private val scope: CoroutineScope,
    /** Streaming subprocess argv (long-lived `logcat` follow). */
    private val streamCmd: List<String>,
    /** One-shot dump argv for [dump] — backlog prefill when the stream isn't already running. */
    private val dumpCmd: (Int) -> List<String>,
) {
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val ring = ArrayDeque<String>(RING_CAP)
    private val dumps = mutableSetOf<Process>()

    // Each start() gets its own Run so a stop→start race can never orphan the new subprocess.
    internal class Run {
        @Volatile var job: Job? = null
        @Volatile var proc: Process? = null
        private var cancelled = false

        fun attach(candidate: Process): Boolean = synchronized(this) {
            if (cancelled) {
                candidate.destroy()
                false
            } else {
                proc = candidate
                true
            }
        }

        fun detach(candidate: Process) = synchronized(this) {
            if (proc === candidate) proc = null
        }

        fun cancel() = synchronized(this) {
            cancelled = true
            job?.cancel()
            runCatching { proc?.destroy() }   // unblocks the reader parked in readLine()
            proc = null
        }
    }
    private var run: Run? = null
    @Volatile private var closed = false

    internal fun activeRun(): Run? = synchronized(this) { run }

    /** Register [listener] for every future (redacted) line; starts the capture if it's the first
     *  consumer. Close the returned handle to detach — the last detach stops the subprocess. */
    fun subscribe(listener: (String) -> Unit): AutoCloseable {
        synchronized(this) {
            if (closed) return AutoCloseable {}
            listeners.add(listener)
            if (run == null) start()
        }
        return AutoCloseable {
            synchronized(this) {
                listeners.remove(listener)
                if (listeners.isEmpty()) stop()
            }
        }
    }

    /** The buffered recent lines (already redacted) — instant backlog while capture is running. */
    fun snapshot(): List<String> = synchronized(ring) { ring.toList() }

    /** One-shot dump of the last [lines] log lines (redacted) — backlog prefill for a fresh viewer
     *  when the stream has only just started. Blocking; call off the request thread's fast path. */
    fun dump(lines: Int = DUMP_LINES): List<String> = runCatching {
        if (closed) return@runCatching emptyList()
        val p = ProcessBuilder(dumpCmd(lines)).redirectErrorStream(true).start()
        val admitted = synchronized(this) {
            if (closed) false else dumps.add(p)
        }
        if (!admitted) {
            p.destroy()
            return@runCatching emptyList()
        }
        try {
            val out = p.inputStream.bufferedReader().readLines().map { redact(it) }.takeLast(lines)
            p.waitFor()
            out
        } finally {
            synchronized(this) { dumps.remove(p) }
            runCatching { p.destroy() }
        }
    }.getOrDefault(emptyList())

    /** Permanently close this service-owned capture and destroy a blocked streaming subprocess. */
    fun close() = synchronized(this) {
        if (closed) return@synchronized
        closed = true
        listeners.clear()
        dumps.forEach { runCatching { it.destroy() } }
        dumps.clear()
        stop()
    }

    private fun start() {
        val r = Run()
        run = r
        r.job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val p = ProcessBuilder(streamCmd).redirectErrorStream(true).start()
                    if (!r.attach(p)) break
                    try {
                        p.inputStream.bufferedReader().use { reader ->
                            while (isActive) {
                                val line = reader.readLine() ?: break
                                emit(r, redact(line))
                            }
                        }
                    } finally {
                        r.detach(p)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "capture restart: ${e.message}")
                }
                if (isActive) delay(BACKOFF_MS)
            }
        }
    }

    private fun stop() {
        run?.cancel()
        run = null
        synchronized(ring) { ring.clear() }
    }

    internal fun emit(source: Run, line: String) {
        val targets = synchronized(this) {
            // A process can produce one final buffered line after destroy(). Never let that line cross a
            // stop→start boundary into the replacement generation's ring or listeners.
            if (closed || run !== source) return
            synchronized(ring) {
                if (ring.size >= RING_CAP) ring.removeFirst()
                ring.addLast(line)
            }
            listeners.toList()
        }
        for (listener in targets) runCatching { listener(line) }
    }

    companion object {
        private const val TAG = "ha-paneld/logcap"
        private const val RING_CAP = 400
        private const val DUMP_LINES = 300
        private const val BACKOFF_MS = 2_000L

        /** Own-process logcat — no `READ_LOGS` / root. `-T 1` starts at "now" so a restart doesn't
         *  replay the whole ring buffer into the shipper. */
        fun app(scope: CoroutineScope) = LogCapture(
            scope,
            listOf("logcat", "-v", "threadtime", "-T", "1", "*:V"),
            { n -> listOf("logcat", "-v", "threadtime", "-d", "-t", "$n", "*:V") },
        )

        /** Full system logcat via su — callers gate on `Su.available()`. The filterspec is quoted so
         *  su's shell doesn't glob `*:V`. */
        fun system(scope: CoroutineScope) = LogCapture(
            scope,
            listOf("su", "-c", "logcat -v threadtime -T 1 '*:V'"),
            { n -> listOf("su", "-c", "logcat -v threadtime -d -t $n '*:V'") },
        )

        // Conservative redaction — strip the obvious secret shapes before a line reaches ANY consumer
        // (remote sink or browser view).
        private val REDACTIONS: List<Pair<Regex, String>> = listOf(
            Regex("""(?i)(authorization:\s*bearer\s+)\S+""") to "$1***",
            // Value bounded by [^\s&]+ (not \S+) so it stops at the next URL query param / whitespace,
            // redacting only the secret rather than eating the rest of the line.
            Regex("""(?i)\b(password|passwd|pwd|secret|api[_-]?key|access[_-]?token|token)(["'=:\s]+)[^\s&]+""")
                to "$1$2***",
            // Home Assistant long-lived tokens / JWTs.
            Regex("""\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{6,}""") to "***jwt***",
            // Secrets carried in URL query strings.
            Regex("""(?i)([?&](?:token|auth|access_token|api_key|key|password)=)[^&\s"]+""") to "$1***",
        )

        /** Apply [REDACTIONS] in sequence. Public for unit testing. */
        fun redact(line: String): String {
            var s = line
            for ((re, repl) in REDACTIONS) s = re.replace(s, repl)
            return s
        }
    }
}
