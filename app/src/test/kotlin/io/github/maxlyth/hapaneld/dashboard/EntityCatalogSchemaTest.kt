package io.github.maxlyth.hapaneld.dashboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityCatalogSchemaTest {
    @Test fun schemaUpgradePlanIsSequentialAndComplete() {
        val plan = EntityCatalogSchema.plan(1, EntityCatalogSchema.CURRENT_VERSION)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), plan.map { it.from })
        assertEquals(EntityCatalogSchema.CURRENT_VERSION, plan.last().to)
        assertTrue(plan.first().sql.single().contains("sync_generation"))
        assertTrue(plan[1].sql.any { it.contains("rate_window_start") })
        assertTrue(plan[2].sql.any { it.contains("minute_rollup") })
        assertTrue(plan[3].sql.single().contains("issues_json"))
        assertTrue(plan[4].sql.single().contains("dashboard_issue_ignore"))
        assertTrue(plan[5].sql.contains("DROP TABLE IF EXISTS hourly"))
        assertTrue(plan[5].sql.any { it.contains("span_start") })
        assertTrue(plan[6].sql.any { it.contains("dashboard_performance") })
        assertTrue(plan[7].sql.any { it.contains("app_state") })
        assertTrue(plan[8].sql.any { it.contains("proximity_model") })
        assertTrue(plan[8].sql.any { it.contains("proximity_rollup") })
        assertTrue(plan.last().sql.any { it.contains("ambient_lux_minute") })
    }

    @Test fun sameVersionIsANoOpAndDowngradeIsRejected() {
        assertTrue(EntityCatalogSchema.plan(11, 11).isEmpty())
        assertTrue(runCatching { EntityCatalogSchema.plan(11, 10) }.isFailure)
        assertTrue(runCatching { EntityCatalogSchema.plan(1, 12) }.isFailure)
    }

    @Test fun ambientHistoryFollowsRatherThanReusesTheProximitySchemaVersion() {
        val ambientOnly = EntityCatalogSchema.plan(10, 11)
        assertEquals(1, ambientOnly.size)
        assertTrue(ambientOnly.single().sql.any { it.contains("ambient_lux_minute") })
        assertFalse(ambientOnly.single().sql.any { it.contains("proximity_model") })
    }

    @Test fun retiredHourlyRollupCannotReturnToTheWritePath() {
        val source = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityCatalogStore.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityCatalogStore.kt"),
        ).first(File::isFile).readText()

        assertFalse(source.contains("CREATE TABLE hourly"))
        assertFalse(source.contains("INTO hourly"))
        assertFalse(source.contains("UPDATE hourly"))
    }
}
