package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.dashboard.EntityCatalogStore
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityLearningRuntimeRestartTest {
    @Test fun denseHallRestartKeepsPersistedModelUntilFullContradictoryHold() {
        val backing = StoreBacking(hallRow())
        val journal = MemoryJournal()
        val runtime = runtime(backing, journal, sparse = false)

        for (now in 0L..300L step 100L) assertAvailable(runtime.observe(HALL_FAR, now), runtime)
        for (now in 350L until 30_350L step 50L) {
            assertAvailable(runtime.observe(HALL_BOOT_TAIL, now), runtime)
        }
        assertTrue(backing.row?.ready == true)
        assertTrue(backing.row?.snapshotJson?.contains("\"farRaw\":37.8244") == true)

        val shifted = runtime.observe(HALL_BOOT_TAIL, 30_350L)
        assertNull(shifted.near)
        assertNull(shifted.normalizedLevel)
        assertTrue(runtime.summary().startsWith("Adapting safely"))
        assertEquals(1, journal.marks)
        close(runtime)
    }

    @Test fun sparseHeldContradictionUsesTheSameLongBoundary() {
        val backing = StoreBacking(hallRow())
        val journal = MemoryJournal()
        val runtime = runtime(backing, journal, sparse = true)

        assertAvailable(runtime.observe(HALL_BOOT_TAIL, 0L, sparseReporting = true), runtime)
        assertAvailable(runtime.tick(29_999L, sparseReporting = true), runtime)
        val shifted = runtime.tick(30_000L, sparseReporting = true)

        assertNull(shifted.near)
        assertNull(shifted.normalizedLevel)
        assertEquals(1, journal.marks)
        close(runtime)
    }

    @Test fun contradictoryFirstDenseStartupCannotUseTheSeedMismatchShortcut() {
        val backing = StoreBacking(hallRow())
        val journal = MemoryJournal()
        val runtime = runtime(backing, journal, sparse = false)

        for (now in 0L..2_000L step 50L) {
            assertAvailable(runtime.observe(HALL_BOOT_TAIL, now), runtime)
        }
        assertEquals(0, journal.marks)
        for (now in 2_050L until 30_000L step 50L) runtime.observe(HALL_BOOT_TAIL, now)
        val shifted = runtime.observe(HALL_BOOT_TAIL, 30_000L)
        assertNull(shifted.near)
        assertNull(shifted.normalizedLevel)
        assertEquals(1, journal.marks)
        close(runtime)
    }

    @Test fun staleStreamRecoversTheTrustedModelWithoutAdvancingGeneration() {
        val backing = StoreBacking(hallRow())
        val journal = MemoryJournal()
        val runtime = runtime(backing, journal, sparse = false)

        assertAvailable(runtime.observe(HALL_FAR, 0L), runtime)
        val stale = runtime.tick(60_001L)
        assertNull(stale.near)
        assertNull(stale.normalizedLevel)
        assertAvailable(runtime.observe(HALL_FAR, 60_002L), runtime)
        assertEquals(0, journal.marks)
        close(runtime)
    }

    @Test fun persistedStoreReopensWithAFreshValidationGeneration() {
        val backing = StoreBacking(hallRow())
        val journal = MemoryJournal()
        val first = runtime(backing, journal, sparse = false)
        for (now in 0L..30_000L step 100L) first.observe(HALL_FAR, now)
        assertTrue(first.isReady())
        close(first)

        val second = runtime(backing, journal, sparse = false)
        assertFalse(second.isReady())
        assertAvailable(second.observe(HALL_BOOT_TAIL, 0L), second)
        assertEquals(2, backing.opens)
        assertTrue(backing.writes >= 2)
        assertEquals(0, journal.marks)
        close(second)
        assertEquals(2, backing.closes)
    }

    private fun runtime(backing: StoreBacking, journal: MemoryJournal, sparse: Boolean) =
        ProximityLearningRuntime(
            context = null,
            config = null,
            sourceIdentity = SOURCE,
            sparseLearningSource = sparse,
            legacySeedEligible = false,
            invalidationJournalForTest = journal,
            modelStoreForTest = backing.open(),
        )

    private fun assertAvailable(decision: ProximityLearningRuntime.Decision, runtime: ProximityLearningRuntime) {
        assertEquals(false, decision.near)
        assertTrue(decision.normalizedLevel != null)
        assertFalse(runtime.isReady())
        assertTrue(runtime.summary().startsWith("Checking the previous"))
    }

    private fun close(runtime: ProximityLearningRuntime) {
        runtime.closeAsync().get(5, TimeUnit.SECONDS)
    }

    private fun hallRow(): EntityCatalogStore.ProximityModelRow {
        val fingerprint = ProximityLearningRuntime.fingerprint(SOURCE)
        val snapshot = ProximityLearningEngine.Snapshot(
            farRaw = HALL_FAR,
            nearRaw = 69.0891f,
            noise = 1.392f,
            mode = ProximityLearningEngine.Mode.GRADED,
            polarity = ProximityLearningEngine.Polarity.NEAR_IS_HIGHER,
            completedExcursions = 119,
        )
        return EntityCatalogStore.ProximityModelRow(
            fingerprint = fingerprint,
            algorithmVersion = ProximityLearningRuntime.ALGORITHM_VERSION,
            behaviorSignature = "GRADED:NEAR_IS_HIGHER",
            snapshotJson = ProximityLearningRuntime.persistedModelJson(snapshot, guidedReady = true),
            ready = true,
            updatedAt = 1L,
        )
    }

    private class StoreBacking(initial: EntityCatalogStore.ProximityModelRow) {
        var row: EntityCatalogStore.ProximityModelRow? = initial
        var opens = 0
        var closes = 0
        var writes = 0

        fun open(): ProximityModelStore {
            opens++
            return object : ProximityModelStore {
                override fun readProximityModel(fingerprint: String) = row?.takeIf { it.fingerprint == fingerprint }

                override fun writeProximityBatch(
                    model: EntityCatalogStore.ProximityModelRow,
                    rollups: List<EntityCatalogStore.ProximityRollupRow>,
                    episodes: List<EntityCatalogStore.ProximityEpisodeRow>,
                    now: Long,
                ) {
                    row = model
                    writes++
                }

                override fun clearProximityLearning(fingerprint: String) {
                    if (row?.fingerprint == fingerprint) row = null
                }

                override fun close() {
                    closes++
                }
            }
        }
    }

    private class MemoryJournal : ProximityWakeInvalidationAuthority {
        private var token: String? = null
        var marks = 0

        override fun read(fingerprint: String) = token
        override fun mark(fingerprint: String, token: String): Boolean {
            this.token = token
            marks++
            return true
        }
        override fun clear(fingerprint: String, token: String): Boolean {
            if (this.token != token) return false
            this.token = null
            return true
        }
    }

    companion object {
        private const val SOURCE = "hal:8:hall-tpa10"
        private const val HALL_FAR = 37.8244f
        private const val HALL_BOOT_TAIL = 25.875f
    }
}
