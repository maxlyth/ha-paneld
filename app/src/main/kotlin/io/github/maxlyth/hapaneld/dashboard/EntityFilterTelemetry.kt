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
    private var trafficInstalled = false
    private var trafficBatches = 0L
    private var trafficSampleMs = 0L
    private var trafficFrames = 0L
    private var trafficPayloadBytes = 0L
    private var trafficEntityUpdates = 0L
    private var trafficObserverMicros = 0L
    private var trafficDroppedFrames = 0L

    @Synchronized fun started(entityIds: List<String>): Lease {
        val lease = Lease(++nextLease)
        owner = lease.id
        active = true
        configuredCount = entityIds.size
        configuredHash = EntityFilterProtocol.hash(entityIds)
        lastError = ""
        modifiedSubscriptions = 0; failures = 0; directFallbacks = 0
        resetTraffic()
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
        resetTraffic()
    }

    private fun resetTraffic() {
        DashboardTelemetry.reset()
        trafficInstalled = false
        trafficBatches = 0
        trafficSampleMs = 0
        trafficFrames = 0
        trafficPayloadBytes = 0
        trafficEntityUpdates = 0
        trafficObserverMicros = 0
        trafficDroppedFrames = 0
    }

    @Synchronized fun isActive(lease: Lease): Boolean = owner == lease.id && active
    @Synchronized fun dashboardFilterState(): Pair<Boolean, Int> = active to configuredCount
    @Synchronized fun subscriptionModified(lease: Lease) {
        if (owner == lease.id) modifiedSubscriptions++
    }
    @Synchronized fun failed(lease: Lease, kind: String) {
        if (owner == lease.id) { failures++; lastError = kind }
    }
    /** Interception could not be established and the renderer was safely held before opening HA.
     *  Preserve the configured set and error for diagnostics without claiming either an active filter
     *  or an unfiltered direct fallback. */
    @Synchronized fun held(lease: Lease, kind: String) {
        if (owner == lease.id) {
            active = false
            failures++
            lastError = kind
        }
    }
    @Synchronized fun directFallback(lease: Lease) {
        if (owner == lease.id) { directFallbacks++; active = false }
    }
    @Synchronized fun trafficObserverInstalled(lease: Lease) {
        if (owner == lease.id) {
            trafficInstalled = true
            DashboardTelemetry.installed()
        }
    }
    @Synchronized fun traffic(lease: Lease, batch: EntityFilterProtocol.TrafficBatch) {
        if (owner != lease.id || !trafficInstalled) return
        trafficBatches = saturatedAdd(trafficBatches, 1)
        trafficSampleMs = saturatedAdd(trafficSampleMs, batch.sampleMs)
        trafficFrames = saturatedAdd(trafficFrames, batch.frames)
        trafficPayloadBytes = saturatedAdd(
            trafficPayloadBytes,
            batch.payloadBytes,
        )
        trafficEntityUpdates = saturatedAdd(
            trafficEntityUpdates,
            batch.entityUpdates,
        )
        trafficObserverMicros = saturatedAdd(
            trafficObserverMicros,
            batch.observerMicros,
        )
        trafficDroppedFrames = saturatedAdd(
            trafficDroppedFrames,
            batch.droppedFrames,
        )
        DashboardTelemetry.record(batch)
    }

    @Synchronized fun json(): String = "{" +
        "\"active\":$active," +
        "\"mode\":\"native_socket\"," +
        "\"entityCount\":$configuredCount," +
        "\"filterHash\":\"$configuredHash\"," +
        "\"modifiedSubscriptions\":$modifiedSubscriptions," +
        "\"failures\":$failures,\"directFallbacks\":$directFallbacks," +
        "\"lastError\":\"$lastError\"," +
        "\"traffic\":{\"installed\":$trafficInstalled,\"batches\":$trafficBatches," +
        "\"sampleMs\":$trafficSampleMs,\"frames\":$trafficFrames," +
        "\"frameChars\":$trafficPayloadBytes,\"payloadBytes\":$trafficPayloadBytes," +
        "\"entityUpdates\":$trafficEntityUpdates," +
        "\"processingMicros\":$trafficObserverMicros,\"observerMicros\":$trafficObserverMicros," +
        "\"droppedFrames\":$trafficDroppedFrames}}"

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right >= Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
}
