package io.github.maxlyth.hapaneld.logship

import android.util.Log
import io.github.maxlyth.hapaneld.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Optional remote log shipping. Streams ha-paneld's OWN process logcat — its `android.util.Log`
 * output plus the Ktor/HiveMQ SLF4J library logs, all emitted by this app's uid, so readable with no
 * `READ_LOGS` permission and no root — to a central log collector for fleet-wide debugging without
 * per-panel `adb logcat`.
 *
 * OFF by default and inert unless a sink host is configured ([Config.logShipActive]); LAN-only by
 * intent; every line is [LogCapture.redact]ed for tokens / passwords / URL secrets before it leaves
 * the device. Configured via the HTTP `/config` endpoint (provision.sh `--log-*` flags) or Configure tab.
 *
 * Two transports, selected by `log_ship_protocol` — both are plain, widely-supported wire formats, so
 * any collector with a syslog or HTTP/NDJSON input can ingest them:
 *  - `syslog` (default): newline-delimited RFC5424 frames over a persistent TCP socket.
 *  - `http`: newline-delimited JSON (NDJSON) POSTed in batches to the collector's HTTP-ingest endpoint.
 *
 * Capture comes from the shared [LogCapture] (one own-uid `logcat` subprocess + one redaction pass,
 * shared with the `:8888` live log viewer). Lines pass through a bounded queue, so a slow/unreachable
 * sink applies backpressure by dropping the OLDEST lines — best-effort telemetry must never OOM or
 * block the panel. The sink connection reconnects with backoff.
 */
class LogShipper(
    private val config: Config,
    private val scope: CoroutineScope,
    private val capture: LogCapture,
) {
    @Volatile private var job: Job? = null
    @Volatile private var captureSub: AutoCloseable? = null

    // Bounded; full => drop oldest (telemetry is best-effort and never back-pressures the app).
    private val queue = ArrayBlockingQueue<String>(QUEUE_CAP)
    @Volatile private var dropped = 0L
    @Volatile private var sent = 0L
    @Volatile private var connected = false
    @Volatile private var lastError: String? = null

    // Snapshot of the sink config this run was started with, so reconfigure() knows when to restart.
    @Volatile private var runHost = ""
    @Volatile private var runPort = 0
    @Volatile private var runProto = "syslog"

    private val rfc3339 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** Start shipping if enabled + host set; otherwise stay inert. Idempotent. */
    fun start() {
        if (job != null) return
        if (!config.logShipActive) {
            Log.i(TAG, "disabled (no sink configured)")
            return
        }
        runHost = config.logShipHost
        runPort = config.logShipPort
        runProto = config.logShipProtocol.ifBlank { "syslog" }
        sent = 0; dropped = 0; connected = false; lastError = null
        captureSub = capture.subscribe { offer(it) }   // lines arrive already redacted
        job = scope.launch(Dispatchers.IO) { shipLoop() }
        Log.i(TAG, "started → $runProto://$runHost:$runPort")
    }

    /** Re-read config; restart only if the sink target changed (or was enabled/disabled). No-op otherwise. */
    fun reconfigure() {
        val active = config.logShipActive
        val changed = config.logShipHost != runHost ||
            config.logShipPort != runPort ||
            config.logShipProtocol.ifBlank { "syslog" } != runProto
        if (job != null && (!active || changed)) stop()
        if (active && job == null) start()
    }

    /** Stop capture + shipping. Idempotent. */
    fun stop() {
        job?.cancel()
        job = null
        runCatching { captureSub?.close() }
        captureSub = null
        queue.clear()
        connected = false
    }

    /** One-line status for the info page / diag. The sink host is operator infra, shown like the broker. */
    fun statusText(): String = when {
        !config.logShipEnabled -> "off"
        config.logShipHost.isBlank() -> "enabled · no host set"
        else -> "$runProto://$runHost:$runPort · " +
            (if (connected) "connected" else "disconnected${lastError?.let { " ($it)" } ?: ""}") +
            " · sent=$sent${if (dropped > 0) " dropped=$dropped" else ""}"
    }

    /** Bounded, drop-oldest: never block the capture thread on a slow sink. */
    private fun offer(line: String) {
        if (!queue.offer(line)) {
            queue.poll()
            dropped++
            queue.offer(line)
        }
    }

    // --- ship: drain the queue to the configured sink -----------------------------------------------

    private suspend fun CoroutineScope.shipLoop() {
        when (runProto) {
            "http" -> shipHttp()
            else -> shipSyslog()
        }
    }

    private suspend fun CoroutineScope.shipSyslog() {
        while (isActive) {
            try {
                Socket(runHost, runPort).use { sock ->
                    sock.tcpNoDelay = true
                    val out = sock.getOutputStream()
                    connected = true
                    lastError = null
                    Log.i(TAG, "syslog connected $runHost:$runPort")
                    while (isActive) {
                        val line = queue.poll(POLL_SECONDS, TimeUnit.SECONDS) ?: continue
                        out.write(syslogFrame(line).toByteArray(Charsets.UTF_8))
                        out.flush()
                        sent++
                    }
                }
            } catch (e: Exception) {
                connected = false
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "syslog $runHost:$runPort: ${e.message}")
                if (isActive) delay(SINK_BACKOFF_MS)
            }
        }
    }

    private suspend fun CoroutineScope.shipHttp() {
        val url = "http://$runHost:$runPort/"   // collector HTTP-ingest root path
        val batch = ArrayList<String>(BATCH_MAX)
        while (isActive) {
            batch.clear()
            val first = queue.poll(POLL_SECONDS, TimeUnit.SECONDS) ?: continue
            batch.add(first)
            queue.drainTo(batch, BATCH_MAX - 1)
            try {
                postNdjson(url, batch)
                connected = true
                lastError = null
                sent += batch.size
            } catch (e: Exception) {
                connected = false
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "http $url: ${e.message}")
                // Best-effort: drop this batch rather than retry-loop it (avoids unbounded buildup).
                if (isActive) delay(SINK_BACKOFF_MS)
            }
        }
    }

    private fun postNdjson(url: String, lines: List<String>) {
        val body = lines.joinToString("\n") { jsonEvent(it) }.toByteArray(Charsets.UTF_8)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/x-ndjson")
        }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
        } finally {
            conn.disconnect()
        }
    }

    // --- framing -------------------------------------------------------------------------------------

    /** RFC5424 frame: `<PRI>1 TIMESTAMP HOSTNAME APP-NAME PROCID MSGID SD MSG`, newline-terminated. */
    private fun syslogFrame(line: String): String {
        val pri = FACILITY_USER * 8 + severityOf(line)
        val ts = rfc3339.format(Date())
        val host = config.panelId.ifBlank { "panel" }.replace(' ', '_')
        return "<$pri>1 $ts $host $APP - - - $line\n"
    }

    private fun jsonEvent(line: String): String {
        val ts = rfc3339.format(Date())
        val host = config.panelId.ifBlank { "panel" }
        return "{\"timestamp\":\"$ts\",\"host\":${jsonStr(host)},\"app\":\"$APP\"," +
            "\"message\":${jsonStr(line)}}"
    }

    /** Map the logcat threadtime level char to a syslog severity (default info). */
    private fun severityOf(line: String): Int {
        val m = LEVEL_RE.find(line) ?: return SEV_INFO
        return when (m.groupValues[1]) {
            "V", "D" -> SEV_DEBUG
            "I" -> SEV_INFO
            "W" -> SEV_WARNING
            "E" -> SEV_ERROR
            "F" -> SEV_CRIT
            else -> SEV_INFO
        }
    }

    companion object {
        private const val TAG = "ha-paneld/logship"
        private const val APP = "ha-paneld"

        private const val QUEUE_CAP = 4000          // ~ a few MB worst case; drop-oldest beyond this
        private const val BATCH_MAX = 200           // HTTP: events per POST
        private const val POLL_SECONDS = 5L         // queue wait before re-checking liveness
        private const val SINK_BACKOFF_MS = 5_000L
        private const val HTTP_TIMEOUT_MS = 5_000

        private const val FACILITY_USER = 1
        private const val SEV_CRIT = 2
        private const val SEV_ERROR = 3
        private const val SEV_WARNING = 4
        private const val SEV_INFO = 6
        private const val SEV_DEBUG = 7

        // threadtime line: "MM-DD HH:MM:SS.mmm  PID  TID L TAG: message" — capture the level char L.
        private val LEVEL_RE =
            Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+\d+\s+\d+\s+([VDIWEF])\s""")

        private fun jsonStr(v: String): String {
            val sb = StringBuilder(v.length + 2)
            sb.append('"')
            for (c in v) when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
            sb.append('"')
            return sb.toString()
        }
    }
}
