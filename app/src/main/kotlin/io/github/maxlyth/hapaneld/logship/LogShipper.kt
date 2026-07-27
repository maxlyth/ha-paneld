package io.github.maxlyth.hapaneld.logship

import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.metrics.FeatureCostRegistry
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import io.github.maxlyth.hapaneld.util.Json
import io.github.maxlyth.hapaneld.util.BoundedDns
import io.github.maxlyth.hapaneld.util.LogShipEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
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
        val endpoint = LogShipEndpoint.resolve(host, port, protocol)
        if (endpoint.host.isBlank()) return null
        return LogShipTarget(endpoint.host, endpoint.port, endpoint.protocol, panelId)
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
    private val onDropped: ((Long) -> Unit)? = null,
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
            onDropped?.invoke(1)
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

    fun markFailure(reason: String) {
        if (!open) return
        connected = false
        lastError = reason
    }

    fun recordSent(count: Int) {
        if (count > 0) sent.addAndGet(count.toLong())
    }

    fun recordDropped(count: Int) {
        if (count > 0) {
            dropped.addAndGet(count.toLong())
            onDropped?.invoke(count.toLong())
        }
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
    private val featureCosts: FeatureCostRegistry = FeatureCosts.registry,
) {
    constructor(config: Config, scope: CoroutineScope, capture: LogCapture) : this(
        configSnapshot = {
            LogShipConfigSnapshot(
                enabled = config.logShipEnabled,
                host = config.logShipHost,
                port = config.logShipPort,
                protocol = config.logShipProtocol,
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
        return "${LogShipEndpoint.scheme(target.protocol)}://${target.host}:${target.port} · " +
            liveness(target, status) +
            " · sent=${status.sent}${if (status.dropped > 0) " dropped=${status.dropped}" else ""}"
    }

    /**
     * UDP has no connection and no acknowledgement, so it must never claim to be "connected" — that
     * word would assert a delivery guarantee the transport cannot make, and a silently-black-holed
     * sink would read exactly like a healthy one.
     */
    private fun liveness(target: LogShipTarget, status: LogShipRun.Status): String {
        if (!status.connected) return "disconnected${status.lastError?.let { " ($it)" } ?: ""}"
        if (target.protocol != LogShipEndpoint.SYSLOG_UDP) return "connected"
        return if (status.sent > 0) "sending (UDP is unacknowledged)" else "ready (UDP is unacknowledged)"
    }

    private fun startLocked(target: LogShipTarget) {
        val onDropped: ((Long) -> Unit)? = if (featureCosts.recordingEnabled) {
            { dropped -> featureCosts.recordDropped(FeatureCostOperation.LOG_SHIP_BATCH, dropped) }
        } else {
            null
        }
        val candidate = LogShipRun(target, QUEUE_CAP, onDropped)
        run = candidate
        try {
            candidate.bindSubscription(subscribeCapture(candidate::offer))
            candidate.bindJob(scope.launch(Dispatchers.IO) { shipLoop(candidate) })
            Log.i(TAG, "started → ${LogShipEndpoint.scheme(target.protocol)}://${target.host}:${target.port}")
        } catch (e: Exception) {
            run = null
            candidate.close()
            Log.w(TAG, "start failed: ${e.message}")
        }
    }

    private suspend fun CoroutineScope.shipLoop(run: LogShipRun) {
        val target = run.target
        // Batching is a property of the transport, not of whether cost metrics happen to be recording.
        // Draining what is already queued never adds latency — the poll after the first line does not
        // block — so there is no reason for the two to have ever been coupled.
        val batchMax = BATCH_MAX
        val encode: (String) -> String = if (target.protocol == LogShipEndpoint.HTTP) {
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
                // Syslog has no application handshake, so a completed connect (or, for UDP, a
                // successful name resolution) is the only positive signal the transport ever gives.
                if (target.protocol != LogShipEndpoint.HTTP) run.markConnected()
                while (isActive && run.isOpen()) {
                    val batch = run.takeBatch(batchMax, POLL_SECONDS)
                    if (batch.isEmpty()) continue
                    val cost = featureCosts.beginSynchronous(FeatureCostOperation.LOG_SHIP_BATCH)
                    var outcome = FeatureCostOutcome.SUCCESS
                    try {
                        candidate.send(batch)
                        run.recordSent(batch.size)
                        run.markConnected()
                    } catch (e: Exception) {
                        outcome = FeatureCostOutcome.FAILURE
                        // The batch left the bounded queue and will not be retried, so account for its loss.
                        run.recordDropped(batch.size)
                        throw e
                    } finally {
                        featureCosts.finishSynchronous(
                            FeatureCostOperation.LOG_SHIP_BATCH,
                            cost,
                            outcome = outcome,
                            workUnits = batch.size.toLong(),
                            workBytes = if (featureCosts.recordingEnabled) {
                                boundedUtf8Bytes(batch, SHIP_WORK_BYTES_MAX)
                            } else {
                                0L
                            },
                        )
                    }
                }
            } catch (e: Exception) {
                // Closing a run deliberately breaks its transport. Do not leak that expected exception
                // into a replacement generation's newly captured log stream.
                if (run.isOpen()) {
                    val reason = describeFailure(e, target.host)
                    run.markFailure(reason)
                    Log.w(
                        TAG,
                        "${LogShipEndpoint.scheme(target.protocol)} ${target.host}:${target.port}: $reason",
                    )
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

    /**
     * A failure reason a reader can act on. `Socket.connect` on an unresolved address throws
     * `UnknownHostException` whose message *is* the hostname, so the warning used to render as
     * "syslog collector:514: collector" — it named the destination and no fault whatsoever. Any
     * message that uninformative is replaced by the exception type.
     */
    private fun describeFailure(e: Throwable, host: String): String {
        val message = e.message?.trim().orEmpty()
        if (message.isNotEmpty() && !message.equals(host, ignoreCase = true)) return message
        return "${e.javaClass.simpleName} ($host)"
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
        private const val SHIP_WORK_BYTES_MAX = 4L * 1024 * 1024
        private const val POLL_SECONDS = 5L
        private const val SINK_BACKOFF_MS = 5_000L
        internal const val NETWORK_TIMEOUT_MS = 5_000
        internal const val DNS_TIMEOUT_MS = 2_000L

        /**
         * RFC5426 obliges a receiver to accept only 480 bytes and recommends 2048; 1024 stays well
         * inside any Ethernet path MTU so a long line is truncated here rather than silently dropped
         * by a middlebox that will not fragment it.
         */
        internal const val UDP_DATAGRAM_MAX_BYTES = 1024

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

/**
 * Production transports. Creation never blocks; [LogSink.connect] begins only after run attachment.
 * Internal rather than private so the transport tests can put real bytes on a real loopback socket —
 * an in-memory fake sink cannot tell TCP from UDP, which is how a TCP-only "syslog" transport aimed
 * at the UDP default port passed every test it had.
 */
internal object NetworkLogSinkFactory : LogSinkFactory {
    override fun create(target: LogShipTarget, encode: (String) -> String): LogSink =
        when (target.protocol) {
            LogShipEndpoint.HTTP -> HttpLogSink(target, encode)
            LogShipEndpoint.SYSLOG_UDP -> UdpSyslogLogSink(target, encode)
            else -> TcpSyslogLogSink(target, encode)
        }
}

/** RFC6587 non-transparent framing: newline-delimited RFC5424 frames on a stream. */
private class TcpSyslogLogSink(
    private val target: LogShipTarget,
    private val encode: (String) -> String,
) : LogSink {
    // A java.net.Socket is single-use: a failed connect leaves it unusable, so each candidate needs
    // its own. Volatile because close() can arrive from another thread mid-connect.
    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false

    override fun connect() {
        // A stream transport reports failure, so walk every resolved address rather than trusting
        // the first: a dual-stack name whose collector listens on one family only must still work.
        var last: IOException? = null
        for (candidate in LogShipEndpoint.orderedCandidates(
            BoundedDns.resolveAll(target.host, LogShipper.DNS_TIMEOUT_MS),
        )) {
            if (closed) throw IOException("closed")
            val attempt = Socket()
            // Publish before connecting: closing the run must be able to break a connect that is
            // still blocking, which is how stop() unblocks a worker parked on an unreachable sink.
            socket = attempt
            if (closed) {
                runCatching { attempt.close() }
                throw IOException("closed")
            }
            try {
                attempt.connect(InetSocketAddress(candidate, target.port), LogShipper.NETWORK_TIMEOUT_MS)
                attempt.tcpNoDelay = true
                return
            } catch (e: IOException) {
                runCatching { attempt.close() }
                last = e
            }
        }
        throw last ?: IOException("no address resolved for ${target.host}")
    }

    override fun send(lines: List<String>) {
        val active = socket ?: throw IOException("tcp sink used before connect")
        val out = active.getOutputStream()
        for (line in lines) out.write(encode(line).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    override fun close() {
        closed = true
        runCatching { socket?.close() }
    }
}

/**
 * RFC5426 UDP syslog: one datagram per message, no framing delimiter, and no delivery signal at all.
 *
 * [connect] resolves the name and connects the socket, which buys the only two failure signals UDP
 * offers — a resolution error up front, and the kernel surfacing ICMP port-unreachable on a later
 * send instead of discarding every datagram in silence.
 */
private class UdpSyslogLogSink(
    private val target: LogShipTarget,
    private val encode: (String) -> String,
) : LogSink {
    private val socket = DatagramSocket()
    private var address: InetAddress? = null

    override fun connect() {
        // UDP has no failure signal to fall back on: a datagram sent to an address whose collector
        // is not listening succeeds locally and vanishes. Ordering is therefore the only lever, so
        // take the first candidate rather than "the first the platform happened to return".
        val resolved = LogShipEndpoint.orderedCandidates(
            BoundedDns.resolveAll(target.host, LogShipper.DNS_TIMEOUT_MS),
        ).first()
        socket.connect(resolved, target.port)
        address = resolved
    }

    /** The address datagrams are actually going to, so the status line can name it. */
    fun resolvedAddress(): InetAddress? = address

    override fun send(lines: List<String>) {
        val destination = address ?: throw IOException("udp sink used before connect")
        for (line in lines) {
            // The trailing newline is stream framing (RFC6587). Inside a datagram the length is the
            // frame, so it is pure noise — and several collectors surface it as a trailing blank line.
            val bytes = truncateUtf8(encode(line).trimEnd('\n'), LogShipper.UDP_DATAGRAM_MAX_BYTES)
            socket.send(DatagramPacket(bytes, bytes.size, destination, target.port))
        }
    }

    override fun close() = socket.close()
}

/** Marks a line the datagram cap cut short, so a truncated record can never be read as a whole one. */
private const val TRUNCATION_MARK = "…"

/**
 * Encode [text] into at most [maxBytes] UTF-8 bytes, cutting on a character boundary. Splitting a
 * multi-byte sequence would corrupt the frame rather than just its tail: a receiver that fails to
 * decode the datagram discards the whole record, losing the part that fitted too.
 */
internal fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return bytes
    val mark = TRUNCATION_MARK.toByteArray(Charsets.UTF_8)
    // A continuation byte is 10xxxxxx; step back to the start of the sequence it belongs to.
    fun boundary(limit: Int): Int {
        var end = limit.coerceIn(0, bytes.size)
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return end
    }
    if (maxBytes <= mark.size) return bytes.copyOf(boundary(maxBytes))
    return bytes.copyOf(boundary(maxBytes - mark.size)) + mark
}

private class HttpLogSink(
    private val target: LogShipTarget,
    private val encode: (String) -> String,
) : LogSink {
    @Volatile private var closed = false
    private var connection: HttpURLConnection? = null

    // Resolved at connect and pinned for the run, so every POST targets the one address proven to
    // answer. Leaving the hostname in the URL would also hand HttpURLConnection its own unbounded
    // second lookup, which is exactly what the bounded resolver exists to prevent.
    @Volatile private var url: String = ""

    override fun connect() {
        if (closed) throw IOException("closed")
        // HTTP reports failure, so walk the candidates and keep the first that answers at all.
        var last: IOException? = null
        for (candidate in LogShipEndpoint.orderedCandidates(
            BoundedDns.resolveAll(target.host, LogShipper.DNS_TIMEOUT_MS),
        )) {
            if (closed) throw IOException("closed")
            val attempt = "http://${LogShipEndpoint.urlHost(candidate.hostAddress)}:${target.port}/"
            try {
                Socket().use {
                    it.connect(InetSocketAddress(candidate, target.port), LogShipper.NETWORK_TIMEOUT_MS)
                }
                url = attempt
                return
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("no address resolved for ${target.host}")
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
