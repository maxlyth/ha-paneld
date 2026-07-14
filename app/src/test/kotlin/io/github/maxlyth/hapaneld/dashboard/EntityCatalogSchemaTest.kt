package io.github.maxlyth.hapaneld.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityCatalogSchemaTest {
    @Test fun schemaUpgradePlanIsSequentialAndComplete() {
        val plan = EntityCatalogSchema.plan(1, EntityCatalogSchema.CURRENT_VERSION)
        assertEquals(listOf(1, 2, 3, 4, 5), plan.map { it.from })
        assertEquals(EntityCatalogSchema.CURRENT_VERSION, plan.last().to)
        assertTrue(plan.first().sql.single().contains("sync_generation"))
        assertTrue(plan[1].sql.any { it.contains("rate_window_start") })
        assertTrue(plan[2].sql.any { it.contains("minute_rollup") })
        assertTrue(plan[3].sql.single().contains("issues_json"))
        assertTrue(plan.last().sql.single().contains("dashboard_issue_ignore"))
    }

    @Test fun sameVersionIsANoOpAndDowngradeIsRejected() {
        assertTrue(EntityCatalogSchema.plan(6, 6).isEmpty())
        assertTrue(runCatching { EntityCatalogSchema.plan(6, 5) }.isFailure)
        assertTrue(runCatching { EntityCatalogSchema.plan(1, 7) }.isFailure)
    }
}
