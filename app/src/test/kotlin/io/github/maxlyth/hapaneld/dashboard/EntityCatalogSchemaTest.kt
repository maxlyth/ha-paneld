package io.github.maxlyth.hapaneld.dashboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityCatalogSchemaTest {
    @Test fun schemaUpgradePlanIsSequentialAndComplete() {
        val plan = EntityCatalogSchema.plan(1, EntityCatalogSchema.CURRENT_VERSION)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), plan.map { it.from })
        assertEquals(EntityCatalogSchema.CURRENT_VERSION, plan.last().to)
        assertTrue(plan.first().sql.single().contains("sync_generation"))
        assertTrue(plan[1].sql.any { it.contains("rate_window_start") })
        assertTrue(plan[2].sql.any { it.contains("minute_rollup") })
        assertTrue(plan[3].sql.single().contains("issues_json"))
        assertTrue(plan[4].sql.single().contains("dashboard_issue_ignore"))
        assertTrue(plan[5].sql.contains("DROP TABLE IF EXISTS hourly"))
        assertTrue(plan[5].sql.any { it.contains("span_start") })
        assertTrue(plan.last().sql.any { it.contains("dashboard_performance") })
    }

    @Test fun sameVersionIsANoOpAndDowngradeIsRejected() {
        assertTrue(EntityCatalogSchema.plan(8, 8).isEmpty())
        assertTrue(runCatching { EntityCatalogSchema.plan(8, 7) }.isFailure)
        assertTrue(runCatching { EntityCatalogSchema.plan(1, 9) }.isFailure)
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
