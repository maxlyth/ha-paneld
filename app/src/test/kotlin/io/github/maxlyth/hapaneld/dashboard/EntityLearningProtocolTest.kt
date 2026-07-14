package io.github.maxlyth.hapaneld.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityLearningProtocolTest {
    @Test fun emptyInstallBootstrapsButExistingManualSetIsPreserved() {
        assertTrue(shouldBootstrapEntityLearning(applied = false, configuredIds = emptyList()))
        assertFalse(shouldBootstrapEntityLearning(applied = false, configuredIds = listOf("light.kitchen")))
        assertFalse(shouldBootstrapEntityLearning(applied = true, configuredIds = emptyList()))
    }

    @Test fun subscriptionPreviewMakesApplyOutcomeExplicit() {
        assertEquals(
            EntitySubscriptionPreview(currentCount = 3, additions = 1, removals = 1, streamChange = true),
            previewEntitySubscription(
                filtered = true,
                currentIds = listOf("light.a", "light.b", "sensor.c"),
                catalogCount = 100,
                desiredIds = listOf("light.a", "light.b", "person.d"),
            ),
        )
        assertEquals(
            EntitySubscriptionPreview(currentCount = 3, additions = 0, removals = 0, streamChange = false),
            previewEntitySubscription(true, listOf("light.a", "light.b", "sensor.c"), 100, listOf("sensor.c", "light.b", "light.a")),
        )
        assertEquals(
            EntitySubscriptionPreview(currentCount = 100, additions = 0, removals = 97, streamChange = true),
            previewEntitySubscription(false, emptyList(), 100, listOf("light.a", "light.b", "sensor.c")),
        )
    }

    @Test fun observerAttributesInitialHydrationAndFlushesPromptly() {
        val script = EntityLearningProtocol.documentStartScript("https://example.test")
        assertTrue(script.contains("JSON.stringify(event.a[k]).length"))
        assertTrue(script.contains("m.id!==entitySubscriptionId"))
        assertTrue(script.contains("Array.isArray(decoded)?decoded:[decoded]"))
        assertTrue(script.contains("},5000);"))
    }

    @Test fun dashboardScannerFindsNestedLiteralsActionsTargetsAndUnresolvedCustomLogic() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[
              {"type":"tile","entity":"light.kitchen"},
              {"type":"custom:bubble-card","tap_action":{"target":{"area_id":"office"}},"name":"sensor.room_temperature"},
              {"type":"custom:mystery-card","value":"[[[ return variables.entity ]]]"}
            ]}]}""",
        )
        assertEquals(setOf("light.kitchen", "sensor.room_temperature"), scan.entityIds)
        assertTrue(scan.targets.single().contains("area_id"))
        assertTrue(scan.unresolved.any { it.contains("custom:mystery-card") })
        assertTrue(scan.unresolved.any { it.contains("dynamic-template") })
    }

    @Test fun canonicalDashboardHashIsIndependentOfObjectKeyOrder() {
        val a = EntityLearningProtocol.canonical(org.json.JSONObject("""{"b":2,"a":{"d":4,"c":3}}"""))
        val b = EntityLearningProtocol.canonical(org.json.JSONObject("""{"a":{"c":3,"d":4},"b":2}"""))
        assertEquals(a, b)
        assertEquals(EntityLearningProtocol.hash(a), EntityLearningProtocol.hash(b))
    }

    @Test fun accessAndMetricBatchesAcceptOnlyEntityIds() {
        val access = EntityLearningProtocol.parseAccessBatch(
            """{"accessed":["light.a","bad"],"missing":["sensor.b","Light.c"]}""",
        )
        assertEquals(mapOf("light.a" to 1L), access.first)
        assertEquals(setOf("sensor.b"), access.second)
        assertEquals(mapOf("sensor.b" to (3L to 120L)), EntityLearningProtocol.parseMetricBatch("""{"sensor.b":[3,120],"bad":[9,9]}"""))
    }

    @Test fun accessBatchPreservesLookupCountsAndAcceptsPrototypeArrays() {
        val counted = EntityLearningProtocol.parseAccessBatch(
            """{"accessed":{"light.kitchen":17,"sensor.room":2},"missing":[]}""",
        )
        assertEquals(mapOf("light.kitchen" to 17L, "sensor.room" to 2L), counted.first)
        assertEquals(mapOf("light.kitchen" to 1L), EntityLearningProtocol.parseAccessBatch(
            """{"accessed":["light.kitchen"],"missing":[]}""",
        ).first)
    }

    @Test fun dashboardUrlPathDistinguishesDefaultLovelace() {
        assertEquals("sample-panel", EntityLearningProtocol.dashboardUrlPath("/sample-panel/dash"))
        assertEquals("", EntityLearningProtocol.dashboardUrlPath("/lovelace/0"))
    }

    @Test fun learningScriptPreservesWebsocketShapeAndBatchesBridgeCalls() {
        val script = EntityLearningProtocol.documentStartScript("https://ha.example")
        assertTrue(script.contains("entityLearningAccesses"))
        assertTrue(script.contains("entityLearningMetrics"))
        assertTrue(script.contains("Object.setPrototypeOf(LearningWebSocket,Parent)"))
        assertTrue(script.contains("wss://ha.example"))
    }

    @Test fun autoEntitiesSelectorsResolveDomainsGlobsAreasAndExclusions() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[{"domain":"sensor","area":"office"},{"entity_id":"light.kitchen*"}],"exclude":[{"entity_id":"sensor.office_noisy"}]}}]}]}""",
        )
        val metadata = mapOf(
            "sensor.office_temp" to """{"ai":"office"}""",
            "sensor.office_noisy" to """{"ai":"office"}""",
            "sensor.kitchen_temp" to """{"ai":"kitchen"}""",
            "light.kitchen_ceiling" to "{}",
        )
        assertEquals(
            setOf("sensor.office_temp", "light.kitchen_ceiling"),
            EntityLearningProtocol.resolveSelectors(scan.selectors, metadata.keys, metadata),
        )
    }
}
