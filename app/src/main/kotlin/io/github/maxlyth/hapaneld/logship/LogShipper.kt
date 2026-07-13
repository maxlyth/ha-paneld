package io.github.maxlyth.hapaneld.logship

import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.util.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Immutable view of the preferences relevant to one log-shipping decision. */
internal data class LogShipConfigSnapshot(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val protocol: String,
    val panelId: String,
) {
    fun targetOrNull(): LogShipTarget? {
        if (!enabled || host.isBlank()) return null
        return LogShipTarget(host, port, if (protocol == "http") "http" else "syslog", panelId)
    }
}

/** A target belongs to one run; live preference reads never change a run underneath its worker. */
internal data class LogShipTarget(val host: String, val port: Int, val protocol: String, val panelId: String)

/** A transport is created inert, attached to its run, and only then allowed to block in [connect]. */
internal interface LogSink : AutoCloseable {
    fun connect()
    fun send(lines: List<String>)
}

internal fun interface LogSinkFactory {
    fun create(target: LogShipTarget, encode: (String) -> String): LogSink
}

/**
 * Everything mutable for one shipping generation. Its queue, counters, capture subscription, worker,
 * and blocking transport cannot be observed or consumed by a replacement generation.
 */
internal class LogShipRun(
    val target: LogShipTarget,
    queueCapacity: Int,
) : AutoCloseable {
    data class Status(val connected: Boolean, val sent: Long, val dropped: Long, val lastError: String?)

    private val queue = ArrayBlockingQueue<Any>(queueCapacity)
    private val sent = AtomicLong()
    private val dropped = AtomicLong()
    @Volatile private var open = true
    @Volatile private var connected = false
    @Volatile private var lastError: String? = null
    private var subscription: AutoCloseable? = null
    private var job: Job? = null
    private var sink: LogSink? = null

    fun isOpen(): Boolean = open

    fun bindSubscription(candidate: AutoCloseable) = synchronized(this) {
        if (open) subscription = candidate else runCatching { candidate.close() }
    }

    fun bindJob(candidate: Job) = synchronized(this) {
        if (open) job = candidate else candidate.cancel()
    }

    fun attachSink(candidate: LogSink): Boolean = synchronized(this) {
        if (!open) false else {
            sink = candidate
            true
        }
    }

    fun detachSink(candidate: LogSink) = synchronized(this) {
        if (sink === candidate) sink = null
    }

    /** Bounded, drop-oldest admission. A closed generation rejects lines from a late capture callback. */
    fun offer(line: String) {
        if (!open) return
        if (!queue.offer(line)) {
            queue.poll()
            dropped.incrementAndGet()
            queue.offer(line)
        }
    }

    /** A close wake-up is deliberately not a String, so it can never collide with captured content. */
    fun takeBatch(max: Int, timeoutSeconds: Long): List<String> {
        val first = queue.poll(timeoutSeconds, TimeUnit.SECONDS) as? String ?: return emptyList()
        val out = ArrayList<String>(max)
        out.add(first)
        while (out.size < max) {
            val next = queue.poll() ?: break
            if (next is String) out.add(next)
        }
        return out
    }

    fun markConnected() {
        if (!open) return
        connected = true
        lastError = null
    }

    fun markFailure(error: Throwable) {
        if (!open) return
        connected = false
        lastError = error.message ?: error.javaClass.simpleName
    }

    fun recordSent(count: Int) {
        if (count > 0) sent.addAndGet(count.toLong())
    }

    fun recordDropped(count: Int) {
        if (count > 0) dropped.addAndGet(count.toLong())
    }

    fun status(): Status = Status(connected, sent.get(), dropped.get(), lastError)

    override fun close() {
        val resources = synchronized(this) {
            if (!open) return
            open = false
            connected = false
            queue.clear()
            queue.offer(WAKE)
            val captured = Triple(subscription, job, sink)
            subscription = null
            job = null
            sink = null
            captured
        }
        resources.second?.cancel()
        runCatching { resources.first?.close() }
        runCatching { resources.third?.close() }
    }

    private companion object {
        val WAKE = Any()
    }
}

/**
 * Optional remote log shipping. Streams ha-paneld's own redacted process log to a configured syslog or
 * HTTP/NDJSON collector. Capture is demand-driven and the sink queue is bounded, so this best-effort
 * telemetry cannot back-pressure the app or grow without limit.
 *
 * Each start owns a complete [LogShipRun]. Reconfiguration closes the old capture subscription and any
 * blocking transport before creating the replacement, while late callbacks are rejected by the closed run.
 */
class LogShipper internal constructor(
    private val configSnapshot: () -> LogShipConfigSnapshot,
    private val scope: CoroutineScope,
    private val subscribeCapture: ((String) -> Unit) -> AutoCloseable,
    private val sinkFactory: LogSinkFactory,
) {
    constructor(config: Config, scope: CoroutineScope, capture: LogCapture) : this(
        configSnapshot = {
            LogShipConfigSnapshot(
                enabled = config.logShipEnabled,
                host = config.logShipHost,
                port = config.logShipPort,
                protocol = config.logShipProtocol.ifBlank { "syslog" },
                panelId = config.panelId,
            )
        },
        scope = scope,
        subscribeCapture = capture::subscribe,
        sinkFactory = NetworkLogSinkFactory,
    )

    private val lock = Any()
    private var run: LogShipRun? = null
    private val rfc3339 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** Start shipping if enabled and a host is set. Idempotent and serialized with reconfiguration. */
    fun start() = synchronized(lock) {
        if (run != null) return@synchronized
        val target = configSnapshot().targetOrNull()
        if (target == null) {
            Log.i(TAG, "disabled (no sink configured)")
            return@synchronized
        }
        startLocked(target)
    }

    /** Replace the complete run when enablement, target, protocol, or panel identity changes. */
    fun reconfigure() = synchronized(lock) {
        val target = configSnapshot().targetOrNull()
        if (run?.target == target || (run == null && target == null)) return@synchronized
        run?.close()
        run = null
        if (target != null) startLocked(target)
    }

    /** Terminally detach and close the current generation. Idempotent. */
    fun stop() = synchronized(lock) {
        run?.close()
        run = null
    }

    /** One-line status for the info page and diagnostics. */
    fun statusText(): String {
        val config = configSnapshot()
        if (!config.enabled) return "off"
        if (config.host.isBlank()) return "enabled · no host set"
        val current = synchronized(lock) { run }
        val target = current?.target ?: config.targetOrNull()!!
        val status = current?.status() ?: LogShipRun.Status(false, 0, 0, null)
        return "${target.protocol}://${target.host}:${target.port} · " +
            (if (status.connected) "connected" else "disconnected${status.lastError?.let { " ($it)" } ?: ""}") +
            " · sent=${status.sent}${if (status.dropped > 0) " dropped=${status.dropped}" else ""}"
    }

    private fun startLocked(target: LogShipTarget) {
        val candidate = LogShipRun(target, QUEUE_CAP)
        run = candidate
        try {
            candidate.bindSubscription(subscribeCapture(candidate::offer))
            candidate.bindJob(scope.launch(Dispatchers.IO) { shipLoop(candidate) })
            Log.i(TAG, "started → ${target.protocol}://${target.host}:${target.port}")
        } catch (e: Exception) {
            run = null
            candidate.close()
            Log.w(TAG, "start failed: ${e.message}")
        }
    }

    private suspend fun CoroutineScope.shipLoop(run: LogShipRun) {
        val target = run.target
        val batchMax = if (target.protocol == "http") BATCH_MAX else 1
        val encode: (String) -> String = if (target.protocol == "http") {
            { line -> jsonEvent(line, target.panelId) }
        } else {
            { line -> syslogFrame(line, target.panelId) }
        }
        while (isActive && run.isOpen()) {
            var sink: LogSink? = null
            try {
                val candidate = sinkFactory.create(target, encode)
                if (!run.attachSink(candidate)) {
                    candidate.close()
                    return
                }
                sink = candidate
                candidate.connect()
                if (target.protocol == "syslog") run.markConnected()
                while (isActive && run.isOpen()) {
                    val batch = run.takeBatch(batchMax, POLL_SECONDS)
                    if (batch.isEmpty()) continue
                    try {
                        candidate.send(batch)
                        run.recordSent(batch.size)
                        run.markConnected()
                    } catch (e: Exception) {
                        // The batch left the bounded queue and will not be retried, so account for its loss.
                        run.recordDropped(batch.size)
                        throw e
                    }
                }
            } catch (e: Exception) {
                // Closing a run deliberately breaks its transport. Do not leak that expected exception
                // into a replacement generation's newly captured log stream.
                if (run.isOpen()) {
                    run.markFailure(e)
                    Log.w(TAG, "${target.protocol} ${target.host}:${target.port}: ${e.message}")
                    if (isActive) delay(SINK_BACKOFF_MS)
                }
            } finally {
                sink?.let {
                    run.detachSink(it)
                    runCatching { it.close() }
                }
            }
        }
    }

    private fun timestamp(): String = synchronized(rfc3339) { rfc3339.format(Date()) }

    /** RFC5424 frame: `<PRI>1 TIMESTAMP HOSTNAME APP-NAME PROCID MSGID SD MSG`. */
    private fun syslogFrame(line: String, panelId: String): String {
        val pri = FACILITY_USER * 8 + severityOf(line)
        val host = panelId.ifBlank { "panel" }.replace(' ', '_')
        return "<$pri>1 ${timestamp()} $host $APP - - - $line\n"
    }

    private fun jsonEvent(line: String, panelId: String): String =
        "{\"timestamp\":\"${timestamp()}\",\"host\":${jsonStr(panelId.ifBlank { "panel" })}," +
            "\"app\":\"$APP\",\"message\":${jsonStr(line)}}"

    private fun severityOf(line: String): Int {
        val level = LEVEL_RE.find(line)?.groupValues?.get(1) ?: return SEV_INFO
        return when (level) {
            "V", "D" -> SEV_DEBUG
            "W" -> SEV_WARNING
            "E" -> SEV_ERROR
            "F" -> SEV_CRIT
            else -> SEV_INFO
        }
    }

    companion object {
        private const val TAG = "ha-paneld/logship"
        private const val APP = "ha-paneld"
        private const val QUEUE_CAP = 4000
        private const val BATCH_MAX = 200
        private const val POLL_SECONDS = 5L
        private const val SINK_BACKOFF_MS = 5_000L
        internal const val NETWORK_TIMEOUT_MS = 5_000

        private const val FACILITY_USER = 1
        private const val SEV_CRIT = 2
        private const val SEV_ERROR = 3
        private const val SEV_WARNING = 4
        private const val SEV_INFO = 6
        private const val SEV_DEBUG = 7

        private val LEVEL_RE =
            Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+\d+\s+\d+\s+([VDIWEF])\s""")

        private fun jsonStr(value: String): String = Json.str(value)
    }
}

/** Production transports. Creation never blocks; [LogSink.connect] begins only after run attachment. */
private object NetworkLogSinkFactory : LogSinkFactory {
    override fun create(target: LogShipTarget, encode: (String) -> String): LogSink =
        if (target.protocol == "http") HttpLogSink(target, encode) else SyslogLogSink(target, encode)
}

private class SyslogLogSink(
    private val target: LogShipTarget,
    private val encode: (String) -> String,
) : LogSink {
    private val socket = Socket()

    override fun connect() {
        socket.connect(InetSocketAddress(target.host, target.port), LogShipper.NETWORK_TIMEOUT_MS)
        socket.tcpNoDelay = true
    }

    override fun send(lines: List<String>) {
        val out = socket.getOutputStream()
        for (line in lines) out.write(encode(line).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    override fun close() = socket.close()
}

private class HttpLogSink(
    target: LogShipTarget,
    private val encode: (String) -> String,
) : LogSink {
    private val url = "http://${target.host}:${target.port}/"
    @Volatile private var closed = false
    private var connection: HttpURLConnection? = null

    override fun connect() {
        if (closed) throw IOException("closed")
    }

    override fun send(lines: List<String>) {
        val body = lines.joinToString("\n") { encode(it) }.toByteArray(Charsets.UTF_8)
        val candidate = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = LogShipper.NETWORK_TIMEOUT_MS
            readTimeout = LogShipper.NETWORK_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/x-ndjson")
        }
        synchronized(this) {
            if (closed) {
                candidate.disconnect()
                throw IOException("closed")
            }
            connection = candidate
        }
        try {
            candidate.outputStream.use { it.write(body) }
            val code = candidate.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
        } finally {
            synchronized(this) { if (connection === candidate) connection = null }
            candidate.disconnect()
        }
    }

    override fun close() {
        val active = synchronized(this) {
            closed = true
            connection.also { connection = null }
        }
        active?.disconnect()
    }
}
