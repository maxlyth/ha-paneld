package io.github.maxlyth.hapaneld.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBundleTest {
    @Test fun roundTripsThroughSerialize() {
        val b = ConfigBundle.fromValues(
            values = mapOf("mqtt_broker" to "tcp://ha:1883", "friendly_name" to "Front \"Hall\""),
            schema = 1, exportedAt = "2026-06-29T00:00:00Z", exportedBy = "test",
        )
        val parsed = ConfigBundle.parse(b.serialize())
        assertNotNull(parsed)
        assertEquals(b, parsed)
    }

    @Test fun valuesAreSortedForStableOutput() {
        val json = ConfigBundle.fromValues(mapOf("z" to "1", "a" to "2")).serialize()
        assertTrue(json.indexOf("\"a\"") < json.indexOf("\"z\""))
    }

    @Test fun escapesAndUnescapes() {
        val v = mapOf("k" to "a\\b\"c\nd")
        val parsed = ConfigBundle.parse(ConfigBundle.fromValues(v).serialize())!!
        assertEquals("a\\b\"c\nd", parsed.values["k"])
    }

    @Test fun malformedReturnsNull() {
        assertNull(ConfigBundle.parse("not json"))
        assertNull(ConfigBundle.parse("{\"kind\":"))
        assertNull(ConfigBundle.parse(ConfigBundle.fromValues(mapOf("a" to "1")).serialize() + " trailing"))
    }
}

class MigrationsTest {
    @Test fun sameSchemaNoChange() {
        val (m, w) = Migrations.migrate(SettingsRegistry.SCHEMA, mapOf("a" to "1"))
        assertEquals(mapOf("a" to "1"), m)
        assertTrue(w.isEmpty())
    }

    @Test fun newerThanCurrentToleratesWithWarning() {
        val (m, w) = Migrations.migrate(SettingsRegistry.SCHEMA + 5, mapOf("a" to "1"))
        assertEquals(mapOf("a" to "1"), m)
        assertTrue(w.any { it.contains("newer") })
    }

    @Test fun olderWithoutRegisteredMigrationWarns() {
        // Only meaningful once SCHEMA > 1; with an empty chain a gap is reported, not thrown.
        if (SettingsRegistry.SCHEMA > 1) {
            val (_, w) = Migrations.migrate(1, mapOf("a" to "1"))
            assertTrue(w.isNotEmpty())
        }
    }
}

class ConfigDiffTest {
    @Test fun reportsOnlyChangedKeys() {
        val current = mapOf("a" to "1", "b" to "2")
        val candidate = mapOf("a" to "1", "b" to "3", "c" to "9")
        val d = ConfigDiff.diff(current, candidate)
        assertEquals(2, d.size)
        assertEquals(ConfigDiff.Change("b", "2", "3"), d[0])
        assertEquals(ConfigDiff.Change("c", null, "9"), d[1])
    }
}

class RevisionRingTest {
    @Test fun noEvictionUnderCap() {
        assertTrue(RevisionRing.toEvict(listOf(1L, 2L, 3L), max = 20).isEmpty())
    }

    @Test fun evictsOldestWhenAddingOverflows() {
        val existing = (1L..20L).toList()
        assertEquals(listOf(1L), RevisionRing.toEvict(existing, max = 20))
    }

    @Test fun evictsMultipleWhenFarOver() {
        val existing = (1L..25L).toList()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), RevisionRing.toEvict(existing, max = 20))
    }
}
