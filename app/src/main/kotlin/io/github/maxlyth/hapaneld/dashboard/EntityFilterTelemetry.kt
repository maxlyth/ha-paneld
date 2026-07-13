package io.github.maxlyth.hapaneld.dashboard

/** Process-wide counters surfaced through `/api/v1/perf` and the filter status endpoint. */
object EntityFilterTelemetry {
    class Lease internal constructor(internal val id: Long)

    private var nextLease = 0L
    private var owner = 0L
    private var active = false
    private var configuredCount = 0
    private var configuredHash = ""
    private var lastError = ""
    private var modifiedSubscriptions = 0L
    private var failures = 0L
    private var directFallbacks = 0L

    @Synchronized fun started(entityIds: List<String>): Lease {
        val lease = Lease(++nextLease)
        owner = lease.id
        active = true
        configuredCount = entityIds.size
        configuredHash = EntityFilterProtocol.hash(entityIds)
        lastError = ""
        modifiedSubscriptions = 0; failures = 0; directFallbacks = 0
        return lease
    }

    /** Establish a new inactive owner, superseding callbacks from any previous renderer activity. */
    @Synchronized fun stopped(): Lease {
        val lease = Lease(++nextLease)
        owner = lease.id
        reset()
        return lease
    }

    /** Release only the activity that still owns the public runtime snapshot. */
    @Synchronized fun stop(lease: Lease) {
        if (owner != lease.id) return
        owner = 0L
        reset()
    }

    private fun reset() {
        active = false
        configuredCount = 0
        configuredHash = ""
        lastError = ""
        modifiedSubscriptions = 0
        failures = 0
        directFallbacks = 0
    }

    @Synchronized fun isActive(lease: Lease): Boolean = owner == lease.id && active
    @Synchronized fun subscriptionModified(lease: Lease) {
        if (owner == lease.id) modifiedSubscriptions++
    }
    @Synchronized fun failed(lease: Lease, kind: String) {
        if (owner == lease.id) { failures++; lastError = kind }
    }
    @Synchronized fun directFallback(lease: Lease) {
        if (owner == lease.id) { directFallbacks++; active = false }
    }

    @Synchronized fun json(): String = "{" +
        "\"active\":$active," +
        "\"mode\":\"native_socket\"," +
        "\"entityCount\":$configuredCount," +
        "\"filterHash\":\"$configuredHash\"," +
        "\"modifiedSubscriptions\":$modifiedSubscriptions," +
        "\"failures\":$failures,\"directFallbacks\":$directFallbacks," +
        "\"lastError\":\"$lastError\"}"
}
