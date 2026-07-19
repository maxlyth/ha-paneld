package io.github.maxlyth.hapaneld.config

import io.github.maxlyth.hapaneld.http.shouldSnapshotConfigSetting
import io.github.maxlyth.hapaneld.http.projectConfigSnapshot
import io.github.maxlyth.hapaneld.http.preserveUnconfiguredZigbeeOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBundleTest {
    @Test fun transientSettingsAreSkippedBeforeTheirLiveValueIsEvaluated() {
        val evaluated = mutableListOf<String>()

        projectConfigSnapshot(
            specs = SettingsRegistry.settable(),
            zigbeeRouterConfigured = true,
            effectiveValue = { spec -> evaluated += spec.key; spec.default },
        )

        assertFalse("cpu_governor is transient and must never be probed for a snapshot", "cpu_governor" in evaluated)
        assertTrue("non-transient controller values still belong in the snapshot", "touch_sound" in evaluated)
    }

    @Test fun unconfiguredZigbeeIntentIsOmittedInsteadOfBecomingExplicitOff() {
        assertFalse(shouldSnapshotConfigSetting("zigbee_router", zigbeeRouterConfigured = false))
        assertTrue(shouldSnapshotConfigSetting("zigbee_router", zigbeeRouterConfigured = true))
        assertTrue(shouldSnapshotConfigSetting("keep_awake", zigbeeRouterConfigured = false))

        val fullBackupConfig = projectConfigSnapshot(
            specs = SettingsRegistry.settable(),
            zigbeeRouterConfigured = false,
            excludedKeys = setOf("dashboard_entity_overrides"),
            effectiveValue = { it.default },
        )
        assertFalse(fullBackupConfig.containsKey("zigbee_router"))
        assertFalse(fullBackupConfig.containsKey("dashboard_entity_overrides"))
        assertTrue(fullBackupConfig.containsKey("keep_awake"))

        val legacyRestore = linkedMapOf("zigbee_router" to "false", "keep_awake" to "true")
        assertTrue(preserveUnconfiguredZigbeeOwnership(legacyRestore, targetConfigured = false))
        assertFalse(legacyRestore.containsKey("zigbee_router"))
        assertEquals("true", legacyRestore["keep_awake"])

        val explicitOn = linkedMapOf("zigbee_router" to "true")
        assertFalse(preserveUnconfiguredZigbeeOwnership(explicitOn, targetConfigured = false))
        assertEquals("true", explicitOn["zigbee_router"])

        val configuredTarget = linkedMapOf("zigbee_router" to "false")
        assertFalse(preserveUnconfiguredZigbeeOwnership(configuredTarget, targetConfigured = true))
        assertEquals("false", configuredTarget["zigbee_router"])
    }
    @Test fun roundTripsThroughSerialize() {
        val b = ConfigBundle.fromValues(
            values = mapOf("mqtt_broker" to "tcp://ha:1883", "friendly_name" to "Front Room"),
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

    @Test fun credentialBundlePreservesPasswordWhitespaceAndOauthExpiry() {
        val values = mapOf(
            "mqtt_password" to "  exact password bytes  ",
            "ha_token_expiry" to "2345678901",
        )
        val parsed = ConfigBundle.parse(ConfigBundle.fromValues(values).serialize())!!

        assertEquals("  exact password bytes  ", parsed.values["mqtt_password"])
        assertEquals("2345678901", parsed.values["ha_token_expiry"])
        assertEquals(
            "  exact password bytes  ",
            (SettingValue.validate(
                SettingsRegistry.spec("mqtt_password")!!,
                parsed.values.getValue("mqtt_password"),
            ) as Validation.Ok).normalized,
        )
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

    @Test fun schemaOneAddsTheBackwardCompatibleAutomaticBrightnessFloor() {
        val (migrated, warnings) = Migrations.migrate(1, mapOf("a" to "1"))

        assertEquals("1", migrated["a"])
        assertEquals("4", migrated["auto_brightness_minimum_percent"])
        assertTrue(warnings.isEmpty())
    }

    @Test fun schemaOnePreservesAnExplicitAutomaticBrightnessFloor() {
        val (migrated, warnings) = Migrations.migrate(
            1,
            mapOf("auto_brightness_minimum_percent" to "25"),
        )

        assertEquals("25", migrated["auto_brightness_minimum_percent"])
        assertTrue(warnings.isEmpty())
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
