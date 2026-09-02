package io.github.maxlyth.hapaneld.sensors

/**
 * The process-global read side of the layer-3 probe, in the [HaNetworkPathRuntime] shape.
 *
 * Identity-gated install and uninstall so a superseded service can never clear its successor's
 * monitor, and one atomic [snapshot] so no surface can assemble a reading from two moments.
 */
internal object PathProbeRuntime {
    @Volatile private var monitor: PathProbeMonitor? = null
    @Volatile private var clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    fun install(next: PathProbeMonitor, nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
        monitor = next
        clock = nowMs
    }

    /** Clear only if [owner] is still the installed monitor. Returns whether it actually cleared. */
    fun uninstall(owner: PathProbeMonitor): Boolean {
        if (monitor !== owner) return false
        monitor = null
        return true
    }

    fun snapshot(): PathProbeMonitor.Snapshot? = monitor?.snapshot(clock())

    /**
     * One `/diag` line. Always present so an absent line cannot be read as health, and terse: counts,
     * percentiles and the cadence, never an address and never an individual echo.
     */
    fun diagnosticLine(): String {
        val snap = snapshot() ?: return "[ha-path-probe] state=unowned"
        return when (snap.availability) {
            PathProbeAvailability.UNSUPPORTED ->
                "[ha-path-probe] state=unsupported detail=this platform refuses an ICMP socket to the app"
            PathProbeAvailability.UNPROVEN ->
                "[ha-path-probe] state=unproven bursts=${snap.bursts} sent=${snap.sent} received=0 " +
                    "detail=no echo answered yet, so silence is not counted as loss"
            PathProbeAvailability.PROVEN ->
                "[ha-path-probe] state=${snap.severity?.wireValue ?: "unknown"} bursts=${snap.bursts} " +
                    "sent=${snap.sent} received=${snap.received} " +
                    "loss=${"%.1f".format(java.util.Locale.ROOT, snap.lossPercent)}% " +
                    "p50=${snap.p50Ms} p95=${snap.p95Ms} max=${snap.maxMs} jitter=${snap.jitterMs} " +
                    "dead_bursts=${snap.consecutiveDeadBursts} interval=${snap.intervalMs / 1000L}s " +
                    "last_burst_age=${snap.lastBurstAgeMs}"
        }
    }

    /** The `/api/v1/status` object, emitted unconditionally so an absent field cannot read as healthy. */
    fun statusJson(): String {
        val snap = snapshot()
        val json = org.json.JSONObject()
            .put("available", snap?.availability?.name?.lowercase() ?: "unowned")
        if (snap != null && snap.availability != PathProbeAvailability.UNSUPPORTED) {
            json.put("state", snap.severity?.wireValue ?: "unproven")
                .put("bursts", snap.bursts)
                .put("echoes_sent", snap.sent)
                .put("echoes_received", snap.received)
                .put("loss_percent", snap.lossPercent)
                .put("p50_ms", snap.p50Ms)
                .put("p95_ms", snap.p95Ms)
                .put("max_ms", snap.maxMs)
                .put("jitter_ms", snap.jitterMs)
                .put("consecutive_dead_bursts", snap.consecutiveDeadBursts)
                .put("interval_ms", snap.intervalMs)
                .put("last_burst_age_ms", snap.lastBurstAgeMs)
        }
        return json.toString()
    }
}
