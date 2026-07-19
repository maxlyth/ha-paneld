package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import kotlin.math.ln1p

class AdaptiveBrightnessPolicyTest {
    @Test fun baselineCacheBuildsOnlyOnTtlEvidenceOrContextChange() {
        var builds = 0
        val cache = AdaptiveBaselineCache(refreshMs = 300_000L) { _, rows, _, _, fallback ->
            builds++
            BaselineEstimate(fallback, 0.1, 0, rows.size)
        }
        val rows = listOf(historyRow(NOW / AMBIENT_MINUTE_MS - 1, 10.0))
        repeat(300) { cache.estimate(NOW + it * 1_000L, rows, UTC, null, ln1p(10.0)) }
        assertEquals(1, builds)
        cache.estimate(NOW + 300_000L, rows, UTC, null, ln1p(10.0))
        assertEquals(2, builds)
        cache.estimate(NOW + 300_001L, rows.toList(), UTC, null, ln1p(10.0))
        assertEquals(3, builds)
        cache.estimate(NOW + 300_002L, rows, UTC, SolarLocation(1.0, 2.0), ln1p(10.0))
        assertEquals(4, builds)
    }

    @Test fun cachedPolicyDoesNotCountCalmTimeBeforeAHighObservation() {
        val policy = AdaptiveBrightnessPolicy()
        val baseline = BaselineEstimate(ln1p(10.0), 0.05, 4, 20)
        repeat(30) { policy.evaluate(NOW + it * 60_000L, 60_000L, 10.0, baseline, 50) }

        val first = policy.evaluate(NOW + 1_800_000L, 60_000L, 100.0, baseline, 50, conditionElapsedMs = 0L)!!
        assertFalse(first.brighterThanExpected)
        assertTrue(policy.needsFastFollowUp())
        assertFalse(policy.evaluate(NOW + 1_801_000L, 1_000L, 100.0, baseline, 50)!!.brighterThanExpected)
        assertTrue(policy.evaluate(NOW + 1_802_000L, 1_000L, 100.0, baseline, 50)!!.brighterThanExpected)
        assertFalse("a steady admitted boost returns to calm cadence", policy.needsFastFollowUp())
        policy.evaluate(NOW + 1_803_000L, 1_000L, 10.0, baseline, 50, conditionElapsedMs = 0L)
        assertTrue("a clearing transition enters bounded fast cadence", policy.needsFastFollowUp())
    }

    @Test fun versionedCurveUsesSpecifiedAnchorsAndSafetyBounds() {
        assertEquals(10, AdaptiveLuxCurve.rawBrightness(0.0))
        assertEquals((0.06 * 255).toInt(), AdaptiveLuxCurve.rawBrightness(1.0))
        assertEquals(77, AdaptiveLuxCurve.rawBrightness(100.0))
        assertEquals(255, AdaptiveLuxCurve.rawBrightness(10_000.0))
        assertEquals(1, AdaptiveLuxCurve.VERSION)
    }

    @Test fun sensitivityScalesOnlyDeviationFromExpectation() {
        val rows = repeatedHistory(expectedLux = 100.0, days = 4)
        fun evaluate(sensitivity: Int): AdaptiveBrightnessResult {
            val policy = AdaptiveBrightnessPolicy()
            repeat(360) { tick ->
                policy.evaluate(NOW + tick * 5_000L, 5_000, 100.0, rows, sensitivity, UTC)
            }
            return policy.evaluate(NOW + 1_800_000L, 1_000, 25.0, rows, sensitivity, UTC)!!
        }
        val steady = evaluate(0)
        val normal = evaluate(50)
        val responsive = evaluate(100)
        assertTrue(steady.effectiveLux > normal.effectiveLux)
        assertTrue(normal.effectiveLux > responsive.effectiveLux)
        assertFalse(normal.brighterThanExpected)
    }

    @Test fun qualifyingFastRiseAdmitsAfterTwoSecondsThenReleases() {
        val history = repeatedHistory(expectedLux = 10.0, days = 4)
        val policy = AdaptiveBrightnessPolicy()
        repeat(360) { policy.evaluate(NOW + it * 5_000L, 5_000, 10.0, history, 50, UTC) }
        val first = policy.evaluate(NOW + 1_800_000, 1_000, 100.0, history, 50, UTC)!!
        val second = policy.evaluate(NOW + 1_801_000, 1_000, 100.0, history, 50, UTC)!!
        assertFalse(first.brighterThanExpected)
        assertTrue(second.brighterThanExpected)
        repeat(29) { policy.evaluate(NOW + 1_802_000 + it * 1_000L, 1_000, 10.0, history, 50, UTC) }
        val released = policy.evaluate(NOW + 1_831_000, 1_000, 10.0, history, 50, UTC)!!
        assertFalse(released.brighterThanExpected)
    }

    @Test fun maturityRequiresElapsedCoverageAndDistinctHistoricalDays() {
        assertEquals(AdaptiveModelMode.COLD_START, AdaptiveAmbientModel.mode(29 * 60_000, 7))
        assertEquals(AdaptiveModelMode.PROVISIONAL, AdaptiveAmbientModel.mode(30 * 60_000, 1))
        assertEquals(AdaptiveModelMode.LEARNING, AdaptiveAmbientModel.mode(30 * 60_000, 2))
        assertEquals(AdaptiveModelMode.CONFIDENT, AdaptiveAmbientModel.mode(30 * 60_000, 4))
    }

    @Test fun elapsedTimeSmoothingIsInvariantToEvaluationRate() {
        val frequent = AdaptiveBrightnessPolicy()
        val sparse = AdaptiveBrightnessPolicy()
        frequent.evaluate(NOW, 1_000, 10.0, emptyList(), 50, UTC)
        sparse.evaluate(NOW, 1_000, 10.0, emptyList(), 50, UTC)
        var frequentResult: AdaptiveBrightnessResult? = null
        var sparseResult: AdaptiveBrightnessResult? = null
        repeat(60) { tick ->
            frequentResult = frequent.evaluate(NOW + (tick + 1) * 1_000L, 1_000, 100.0, emptyList(), 50, UTC)
        }
        repeat(12) { tick ->
            sparseResult = sparse.evaluate(NOW + (tick + 1) * 5_000L, 5_000, 100.0, emptyList(), 50, UTC)
        }
        assertTrue(kotlin.math.abs(frequentResult!!.brightness - sparseResult!!.brightness) <= 1)
        assertEquals(frequentResult!!.effectiveLux, sparseResult!!.effectiveLux, 0.001)
    }

    @Test fun invalidObservationsDoNotAdvancePolicy() {
        val policy = AdaptiveBrightnessPolicy()
        assertEquals(null, policy.evaluate(NOW, 1_000, Double.NaN, emptyList(), 50, UTC))
        assertEquals(null, policy.evaluate(NOW, 1_000, -1.0, emptyList(), 50, UTC))
        assertEquals(null, policy.evaluate(NOW, 0, 1.0, emptyList(), 50, UTC))
        assertEquals(AdaptiveModelMode.COLD_START,
            policy.evaluate(NOW, 1_000, 1.0, emptyList(), 50, UTC)!!.mode)
    }

    @Test fun localTimeFallbackUsesRobustMedianAndRejectsOutlier() {
        val baseMinute = NOW / AMBIENT_MINUTE_MS
        val rows = listOf(10.0, 11.0, 12.0, 10_000.0).mapIndexed { index, lux ->
            historyRow(baseMinute - (index + 1) * 1440L, lux)
        }
        val estimate = AdaptiveAmbientModel.estimate(NOW, rows, UTC, null, ln1p(1.0))
        assertTrue(estimate.expectedLogLux in ln1p(10.0)..ln1p(12.0))
        assertEquals(4, estimate.coveredDays)
    }

    @Test fun chartProjectionIsFiveMinuteAndPartitionAgnostic() {
        val rows = (0 until 12).map { historyRow(NOW / AMBIENT_MINUTE_MS + it, (it + 1).toDouble()) }
        val points = AdaptiveChartProjection.fiveMinute(rows) { ln1p(20.0) }
        assertEquals(3, points.size)
        assertEquals(20.0, points.first().expectedLux, 0.0001)
        assertTrue(points.first().maxLux >= points.first().minLux)
    }

    @Test fun compiledChartBaselineReusesOneHistoryIndexForEveryPoint() {
        val rows = repeatedHistory(expectedLux = 20.0, days = 7)
        val lookup = AdaptiveChartBaselineLookup.compile(rows, UTC, null, ln1p(1.0))
        repeat(2_016) {
            assertEquals(20.0, kotlin.math.exp(lookup.expectedLogLux(NOW)) - 1.0, 0.0001)
        }
    }

    private fun repeatedHistory(expectedLux: Double, days: Int): List<AmbientHistoryMinute> =
        (1..days).flatMap { day -> (-2..2).map { offset ->
            historyRow(NOW / AMBIENT_MINUTE_MS - day * 1440L + offset, expectedLux)
        } }

    private fun historyRow(minute: Long, lux: Double): AmbientHistoryMinute = AmbientHistoryMinute(
        AmbientHistoryKey("location", "source", minute), lux * 60_000, 60_000,
        lux, lux, lux, 60, ln1p(lux) * 60_000, 60_000,
    )

    companion object {
        private val UTC = TimeZone.getTimeZone("UTC")
        private const val NOW = 1_800_000_000_000L
    }
}
