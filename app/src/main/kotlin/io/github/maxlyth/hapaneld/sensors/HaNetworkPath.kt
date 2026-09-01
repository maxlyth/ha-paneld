package io.github.maxlyth.hapaneld.sensors

import org.json.JSONObject

/**
 * Why one attempt to reach Home Assistant produced no round trip.
 *
 * The split is the point of the feature: a stopped, restarting, overloaded or misconfigured server
 * must never be reported as packet loss, because the remedy is different. [NETWORK] is the only kind
 * that counts toward loss and the consecutive-failure alarm.
 */
internal enum class HaPathFailureKind(val wireValue: String) {
    /** The path itself: a probe that never came back, an unreachable host, a connect that timed out. */
    NETWORK("network"),

    /** Home Assistant answered, or the host did, but not usefully: closed socket, refused port, error frame. */
    SERVER("server"),

    /** Credentials were refused. The bytes got there and back; the sign-in is what failed. */
    AUTH("auth"),
}

/**
 * The shared Home Assistant socket's state, as the only thing entitled to start and stop a
 * measurement. Reported by the stream owner; nothing else may synthesise it.
 */
internal enum class HaSocketState(val wireValue: String) {
    /** No socket and none being sought: no demand, no credentials, or the stream has parked. */
    STOPPED("stopped"),

    /** Connecting, authenticating, subscribing or backing off between attempts. */
    CONNECTING("connecting"),

    /** Authenticated and subscribed: probes can be sent and the path can be described. */
    LIVE("live"),
}

/** The one classification every surface renders. Wire values are the `/health` and `/diag` tokens. */
internal enum class HaNetworkPathSeverity(val wireValue: String) {
    HEALTHY("healthy"),
    WARNING("warning"),
    SEVERE("severe"),
}

/**
 * Rolling classification of the network path between this panel and its configured Home Assistant.
 *
 * Measures the path the product actually uses: the round trip of a Home Assistant WebSocket `ping`
 * on the app's own authenticated socket. No ICMP, no subprocess, no root. Pure and time-injected in
 * the `HaLifecycle` style: every observation carries its own monotonic millisecond, so nothing here
 * can be satisfied or defeated by real elapsed time, and the bounded window ages state out by itself.
 *
 * Policy (the first rc3 rule, recorded in the funnel):
 *  - WARNING when the rolling p95 round trip exceeds [WARN_P95_MS];
 *  - SEVERE when the rolling p95 exceeds [SEVERE_P95_MS], after [SEVERE_CONSECUTIVE_FAILURES]
 *    consecutive network failures, or when network loss exceeds [SEVERE_LOSS_PERCENT] of the probes
 *    in the [WINDOW_MS] window;
 *  - one isolated miss or one isolated spike is diagnostic evidence only and never alarms.
 *
 * The "exceeds p95" and "exceeds loss" tests are both written as [exceedsShare]: at least two events
 * AND more than five percent of the window. Nearest-rank p95 over fewer than twenty samples IS the
 * maximum, so a threshold on the reported p95 alone would let a single spike right after connecting
 * raise a warning; the two-event floor is what keeps the isolated cases diagnostic. Once the window
 * holds twenty or more round trips the two tests coincide exactly with "nearest-rank p95 > T".
 *
 * Retained: bounded samples only. Never a hostname, address, SSID or raw frame.
 */
internal class HaNetworkPath(
    private val windowMs: Long = WINDOW_MS,
    private val maxSamples: Int = MAX_SAMPLES,
    private val warnP95Ms: Long = WARN_P95_MS,
    private val severeP95Ms: Long = SEVERE_P95_MS,
    private val severeConsecutiveFailures: Int = SEVERE_CONSECUTIVE_FAILURES,
) {
    private enum class Outcome { ROUND_TRIP, NETWORK_FAILURE, SERVER_FAILURE, AUTH_FAILURE }

    private class Sample(val atMs: Long, val outcome: Outcome, val rttMs: Long)

    private val samples = ArrayDeque<Sample>()
    private var measuring = false
    private var socketLive = false
    private var consecutiveNetworkFailures = 0
    private var lastRoundTripAtMs = -1L

    /**
     * Follow the shared socket's own state. Measurement is owned by an AUTHENTICATED socket, not by
     * the demand for one: until the stream has actually reached [HaSocketState.LIVE] there is no
     * Home Assistant application path to describe, so a panel that is still authenticating — or one
     * whose stream has parked on a refused sign-in or repeated protocol failures — reports nothing
     * rather than a verdict it cannot support.
     *
     * [HaSocketState.CONNECTING] deliberately does NOT stop measurement once a socket has been held.
     * Reconnect attempts are exactly when a broken path must still be reported: dropping the verdict
     * there would hide "severely degraded" for the whole outage it exists to explain, and would
     * discard the very failures that prove it. The distinction is published instead
     * ([Snapshot.socketLive]) so a reader can tell a live measurement from one being re-established.
     *
     * [HaSocketState.STOPPED] discards the samples, so a socket re-demanded hours later starts clean
     * instead of replaying a stale verdict.
     */
    fun onSocketState(state: HaSocketState) {
        socketLive = state == HaSocketState.LIVE
        when (state) {
            HaSocketState.LIVE -> measuring = true
            HaSocketState.CONNECTING -> Unit
            HaSocketState.STOPPED -> {
                measuring = false
                samples.clear()
                consecutiveNetworkFailures = 0
                lastRoundTripAtMs = -1L
            }
        }
    }

    /** One probe answered: [rttMs] is the send-to-decode round trip on the monotonic clock. */
    fun onRoundTrip(nowMs: Long, rttMs: Long) {
        record(Sample(nowMs, Outcome.ROUND_TRIP, rttMs.coerceAtLeast(0L)))
        consecutiveNetworkFailures = 0
        lastRoundTripAtMs = nowMs
    }

    /** One attempt failed for the reason [kind]; only [HaPathFailureKind.NETWORK] is loss. */
    fun onFailure(nowMs: Long, kind: HaPathFailureKind) {
        when (kind) {
            HaPathFailureKind.NETWORK -> {
                record(Sample(nowMs, Outcome.NETWORK_FAILURE, -1L))
                consecutiveNetworkFailures++
            }
            // A server or sign-in failure is neither a hit nor a miss on the path: it neither extends
            // nor resets the consecutive-network-failure count, because it says nothing about the path.
            HaPathFailureKind.SERVER -> record(Sample(nowMs, Outcome.SERVER_FAILURE, -1L))
            HaPathFailureKind.AUTH -> record(Sample(nowMs, Outcome.AUTH_FAILURE, -1L))
        }
    }

    private fun record(sample: Sample) {
        samples.addLast(sample)
        while (samples.size > maxSamples) samples.removeFirst()
    }

    /**
     * One atomic rendered tuple for [nowMs]. Samples older than the window are dropped first, which is
     * the only ageing mechanism: recovery is not an event, it is the bad samples leaving the window.
     */
    fun snapshot(nowMs: Long): Snapshot {
        while (samples.isNotEmpty() && samples.first().atMs < nowMs - windowMs) samples.removeFirst()
        val roundTrips = samples.filter { it.outcome == Outcome.ROUND_TRIP }
        val rtts = roundTrips.map { it.rttMs }
        val sorted = rtts.sorted()
        val networkFailures = samples.count { it.outcome == Outcome.NETWORK_FAILURE }
        val serverFailures = samples.count { it.outcome == Outcome.SERVER_FAILURE }
        val authFailures = samples.count { it.outcome == Outcome.AUTH_FAILURE }
        val probes = rtts.size + networkFailures
        // The run is a claim about the window, so it ages WITH the window: once no failure sample
        // survives, nothing evidences a run and it is zeroed rather than carried indefinitely. A run
        // whose older half has aged out but whose newest failure is still inside remains provable and
        // is deliberately kept — that is what carries a verdict across a slow reconnect ladder.
        if (networkFailures == 0) consecutiveNetworkFailures = 0
        val consecutive = consecutiveNetworkFailures
        val severity = when {
            !measuring -> HaNetworkPathSeverity.HEALTHY
            consecutive >= severeConsecutiveFailures -> HaNetworkPathSeverity.SEVERE
            exceedsShare(networkFailures, probes) -> HaNetworkPathSeverity.SEVERE
            exceedsShare(rtts.count { it > severeP95Ms }, rtts.size) -> HaNetworkPathSeverity.SEVERE
            exceedsShare(rtts.count { it > warnP95Ms }, rtts.size) -> HaNetworkPathSeverity.WARNING
            else -> HaNetworkPathSeverity.HEALTHY
        }
        return Snapshot(
            measuring = measuring,
            socketLive = socketLive,
            severity = severity,
            windowMs = windowMs,
            probes = probes,
            roundTrips = rtts.size,
            networkFailures = networkFailures,
            serverFailures = serverFailures,
            authFailures = authFailures,
            p50Ms = nearestRank(sorted, 0.50),
            p95Ms = nearestRank(sorted, 0.95),
            maxMs = sorted.lastOrNull() ?: -1L,
            jitterMs = jitter(rtts),
            lossPercent = if (probes == 0) 0.0 else networkFailures * 100.0 / probes,
            consecutiveFailures = consecutive,
            lastRoundTripAgeMs = if (lastRoundTripAtMs < 0L) -1L else (nowMs - lastRoundTripAtMs).coerceAtLeast(0L),
        )
    }

    /**
     * The one atomic tuple a surface may render. Every millisecond field is `-1` when there is no
     * round trip in the window, never `0`, so "fast" and "unmeasured" cannot be confused.
     */
    data class Snapshot(
        val measuring: Boolean,
        /** Whether the authenticated socket is up right now, as opposed to being re-established. */
        val socketLive: Boolean,
        val severity: HaNetworkPathSeverity,
        val windowMs: Long,
        val probes: Int,
        val roundTrips: Int,
        val networkFailures: Int,
        val serverFailures: Int,
        val authFailures: Int,
        val p50Ms: Long,
        val p95Ms: Long,
        val maxMs: Long,
        val jitterMs: Long,
        val lossPercent: Double,
        val consecutiveFailures: Int,
        val lastRoundTripAgeMs: Long,
    ) {
        /** True while a surface has a warning to show; the two non-healthy severities. */
        val degraded: Boolean get() = measuring && severity != HaNetworkPathSeverity.HEALTHY

        /**
         * The key surfaces are re-poked on. Numbers move with every probe and the polling surfaces
         * read them anyway; only a change of verdict is worth waking the native chip for.
         */
        val reportableKey: Pair<Boolean, HaNetworkPathSeverity> get() = measuring to severity
    }

    companion object {
        /** The rolling window every rate and percentile is judged over. */
        const val WINDOW_MS = 5L * 60_000L

        /**
         * Retention bound. At [PROBE_INTERVAL_MS] a window holds thirty probes; the bound leaves room
         * for the reconnect attempts a bad five minutes adds (backoff runs 1 s doubling to 60 s, so at
         * most about ten) plus server-side failures, and drops the oldest beyond that.
         */
        const val MAX_SAMPLES = 64

        /**
         * How often the socket owner sends a probe while live, regardless of entity traffic.
         *
         * The arithmetic this was chosen for: 300 s / 10 s = 30 probes per window. One miss is
         * 3.3 % loss and one spike is a p95 rank of 29 of 30 that ignores it, so both the loss rule
         * and the p95 rule need TWO bad probes before they fire; two misses are 6.7 %, over the 5 %
         * line. The two-consecutive-timeout rule fires at any window size. A 15 s cadence would give
         * twenty probes and the same two-probe property; anything slower than 20 s would let one miss
         * exceed 5 % on its own, which the policy forbids.
         */
        const val PROBE_INTERVAL_MS = 10_000L

        const val WARN_P95_MS = 100L
        const val SEVERE_P95_MS = 1_000L
        const val SEVERE_LOSS_PERCENT = 5.0
        const val SEVERE_CONSECUTIVE_FAILURES = 2

        /**
         * "More than five percent of the window, and at least two events." The floor is what keeps an
         * isolated miss or spike diagnostic; the share is what "p95 > T" and "loss > 5 %" both reduce to.
         */
        fun exceedsShare(count: Int, total: Int): Boolean =
            count >= 2 && count * 100.0 > SEVERE_LOSS_PERCENT * total

        /** Nearest-rank percentile over an ascending list; `-1` for an empty one. */
        fun nearestRank(sorted: List<Long>, fraction: Double): Long {
            if (sorted.isEmpty()) return -1L
            val rank = kotlin.math.ceil(sorted.size * fraction).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }

        /** Mean absolute difference between successive round trips (RFC 3550 style), `0` under two. */
        fun jitter(rttsInOrder: List<Long>): Long {
            if (rttsInOrder.size < 2) return 0L
            var total = 0L
            for (i in 1 until rttsInOrder.size) total += kotlin.math.abs(rttsInOrder[i] - rttsInOrder[i - 1])
            return total / (rttsInOrder.size - 1)
        }
    }
}

/**
 * The wording and machine projections of one [HaNetworkPath.Snapshot]. Pure, so every surface renders
 * the same tuple the same way and the contract tests can pin the text without a service.
 *
 * None of these ever include a host, address, SSID, BSSID or an individual sample.
 */
internal object HaNetworkPathPresentation {
    /** The panel-facing headline: one short phrase readable from across a room. */
    const val PANEL_TEXT = "HA network slow"

    /** `/health` tokens, or empty when there is nothing to report (unreportable, exactly like `ha=`). */
    fun healthToken(snap: HaNetworkPath.Snapshot?): String {
        if (snap == null || !snap.measuring) return ""
        return " ha_net=${snap.severity.wireValue} ha_net_p95=${snap.p95Ms} ha_net_n=${snap.probes} " +
            "ha_net_miss=${snap.networkFailures} ha_net_age=${snap.lastRoundTripAgeMs}"
    }

    /** The Runtime diagnostics row, or null when no service owns the monitor. */
    fun statusText(snap: HaNetworkPath.Snapshot?): String? {
        if (snap == null) return null
        if (!snap.measuring) return NOT_MEASURED
        return when (snap.severity) {
            HaNetworkPathSeverity.HEALTHY -> "healthy; ${evidence(snap)}"
            HaNetworkPathSeverity.WARNING -> "slow; ${evidence(snap)}"
            HaNetworkPathSeverity.SEVERE -> "severely degraded; ${evidence(snap)}"
        }
    }

    const val NOT_MEASURED = "not measured; this panel holds no authenticated Home Assistant socket"

    /**
     * The terse numbers every surface shares: window, probe count, p95, misses and, when the window
     * is empty, how long ago the last reply was. An empty window is two different facts: a socket
     * that has only just connected, and a stream that parked (a refused sign-in, repeated protocol
     * failures) and stopped probing. Only the age tells them apart, so an empty window with a
     * remembered reply says so rather than reading like a fresh connect.
     */
    fun evidence(snap: HaNetworkPath.Snapshot): String {
        val minutes = snap.windowMs / 60_000L
        if (snap.probes == 0) {
            return if (snap.lastRoundTripAgeMs < 0L) "no probes yet in the last $minutes min"
            else "no probe answered in the last $minutes min; last reply ${age(snap.lastRoundTripAgeMs)} ago"
        }
        val p95 = if (snap.p95Ms < 0L) "no reply" else "p95 ${ms(snap.p95Ms)}"
        val misses = when (snap.networkFailures) {
            0 -> "no misses"
            else -> "${snap.networkFailures} of ${snap.probes} probes missed"
        }
        return "$p95, $misses in the last $minutes min"
    }

    /**
     * The `:8888` banner copy, rendered client-side by `buildwatch.js` from the `/health` tokens so
     * it retracts itself on recovery. Kept here as the single source the contract test compares the
     * script against, so the two cannot drift.
     */
    const val BANNER_WARNING_PREFIX = "⚠ The network path to Home Assistant is slow"
    const val BANNER_SEVERE_PREFIX = "⚠ The network path to Home Assistant is severely degraded"
    const val BANNER_ADVICE =
        "Dashboard actions will lag. Check the Wi-Fi path between this panel and Home Assistant before blaming the panel."

    /** One `/diag` line: classified state and terse aggregates only. */
    fun diagnosticLine(snap: HaNetworkPath.Snapshot?): String {
        if (snap == null) return "[ha-network] state=unowned"
        if (!snap.measuring) return "[ha-network] state=idle measuring=false"
        return "[ha-network] state=${snap.severity.wireValue} measuring=true " +
            "socket=${if (snap.socketLive) "live" else "reconnecting"} window=${snap.windowMs / 60_000L}m " +
            "probes=${snap.probes} round_trips=${snap.roundTrips} p50=${snap.p50Ms} p95=${snap.p95Ms} " +
            "max=${snap.maxMs} jitter=${snap.jitterMs} loss=${"%.1f".format(java.util.Locale.ROOT, snap.lossPercent)}% " +
            "consecutive=${snap.consecutiveFailures} server_errors=${snap.serverFailures} auth_errors=${snap.authFailures} " +
            "last_reply_age=${snap.lastRoundTripAgeMs}"
    }

    /** The `/api/v1/status` object, emitted unconditionally so an absent field cannot read as healthy. */
    fun statusJson(snap: HaNetworkPath.Snapshot?): String {
        val json = JSONObject()
            .put("measuring", snap?.measuring ?: false)
            .put("state", if (snap == null || !snap.measuring) "idle" else snap.severity.wireValue)
        if (snap != null && snap.measuring) {
            json.put("socket", if (snap.socketLive) "live" else "reconnecting")
                .put("window_ms", snap.windowMs)
                .put("probes", snap.probes)
                .put("round_trips", snap.roundTrips)
                .put("network_failures", snap.networkFailures)
                .put("server_failures", snap.serverFailures)
                .put("auth_failures", snap.authFailures)
                .put("p50_ms", snap.p50Ms)
                .put("p95_ms", snap.p95Ms)
                .put("max_ms", snap.maxMs)
                .put("jitter_ms", snap.jitterMs)
                .put("loss_percent", Math.round(snap.lossPercent * 10.0) / 10.0)
                .put("consecutive_failures", snap.consecutiveFailures)
                .put("last_round_trip_age_ms", snap.lastRoundTripAgeMs)
        }
        return json.toString()
    }

    /** A remembered age in the coarsest honest unit: seconds under a minute, whole minutes after. */
    fun age(ms: Long): String = if (ms < 60_000L) "${ms / 1_000L} s" else "${ms / 60_000L} min"

    /** The likely-cause code `DashboardTelemetry` ranks first while the path is degraded. */
    const val LIKELY_CAUSE = "ha_network_path"

    private fun ms(value: Long): String = "${"%,d".format(java.util.Locale.ROOT, value)} ms"
}
