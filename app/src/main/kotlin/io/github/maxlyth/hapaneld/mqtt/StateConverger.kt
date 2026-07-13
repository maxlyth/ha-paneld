package io.github.maxlyth.hapaneld.mqtt

/**
 * Registry-driven state convergence. Every state-bearing MQTT entity supplies one authoritative
 * observation; commands, reconnects, local events and periodic audits all flow through this class.
 * Publication state advances only after the broker acknowledges the exact generation that was sent.
 */
class StateConverger(
    private val sender: (topic: String, payload: String, retain: Boolean, done: (Boolean) -> Unit) -> Unit,
    private val schedule: (() -> Unit) -> Unit = ::dispatch,
) {
    sealed interface Observation {
        data class Known(val payload: String) : Observation
        data object Unknown : Observation
        data object Unavailable : Observation
    }

    data class Channel(
        val key: String,
        val topic: String,
        val retain: Boolean = true,
        val observe: () -> Observation,
        val equivalent: (acknowledged: String, observed: String) -> Boolean = String::equals,
    )

    private data class Runtime(
        val channel: Channel,
        var acknowledged: String? = null,
        var sent: String? = null,
        var generation: Long = 0,
        var inFlight: Boolean = false,
        var dirty: Boolean = true,
        var unknown: Boolean = false,
        var sentAtMs: Long = 0,
    )

    private val channels = linkedMapOf<String, Runtime>()
    private var successes = 0L
    private var failures = 0L
    private var closed = false

    @Synchronized
    fun register(channel: Channel) {
        check(!closed) { "state converger is closed" }
        check(channel.key !in channels) { "duplicate state channel ${channel.key}" }
        channels[channel.key] = Runtime(channel)
    }

    fun reconcile(key: String, force: Boolean = false) {
        val runtime = synchronized(this) { if (closed) null else channels[key] } ?: return
        val payload = when (val observation = runCatching { runtime.channel.observe() }.getOrDefault(Observation.Unknown)) {
            is Observation.Known -> observation.payload.also {
                synchronized(this) {
                    if (closed) return
                    runtime.unknown = false
                }
            }
            Observation.Unknown, Observation.Unavailable -> {
                synchronized(this) {
                    if (closed) return
                    runtime.unknown = true
                    // An observation failure cannot cancel a publish already admitted to the bounded
                    // outbox. Keep its slot until the callback arrives; otherwise repeated unknown reads
                    // can make the actual MQTT in-flight count exceed MAX_IN_FLIGHT.
                    if (!runtime.inFlight) runtime.dirty = false
                }
                return
            }
        }

        val generation: Long
        synchronized(this) {
            if (closed) return
            if (runtime.inFlight && runtime.sent == payload) return
            if (!force && !runtime.dirty && runtime.acknowledged?.let { runtime.channel.equivalent(it, payload) } == true) return
            if (channels.values.count { it.inFlight } >= MAX_IN_FLIGHT) return
            generation = ++runtime.generation
            runtime.sent = payload
            runtime.inFlight = true
            runtime.dirty = true
            runtime.sentAtMs = System.currentTimeMillis()
        }

        sender(runtime.channel.topic, payload, runtime.channel.retain) { success ->
            var pump = false
            synchronized(this) {
                if (closed || generation != runtime.generation) return@synchronized
                runtime.inFlight = false
                if (success) {
                    successes++
                    runtime.acknowledged = payload
                    runtime.dirty = false
                    pump = true
                } else {
                    failures++
                    runtime.dirty = true
                }
            }
            if (pump) schedule { reconcileDirty() }
        }
    }

    fun reconcileAll(force: Boolean = false) {
        val keys = synchronized(this) { if (closed) emptyList() else channels.keys.toList() }
        keys.forEach { reconcile(it, force) }
    }

    /** Drain only channels already queued/dirty; do not turn ACK completion into a fresh sensor poll. */
    fun reconcileDirty() {
        val keys = synchronized(this) {
            if (closed) emptyList() else channels.filterValues { it.dirty && !it.inFlight }.keys.toList()
        }
        keys.forEach { reconcile(it) }
    }

    /**
     * Invalidate every acknowledgement from the previous broker connection. A QoS acknowledgement is
     * evidence about the connection that produced it, not permission to suppress publication forever:
     * a replacement broker/session may have no retained copy. Incrementing each generation also makes
     * completions from the superseded connection harmless if they arrive after reconnect.
     */
    @Synchronized
    fun markAllDirty() {
        if (closed) return
        channels.values.forEach {
            it.generation++
            it.acknowledged = null
            it.sent = null
            it.dirty = true
            it.inFlight = false
        }
    }

    /** Terminal owner boundary: reject queued audits and invalidate every late completion. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        channels.values.forEach {
            it.generation++
            it.inFlight = false
            it.dirty = false
        }
    }

    @Synchronized
    fun keys(): Set<String> = channels.keys.toSet()

    data class Status(
        val channels: Int,
        val dirty: Int,
        val inFlight: Int,
        val unknown: Int,
        val successes: Long,
        val failures: Long,
        val pending: List<String>,
    )

    @Synchronized
    fun status(): Status = Status(
        channels = channels.size,
        dirty = channels.values.count { it.dirty },
        inFlight = channels.values.count { it.inFlight },
        unknown = channels.values.count { it.unknown },
        successes = successes,
        failures = failures,
        pending = channels.values.filter { it.inFlight }.map {
            "${it.channel.key}:${((System.currentTimeMillis() - it.sentAtMs).coerceAtLeast(0) / 1000)}s"
        },
    )

    companion object {
        private const val MAX_IN_FLIGHT = 4
        private val PUMP = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "state-convergence").apply { isDaemon = true }
        }

        /** Serialize local UI/hardware notifications with acknowledgement-driven outbox pumping. */
        internal fun dispatch(task: () -> Unit) = PUMP.execute(task)

        fun numericDeadband(deadband: Double): (String, String) -> Boolean = { acknowledged, observed ->
            val old = acknowledged.toDoubleOrNull()
            val new = observed.toDoubleOrNull()
            old != null && new != null && kotlin.math.abs(new - old) < deadband
        }
    }
}
