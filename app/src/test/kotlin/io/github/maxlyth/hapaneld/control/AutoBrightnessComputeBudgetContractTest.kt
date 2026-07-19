package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.adaptiveHaSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBrightnessComputeBudgetContractTest {
    @Test fun `controller has no permanent one-second fixed-rate loop`() {
        val source = source("control/AutoBrightnessController.kt")
        assertFalse(source.contains("scheduleAtFixedRate"))
        assertTrue(source.contains("CALM_EVALUATION_MS = 60_000L"))
        assertTrue(source.contains("policy.needsFastFollowUp()"))
        assertTrue(source.contains("AdaptiveBaselineCache"))
        assertTrue("tick failures must retain a calm recovery deadline", source.contains("requestEvaluationLocked(CALM_EVALUATION_MS)"))
    }

    @Test fun `ambient history has no empty periodic database reload`() {
        val source = source("control/AmbientHistoryRuntime.kt")
        assertFalse(source.contains("scheduleWithFixedDelay"))
        assertTrue(source.contains("scheduleFlushLocked"))
        assertTrue("reset must invalidate in-flight reloads", source.contains("generation.incrementAndGet()"))
    }

    @Test fun `disabled auto brightness does not select a Home Assistant source`() {
        assertNull(adaptiveHaSource(enabled = false, configuredEntity = "sensor.room_lux"))
        assertNull(adaptiveHaSource(enabled = true, configuredEntity = "  "))
        assertEquals(
            "sensor.room_lux",
            adaptiveHaSource(enabled = true, configuredEntity = " sensor.room_lux "),
        )
    }

    @Test fun `site metadata fetch is gated by auto brightness enablement`() {
        val source = source("PaneldService.kt")
        assertTrue(source.contains("if (enabled) {\n            scope.launch {\n                val site = haSiteMetadata.fetch()"))
    }

    @Test fun `successful write recency has no cross-owner callback edge`() {
        val brightness = source("control/BrightnessController.kt")
        val automatic = source("control/AutoBrightnessController.kt")
        val service = source("PaneldService.kt")

        assertTrue(brightness.contains("successfulWrites = successfulWrites"))
        assertTrue(brightness.contains("if (actuator.apply(level)) successfulWrites.record(elapsedRealtimeMs.read())"))
        assertTrue(automatic.contains("brightness.lastSuccessfulWriteElapsed()"))
        assertFalse(brightness.contains("onBrightnessWritten"))
        assertFalse(automatic.contains("noteBacklightWrite"))
        assertFalse(service.contains("onBrightnessWritten"))
    }

    private fun source(relative: String): String {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            File(working, "app/src/main/kotlin/io/github/maxlyth/hapaneld/$relative"),
            File(working, "src/main/kotlin/io/github/maxlyth/hapaneld/$relative"),
        ).first(File::isFile).readText()
    }
}
