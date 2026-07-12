package io.github.maxlyth.hapaneld.mqtt

/**
 * Registry-driven state convergence. Every state-bearing MQTT entity supplies one authoritative
 * observation; commands, reconnects, local events and periodic audits all flow through this class.
 * Publication state advances only after the broker acknowledges the exact generation that was sent.
 */
class StateConverger(
    private val sender: (topic: String, payload: String, retain: Boolean, done: (Boolean) -> Unit) -> Unit,
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
    )

    private val channels = linkedMapOf<String, Runtime>()

    @Synchronized
    fun register(channel: Channel) {
        check(channel.key !in channels) { "duplicate state channel ${channel.key}" }
        channels[channel.key] = Runtime(channel)
    }

    fun reconcile(key: String, force: Boolean = false) {
        val runtime = synchronized(this) { channels[key] } ?: return
        val payload = when (val observation = runCatching { runtime.channel.observe() }.getOrDefault(Observation.Unknown)) {
            is Observation.Known -> observation.payload.also {
                synchronized(this) { runtime.unknown = false }
            }
            Observation.Unknown, Observation.Unavailable -> {
                synchronized(this) {
                    runtime.unknown = true
                    runtime.dirty = false
                    runtime.inFlight = false
                }
                return
            }
        }

        val generation: Long
        synchronized(this) {
            if (runtime.inFlight && runtime.sent == payload) return
            if (!force && !runtime.dirty && runtime.acknowledged?.let { runtime.channel.equivalent(it, payload) } == true) return
            generation = ++runtime.generation
            runtime.sent = payload
            runtime.inFlight = true
            runtime.dirty = true
        }

        sender(runtime.channel.topic, payload, runtime.channel.retain) { success ->
            synchronized(this) {
                if (generation != runtime.generation) return@synchronized
                runtime.inFlight = false
                if (success) {
                    runtime.acknowledged = payload
                    runtime.dirty = false
                } else {
                    runtime.dirty = true
                }
            }
        }
    }

    fun reconcileAll(force: Boolean = false) {
        val keys = synchronized(this) { channels.keys.toList() }
        keys.forEach { reconcile(it, force) }
    }

    @Synchronized
    fun markAllDirty() {
        channels.values.forEach { it.dirty = true; it.inFlight = false }
    }

    @Synchronized
    fun keys(): Set<String> = channels.keys.toSet()

    data class Status(val channels: Int, val dirty: Int, val inFlight: Int, val unknown: Int)

    @Synchronized
    fun status(): Status = Status(
        channels = channels.size,
        dirty = channels.values.count { it.dirty },
        inFlight = channels.values.count { it.inFlight },
        unknown = channels.values.count { it.unknown },
    )

    companion object {
        fun numericDeadband(deadband: Double): (String, String) -> Boolean = { acknowledged, observed ->
            val old = acknowledged.toDoubleOrNull()
            val new = observed.toDoubleOrNull()
            old != null && new != null && kotlin.math.abs(new - old) < deadband
        }
    }
}
