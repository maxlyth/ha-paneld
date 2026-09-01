package io.github.maxlyth.hapaneld.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic traces for the network-path classifier.
 *
 * The clock is owned entirely by the test: every observation is given an explicit millisecond, so no
 * assertion here can be satisfied by real elapsed time. Each trace names the field situation it
 * stands for, and the healthy baseline and the degraded case reproduce the figures recorded for the
 * rc3 policy (a loss-free congested baseline of 3-40 ms with one 61 ms peak, and a path with repeated
 * timeouts plus 2.2-5.9 s replies).
 */
class HaNetworkPathTest {
    private val path = HaNetworkPath()
    private val interval = HaNetworkPath.PROBE_INTERVAL_MS

    /** Replay [rtts] as successive probes [interval] apart starting at [startMs]; null is a timeout. */
    private fun replay(startMs: Long, rtts: List<Long?>): Long {
        var t = startMs
        for (rtt in rtts) {
            if (rtt == null) path.onFailure(t, HaPathFailureKind.NETWORK) else path.onRoundTrip(t, rtt)
            t += interval
        }
        return t
    }

    @Test fun nothingIsReportableUntilTheSocketIsWanted() {
        val snap = path.snapshot(1_000L)
        assertFalse(snap.measuring)
        assertFalse(snap.degraded)
        assertEquals(HaNetworkPathSeverity.HEALTHY, snap.severity)
        assertEquals(-1L, snap.p95Ms)
        assertEquals(0, snap.probes)
    }

    @Test fun theHealthyCongestedBaselineStaysHealthyWithAllNumbersRetained() {
        path.onSocketState(HaSocketState.LIVE)
        val baseline = listOf<Long?>(
            3, 5, 4, 12, 40, 8, 6, 3, 9, 15, 61, 7, 4, 5, 22, 3, 6, 8, 11, 4, 5, 3, 9, 30, 4, 6, 5, 3, 7, 4,
        )
        val end = replay(0L, baseline)
        val snap = path.snapshot(end)
        assertTrue(snap.measuring)
        assertEquals(HaNetworkPathSeverity.HEALTHY, snap.severity)
        assertEquals(30, snap.probes)
        assertEquals(30, snap.roundTrips)
        assertEquals(0, snap.networkFailures)
        assertEquals(61L, snap.maxMs)
        // Nearest rank: p50 is the 15th of 30 ascending, p95 the 29th, so the single 61 ms peak is
        // excluded from p95 and cannot alarm.
        assertEquals(6L, snap.p50Ms)
        assertEquals(40L, snap.p95Ms)
        assertEquals(0.0, snap.lossPercent, 0.0)
        assertTrue(snap.jitterMs > 0L)
        assertEquals(0, snap.consecutiveFailures)
    }

    @Test fun aSingleTransientSpikeRightAfterConnectingIsDiagnosticOnly() {
        path.onSocketState(HaSocketState.LIVE)
        // Three probes: nearest-rank p95 of three samples IS the maximum, which is exactly the trap.
        val end = replay(0L, listOf(8L, 450L, 9L))
        val snap = path.snapshot(end)
        assertEquals(450L, snap.p95Ms)
        assertEquals(450L, snap.maxMs)
        assertEquals(HaNetworkPathSeverity.HEALTHY, snap.severity)
    }

    @Test fun aSingleSpikeInAFullWindowIsDiagnosticOnly() {
        path.onSocketState(HaSocketState.LIVE)
        val end = replay(0L, List(29) { 10L } + listOf(2_500L))
        val snap = path.snapshot(end)
        assertEquals(2_500L, snap.maxMs)
        assertEquals(10L, snap.p95Ms)
        assertEquals(HaNetworkPathSeverity.HEALTHY, snap.severity)
    }

    @Test fun twoSpikesInAWindowAreAWarningAndTheP95ReportsThem() {
        path.onSocketState(HaSocketState.LIVE)
        val end = replay(0L, List(28) { 10L } + listOf(300L, 400L))
        val snap = path.snapshot(end)
        assertEquals(300L, snap.p95Ms)
        assertEquals(HaNetworkPathSeverity.WARNING, snap.severity)
    }

    @Test fun oneIsolatedTimeoutIsVisibleEvidenceButNeverAnAlarm() {
        path.onSocketState(HaSocketState.LIVE)
        // Five probes, one miss: 20 % of a tiny window, which a bare percentage would call severe.
        val end = replay(0L, listOf(8L, 9L, null, 7L, 8L))
        val snap = path.snapshot(end)
        assertEquals(1, snap.networkFailures)
        assertEquals(5, snap.probes)
        assertEquals(20.0, snap.lossPercent, 0.01)
        assertEquals(0, snap.consecutiveFailures)
        assertEquals(HaNetworkPathSeverity.HEALTHY, snap.severity)
    }

    @Test fun oneIsolatedTimeoutInAFullWindowIsUnderTheLossLine() {
        path.onSocketState(HaSocketState.LIVE)
        val end = replay(0L, List(15) { 10L } + listOf<Long?>(null) + List(14) { 10L })
        val snap = path.snapshot(end)
        assertEquals(30, snap.probes)
        assertEquals(1, snap.networkFailures)
        assertEquals(HaNetworkPathSeverity.HEALTHY, snap.severity)
    }

    @Test fun sustainedLatencyOverAHundredMillisecondsIsAWarningNotSevere() {
        path.onSocketState(HaSocketState.LIVE)
        val end = replay(0L, List(30) { 120L + (it % 5) * 40L })
        val snap = path.snapshot(end)
        assertTrue(snap.p95Ms > HaNetworkPath.WARN_P95_MS)
        assertTrue(snap.p95Ms <= HaNetworkPath.SEVERE_P95_MS)
        assertEquals(HaNetworkPathSeverity.WARNING, snap.severity)
        assertEquals(0, snap.networkFailures)
    }

    @Test fun theThresholdsAreStrictSoExactlyOnTheLineIsNotOverIt() {
        path.onSocketState(HaSocketState.LIVE)
        val atWarn = replay(0L, List(30) { HaNetworkPath.WARN_P95_MS })
        assertEquals(HaNetworkPath.WARN_P95_MS, path.snapshot(atWarn).p95Ms)
        assertEquals(HaNetworkPathSeverity.HEALTHY, path.snapshot(atWarn).severity)
        val severePath = HaNetworkPath().apply { onSocketState(HaSocketState.LIVE) }
        var t = 0L
        repeat(30) { severePath.onRoundTrip(t, HaNetworkPath.SEVERE_P95_MS); t += interval }
        assertEquals(HaNetworkPath.SEVERE_P95_MS, severePath.snapshot(t).p95Ms)
        assertEquals(HaNetworkPathSeverity.WARNING, severePath.snapshot(t).severity)
    }

    @Test fun repeatedMultiSecondRepliesWithTimeoutsMatchTheReportedDegradedCase() {
        path.onSocketState(HaSocketState.LIVE)
        // The reporter's trace: successful replies delayed 2.2-5.9 s interleaved with timeouts.
        val trace = listOf<Long?>(2_200L, null, 5_900L, 3_100L, null, 4_400L, 2_600L, null, 5_200L, 3_800L)
        val end = replay(0L, trace)
        val snap = path.snapshot(end)
        assertEquals(HaNetworkPathSeverity.SEVERE, snap.severity)
        assertEquals(3, snap.networkFailures)
        assertTrue(snap.p95Ms > HaNetworkPath.SEVERE_P95_MS)
        assertEquals(5_900L, snap.maxMs)
    }

    @Test fun repeatedMultiSecondRepliesAloneAreSevereWithoutAnyTimeout() {
        path.onSocketState(HaSocketState.LIVE)
        val end = replay(0L, List(30) { 2_200L + (it % 4) * 900L })
        val snap = path.snapshot(end)
        assertEquals(0, snap.networkFailures)
        assertEquals(HaNetworkPathSeverity.SEVERE, snap.severity)
    }

    @Test fun twoConsecutiveTimeoutsAreSevereAtAnyWindowSize() {
        path.onSocketState(HaSocketState.LIVE)
        replay(0L, listOf(9L, null))
        assertEquals(HaNetworkPathSeverity.HEALTHY, path.snapshot(2 * interval).severity)
        path.onFailure(2 * interval, HaPathFailureKind.NETWORK)
        val snap = path.snapshot(2 * interval)
        assertEquals(2, snap.consecutiveFailures)
        assertEquals(HaNetworkPathSeverity.SEVERE, snap.severity)
    }

    @Test fun twoConsecutiveMissesAreSevereEvenWhenTheFirstHasAgedOutOfTheWindow() {
        // The consecutive rule is the one that survives sparse failures: a reconnect ladder that backs
        // off past the window (or the harness's short window) leaves ONE miss inside it, which the
        // loss rule alone would call diagnostic. Two misses in a row are severe regardless.
        val sparse = HaNetworkPath(windowMs = 1_000L).apply { onSocketState(HaSocketState.LIVE) }
        sparse.onRoundTrip(0L, 10L)
        sparse.onFailure(100L, HaPathFailureKind.NETWORK)
        sparse.onFailure(2_100L, HaPathFailureKind.NETWORK)
        val snap = sparse.snapshot(2_100L)
        assertEquals("only the second miss is still inside the window", 1, snap.networkFailures)
        assertEquals(2, snap.consecutiveFailures)
        assertEquals(HaNetworkPathSeverity.SEVERE, snap.severity)
        // And one miss with the run broken by a round trip in between is not.
        val broken = HaNetworkPath(windowMs = 1_000L).apply { onSocketState(HaSocketState.LIVE) }
        broken.onFailure(100L, HaPathFailureKind.NETWORK)
        broken.onRoundTrip(1_000L, 10L)
        broken.onFailure(2_100L, HaPathFailureKind.NETWORK)
        assertEquals(1, broken.snapshot(2_100L).consecutiveFailures)
        assertEquals(HaNetworkPathSeverity.HEALTHY, broken.snapshot(2_100L).severity)
    }

    @Test fun theConsecutiveRunAgesOutWithTheWindowAndCannotResurrectLater() {
        // The run is a claim about the window. Once every failure sample has aged out nothing
        // evidences it, so it must not keep a panel severe forever, and a later single failure must
        // start a fresh run rather than continuing a stale one.
        path.onSocketState(HaSocketState.LIVE)
        path.onFailure(0L, HaPathFailureKind.NETWORK)
        path.onFailure(1_000L, HaPathFailureKind.NETWORK)
        val severe = path.snapshot(1_000L)
        assertEquals(2, severe.consecutiveFailures)
        assertEquals(HaNetworkPathSeverity.SEVERE, severe.severity)
        // The newest failure is still inside the window: the run stands.
        val boundary = path.snapshot(1_000L + HaNetworkPath.WINDOW_MS)
        assertEquals(1, boundary.networkFailures)
        assertEquals(2, boundary.consecutiveFailures)
        assertEquals(HaNetworkPathSeverity.SEVERE, boundary.severity)
        // One millisecond later nothing is left to evidence it.
        val aged = path.snapshot(1_000L + HaNetworkPath.WINDOW_MS + 1L)
        assertEquals(0, aged.networkFailures)
        assertEquals(0, aged.consecutiveFailures)
        assertEquals(HaNetworkPathSeverity.HEALTHY, aged.severity)
        // A later isolated failure is the first of a NEW run, not the third of the old one.
        val laterMs = 2_000L + HaNetworkPath.WINDOW_MS
        path.onFailure(laterMs, HaPathFailureKind.NETWORK)
        val fresh = path.snapshot(laterMs)
        assertEquals(1, fresh.consecutiveFailures)
        assertEquals(HaNetworkPathSeverity.HEALTHY, fresh.severity)
    }

    @Test fun twoNonConsecutiveTimeoutsInAWindowExceedTheLossLine() {
        path.onSocketState(HaSocketState.LIVE)
        val end = replay(0L, List(10) { 10L } + listOf<Long?>(null) + List(9) { 10L } + listOf<Long?>(null) + List(9) { 10L })
        val snap = path.snapshot(end)
        assertEquals(30, snap.probes)
        assertEquals(2, snap.networkFailures)
        assertEquals(0, snap.consecutiveFailures)
        assertTrue(snap.lossPercent > HaNetworkPath.SEVERE_LOSS_PERCENT)
        assertEquals(HaNetworkPathSeverity.SEVERE, snap.severity)
    }

    @Test fun aHomeAssistantRestartOnAHealthyPathIsNeverLoss() {
        path.onSocketState(HaSocketState.LIVE)
        var t = replay(0L, List(6) { 8L })
        // Home Assistant closes the socket, then refuses connections while it restarts, then a slow
        // startup stalls the subscribe deadline: all server-attributed.
        path.onFailure(t, HaPathFailureKind.SERVER); t += 1_000L
        path.onFailure(t, HaPathFailureKind.SERVER); t += 2_000L
        path.onFailure(t, HaPathFailureKind.SERVER); t += 4_000L
        path.onFailure(t, HaPathFailureKind.SERVER); t += 8_000L
        val during = path.snapshot(t)
        assertEquals(HaNetworkPathSeverity.HEALTHY, during.severity)
        assertEquals(0, during.networkFailures)
        assertEquals(4, during.serverFailures)
        assertEquals(0, during.consecutiveFailures)
        assertEquals(6, during.probes)
        // Back: probes resume on the same healthy path.
        t = replay(t, List(4) { 9L })
        val after = path.snapshot(t)
        assertEquals(HaNetworkPathSeverity.HEALTHY, after.severity)
        assertEquals(4, after.serverFailures)
    }

    @Test fun authenticationAndServerErrorsAreCountedApartAndDoNotResetTheFailureRun() {
        path.onSocketState(HaSocketState.LIVE)
        path.onFailure(0L, HaPathFailureKind.NETWORK)
        path.onFailure(1_000L, HaPathFailureKind.AUTH)
        path.onFailure(2_000L, HaPathFailureKind.SERVER)
        val snap = path.snapshot(3_000L)
        assertEquals(1, snap.networkFailures)
        assertEquals(1, snap.authFailures)
        assertEquals(1, snap.serverFailures)
        assertEquals(1, snap.consecutiveFailures)
        assertEquals(1, snap.probes)
        assertEquals(HaNetworkPathSeverity.HEALTHY, snap.severity)
        // A second real miss after the server noise completes the consecutive pair.
        path.onFailure(4_000L, HaPathFailureKind.NETWORK)
        assertEquals(HaNetworkPathSeverity.SEVERE, path.snapshot(4_000L).severity)
    }

    @Test fun aGenuinelyUnreachableNetworkEscalatesOnTheSecondMissAndStaysSevereWhileItLasts() {
        path.onSocketState(HaSocketState.LIVE)
        var t = replay(0L, List(3) { 12L })
        path.onFailure(t, HaPathFailureKind.NETWORK)
        assertEquals(HaNetworkPathSeverity.HEALTHY, path.snapshot(t).severity)
        t += 15_000L
        path.onFailure(t, HaPathFailureKind.NETWORK)
        assertEquals(HaNetworkPathSeverity.SEVERE, path.snapshot(t).severity)
        // Reconnect attempts keep failing on the backoff ladder.
        for (delay in listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L)) {
            t += delay
            path.onFailure(t, HaPathFailureKind.NETWORK)
        }
        val snap = path.snapshot(t)
        assertEquals(HaNetworkPathSeverity.SEVERE, snap.severity)
        assertEquals(9, snap.consecutiveFailures)
        assertTrue(snap.lossPercent > 50.0)
    }

    @Test fun recoveryAgesTheVerdictOutOfTheWindowRatherThanClearingItAtOnce() {
        path.onSocketState(HaSocketState.LIVE)
        var t = replay(0L, List(3) { 12L })
        val firstMissAt = t
        path.onFailure(t, HaPathFailureKind.NETWORK); t += 15_000L
        path.onFailure(t, HaPathFailureKind.NETWORK); t += 15_000L
        assertEquals(HaNetworkPathSeverity.SEVERE, path.snapshot(t).severity)
        // The path comes back. The consecutive run ends at once, but two misses in the window still
        // exceed five percent, so the notice persists until they age out.
        t = replay(t, List(20) { 10L })
        val recovering = path.snapshot(t)
        assertEquals(0, recovering.consecutiveFailures)
        assertEquals(2, recovering.networkFailures)
        assertEquals(HaNetworkPathSeverity.SEVERE, recovering.severity)
        // Exactly at the window boundary the first miss is still inside and the pair still exceeds
        // five percent; one millisecond later only one miss remains, which is diagnostic, not severe.
        val boundary = firstMissAt + HaNetworkPath.WINDOW_MS
        assertEquals(HaNetworkPathSeverity.SEVERE, path.snapshot(boundary).severity)
        val cleared = path.snapshot(boundary + 1L)
        assertEquals(HaNetworkPathSeverity.HEALTHY, cleared.severity)
        assertEquals(1, cleared.networkFailures)
        // And once the second miss has aged out too, nothing of the episode remains.
        val gone = path.snapshot(firstMissAt + 15_000L + HaNetworkPath.WINDOW_MS + 1L)
        assertEquals(0, gone.networkFailures)
        assertEquals(HaNetworkPathSeverity.HEALTHY, gone.severity)
    }

    @Test fun switchingTheSocketOffMakesTheVerdictUnreportableAndDiscardsTheSamples() {
        path.onSocketState(HaSocketState.LIVE)
        replay(0L, listOf(9L, null, null))
        assertEquals(HaNetworkPathSeverity.SEVERE, path.snapshot(3 * interval).severity)
        path.onSocketState(HaSocketState.STOPPED)
        val off = path.snapshot(3 * interval)
        assertFalse(off.measuring)
        assertFalse(off.degraded)
        assertEquals(0, off.probes)
        assertEquals(0, off.consecutiveFailures)
        // Re-demanded later: a clean start, not a replay of the old verdict.
        path.onSocketState(HaSocketState.LIVE)
        val fresh = path.snapshot(3 * interval)
        assertTrue(fresh.measuring)
        assertEquals(HaNetworkPathSeverity.HEALTHY, fresh.severity)
        assertEquals(0, fresh.probes)
    }

    @Test fun retentionIsBoundedToTheNewestSamples() {
        path.onSocketState(HaSocketState.LIVE)
        // Far more samples than the bound inside one window, at one millisecond spacing.
        for (i in 0 until HaNetworkPath.MAX_SAMPLES * 3) path.onRoundTrip(i.toLong(), 10L)
        val snap = path.snapshot((HaNetworkPath.MAX_SAMPLES * 3).toLong())
        assertEquals(HaNetworkPath.MAX_SAMPLES, snap.probes)
    }

    @Test fun exceedsShareNeedsTwoEventsAndMoreThanFivePercent() {
        assertFalse(HaNetworkPath.exceedsShare(0, 30))
        assertFalse(HaNetworkPath.exceedsShare(1, 1))
        assertFalse(HaNetworkPath.exceedsShare(1, 30))
        assertTrue(HaNetworkPath.exceedsShare(2, 30))
        assertTrue(HaNetworkPath.exceedsShare(2, 20))
        assertFalse(HaNetworkPath.exceedsShare(2, 40))
        assertTrue(HaNetworkPath.exceedsShare(3, 40))
        assertTrue(HaNetworkPath.exceedsShare(2, 2))
    }

    @Test fun nearestRankAndJitterAreExact() {
        assertEquals(-1L, HaNetworkPath.nearestRank(emptyList(), 0.95))
        assertEquals(7L, HaNetworkPath.nearestRank(listOf(7L), 0.95))
        assertEquals(20L, HaNetworkPath.nearestRank(listOf(10L, 20L), 0.95))
        assertEquals(10L, HaNetworkPath.nearestRank(listOf(10L, 20L), 0.50))
        val thirty = (1L..30L).toList()
        assertEquals(29L, HaNetworkPath.nearestRank(thirty, 0.95))
        assertEquals(15L, HaNetworkPath.nearestRank(thirty, 0.50))
        assertEquals(0L, HaNetworkPath.jitter(listOf(5L)))
        assertEquals(10L, HaNetworkPath.jitter(listOf(10L, 20L, 10L)))
    }

    @Test fun timeoutsInsideTheWindowKeepTheirPlaceInThePercentileCount() {
        path.onSocketState(HaSocketState.LIVE)
        // Misses are probes but not round trips: they count toward loss, never toward the p95 rank.
        val end = replay(0L, listOf(10L, null, 10L, 10L))
        val snap = path.snapshot(end)
        assertEquals(4, snap.probes)
        assertEquals(3, snap.roundTrips)
        assertEquals(10L, snap.p95Ms)
        assertEquals(25.0, snap.lossPercent, 0.01)
    }
}
