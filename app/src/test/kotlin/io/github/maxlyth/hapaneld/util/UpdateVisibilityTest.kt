package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.util.UpdateChecker.UpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateVisibilityTest {
    private val paneld = UpdateInfo("ha-paneld", "0.8.5", "0.8.6", "u1")
    private val companion = UpdateInfo("HA Companion", "2026.5.4", "2026.6.5", "u2")
    private val all = listOf(paneld, companion)

    @Test fun noIgnoresShowsEverything() {
        assertEquals(all, UpdateChecker.filterIgnored(all, emptyMap()))
    }

    @Test fun ignoringTheExactVersionHidesOnlyThatEntry() {
        val out = UpdateChecker.filterIgnored(all, mapOf("HA Companion" to "2026.6.5"))
        assertEquals(listOf(paneld), out)
    }

    @Test fun ignoreOfAnOlderVersionDoesNotHideANewerRelease() {
        // The user dismissed 2026.6.5 earlier; now 2026.7.0 is out — it must re-surface ("ticks again").
        val newer = companion.copy(latestVersion = "2026.7.0")
        val out = UpdateChecker.filterIgnored(listOf(newer), mapOf("HA Companion" to "2026.6.5"))
        assertEquals(listOf(newer), out)
    }

    @Test fun ignoreIsKeyedByLabelSoOtherComponentsUnaffected() {
        val out = UpdateChecker.filterIgnored(all, mapOf("ha-paneld" to "0.8.6"))
        assertEquals(listOf(companion), out)
    }
}
