package io.github.maxlyth.hapaneld.dashboard

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardPerformanceHistoryStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun cleanBefore() {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
        context.deleteDatabase(EntityCatalogStore.LEGACY_DATABASE_NAME)
    }

    @After fun cleanAfter() {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
        context.deleteDatabase(EntityCatalogStore.LEGACY_DATABASE_NAME)
    }

    @Test fun minuteRollupsSurviveStoreReopenAndAggregateBatches() {
        val minute = System.currentTimeMillis() / 60_000L
        fun sample(updates: Long, bytes: Long, worstMicros: Long) = DashboardPerformanceSample(
            instance = "https://ha.example",
            path = "wall/default",
            minute = minute,
            filterActive = true,
            entityCount = 42,
            batch = EntityFilterProtocol.TrafficBatch(
                sampleMs = 5_000,
                frames = 2,
                frameChars = bytes,
                entityUpdates = updates,
                processingMicros = 100,
                droppedFrames = 0,
                stateTaskMicros = 50_000,
                stateTaskMaxMicros = 30_000,
                interactionBins = LongArray(10).also { it[3] = 1 },
                interactionMaxMicros = worstMicros,
                inputDelayMicros = worstMicros / 4,
                interactionProcessingMicros = worstMicros / 2,
                presentationMicros = worstMicros / 4,
            ),
        )

        EntityCatalogStore(context).use { store ->
            store.recordDashboardPerformance(sample(10, 20_000, 200_000))
            store.recordDashboardPerformance(sample(15, 30_000, 500_000))
        }

        EntityCatalogStore(context).use { reopened ->
            val rows = reopened.dashboardPerformanceHistory(
                "https://ha.example",
                "wall/default",
                minute - 1,
            )
            assertEquals(1, rows.size)
            assertEquals(10_000, rows.single().sampleMs)
            assertEquals(25, rows.single().updates)
            assertEquals(50_000, rows.single().payloadBytes)
            assertEquals(500_000, rows.single().interactionMaxMicros)
            assertTrue(rows.single().filterActive)
        }
    }
}
