package io.github.maxlyth.hapaneld.dashboard

import java.util.concurrent.atomic.AtomicLong

/** Process-wide counters surfaced through `/api/v1/perf` and the filter status endpoint. */
object EntityFilterTelemetry {
    @Volatile private var active = false
    @Volatile private var configuredCount = 0
    @Volatile private var configuredHash = ""
    @Volatile private var lastError = ""
    private val modifiedSubscriptions = AtomicLong()
    private val failures = AtomicLong()
    private val directFallbacks = AtomicLong()

    fun started(entityIds: List<String>) {
        active = true
        configuredCount = entityIds.size
        configuredHash = EntityFilterProtocol.hash(entityIds)
        lastError = ""
        modifiedSubscriptions.set(0); failures.set(0); directFallbacks.set(0)
    }

    fun stopped() {
        active = false
        configuredCount = 0
        configuredHash = ""
        lastError = ""
        modifiedSubscriptions.set(0)
        failures.set(0)
        directFallbacks.set(0)
    }
    fun isActive(): Boolean = active
    fun subscriptionModified() { modifiedSubscriptions.incrementAndGet() }
    fun failed(kind: String) { failures.incrementAndGet(); lastError = kind }
    fun directFallback() { directFallbacks.incrementAndGet(); active = false }

    fun json(): String = "{" +
        "\"active\":$active," +
        "\"mode\":\"native_socket\"," +
        "\"entityCount\":$configuredCount," +
        "\"filterHash\":\"$configuredHash\"," +
        "\"modifiedSubscriptions\":${modifiedSubscriptions.get()}," +
        "\"failures\":${failures.get()},\"directFallbacks\":${directFallbacks.get()}," +
        "\"lastError\":\"$lastError\"}"
}
