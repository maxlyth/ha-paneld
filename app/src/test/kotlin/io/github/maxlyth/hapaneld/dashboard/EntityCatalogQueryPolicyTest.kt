package io.github.maxlyth.hapaneld.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityCatalogQueryPolicyTest {
    private fun row(
        id: String,
        access: Long = 0,
        rate: Double = 0.0,
        reasons: String = "",
        last: Long = 0,
        override: String = "auto",
    ) = EntitySortProjection(id, access, rate, reasons, last, override)

    @Test fun apiSortPolicyAllowsOnlyDisplayedColumnsAndDirections() {
        val allowed = listOf("entity_id", "access_1h", "rate_1h_bps", "reasons", "last_access", "override")
        allowed.forEach { assertEquals(it, EntityCatalogSorting.key(it)) }
        assertEquals("entity_id", EntityCatalogSorting.key("update_bytes DESC; DROP TABLE entity"))
        assertEquals("asc", EntityCatalogSorting.direction("sideways"))
        assertEquals("desc", EntityCatalogSorting.direction("DESC"))
        assertTrue(EntityCatalogSorting.sqlOrder("entity_id", "asc")!!.startsWith("e.entity_id"))
        assertTrue(EntityCatalogSorting.sqlOrder("last_access", "desc")!!.contains("DESC"))
        assertEquals(null, EntityCatalogSorting.sqlOrder("access_1h", "asc"))
        assertEquals(null, EntityCatalogSorting.sqlOrder("rate_1h_bps", "desc"))
    }

    @Test fun everyDisplayedColumnSortsBothDirectionsWithDeterministicEntityTieBreak() {
        val rows = listOf(
            row("sensor.z", 7, 2.5, "runtime", 100, "pinned"),
            row("sensor.a", 7, 2.5, "runtime", 100, "pinned"),
            row("sensor.m", 2, 9.0, "dashboard", 300, "excluded"),
        )
        for (key in listOf("entity_id", "access_1h", "rate_1h_bps", "reasons", "last_access", "override")) {
            for (direction in listOf("asc", "desc")) {
                val sorted = rows.sortedWith(EntityCatalogSorting.comparator(key, direction) { it })
                // Equal primary values are stable across refreshes/pages regardless of primary direction.
                if (key != "entity_id") assertTrue(sorted.indexOfFirst { it.entityId == "sensor.a" } < sorted.indexOfFirst { it.entityId == "sensor.z" })
                assertEquals(rows.toSet(), sorted.toSet())
            }
        }
    }

    @Test fun paginationIsAppliedAfterGlobalSort() {
        val rows = (0 until 250).map { row("sensor.%03d".format(it), access = ((it * 37) % 91).toLong()) }
        val globallySorted = rows.sortedWith(EntityCatalogSorting.comparator("access_1h", "desc") { it })

        val secondPage = globallySorted.drop(100).take(100)

        assertEquals(globallySorted.subList(100, 200), secondPage)
        assertTrue(secondPage.first() !in globallySorted.take(100))
    }

    @Test fun oneSnapshotLoadServesAllTablesInsideRefreshWindow() {
        val cache = BoundedSnapshotCache<String, List<Int>>(windowMs = 10_000, maxEntries = 4)
        var aggregateQueries = 0
        fun load(now: Long): List<Int> = cache.get("instance/dashboard", now) { aggregateQueries++; listOf(aggregateQueries) }

        val subscribed = load(20_001)
        val suggested = load(24_000)
        val review = load(29_999)

        assertSame(subscribed, suggested)
        assertSame(subscribed, review)
        assertEquals(1, aggregateQueries)
        assertEquals(listOf(2), load(30_001))
        assertEquals(2, aggregateQueries)
    }

    @Test fun explicitInvalidationDropsADeletedEvidenceSnapshot() {
        val cache = BoundedSnapshotCache<String, String>(windowMs = 10_000, maxEntries = 4)
        var loads = 0
        fun load() = cache.get("target", 1_000) { "snapshot-${++loads}" }
        assertEquals("snapshot-1", load())
        cache.invalidate("target")
        assertEquals("snapshot-2", load())
    }

    @Test fun snapshotCacheIsStrictlyBoundedAcrossInstancesAndDashboards() {
        val cache = BoundedSnapshotCache<String, String>(windowMs = 10_000, maxEntries = 3)
        repeat(20) { cache.get("target-$it", 1_000) { "snapshot-$it" } }
        assertEquals(3, cache.sizeForTest())
    }
}
