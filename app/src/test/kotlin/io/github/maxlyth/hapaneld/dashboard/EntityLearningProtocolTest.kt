package io.github.maxlyth.hapaneld.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityLearningProtocolTest {
    @Test fun emptyInstallBootstrapsButExistingManualSetIsPreserved() {
        assertTrue(shouldBootstrapEntityLearning(learningEnabled = true, applied = false, configuredIds = emptyList()))
        assertFalse(shouldBootstrapEntityLearning(learningEnabled = true, applied = false, configuredIds = listOf("light.kitchen")))
        assertFalse(shouldBootstrapEntityLearning(learningEnabled = true, applied = true, configuredIds = emptyList()))
        assertFalse(shouldBootstrapEntityLearning(learningEnabled = false, applied = false, configuredIds = emptyList()))
    }

    @Test fun blockingDashboardRejectsWholeAutomaticSetAndDisabledScanOnlyObserves() {
        assertEquals(
            AutomaticSyncDecision.BLOCKED,
            automaticSyncDecision(true, applied = true, configuredIds = listOf("light.known_good"), blockingIssues = true),
        )
        assertEquals(
            AutomaticSyncDecision.BLOCKED,
            automaticSyncDecision(true, applied = false, configuredIds = emptyList(), blockingIssues = true),
        )
        assertEquals(
            AutomaticSyncDecision.OBSERVE,
            automaticSyncDecision(false, applied = false, configuredIds = emptyList(), blockingIssues = false),
        )
        assertEquals(
            AutomaticSyncDecision.BOOTSTRAP,
            automaticSyncDecision(
                learningEnabled = true,
                applied = false,
                configuredIds = listOf("light.known_good"),
                blockingIssues = false,
                forceBootstrap = true,
            ),
        )
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

    @Test fun dashboardScannerReturnsStructuredDynamicExpressionsWithVerbatimLiterals() {
        val literal = "[[[ return hass.states['sensor.room'].state; ]]]"
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[{"type":"custom:sample-card","value":${org.json.JSONObject.quote(literal)}}]}]}""",
        )

        val expression = scan.dynamicExpressions.single()
        assertEquals("dashboard.views[0].cards[0].value", expression.sourceLocation)
        assertEquals(literal, expression.literal)
        assertFalse(expression.truncated)
        assertEquals(expression, scan.dynamicExpressions.single())
        assertEquals(literal, expression.toJson().getString("literal"))
        assertEquals(expression.sourceLocation, expression.toJson().getString("source_location"))
    }

    @Test fun dynamicExpressionLiteralIsBoundedAndFingerprintUsesTheCompleteValue() {
        val prefix = "{{ "
        val first = prefix + "a".repeat(EntityLearningProtocol.MAX_DYNAMIC_EXPRESSION_LENGTH + 100) + " }}"
        val second = prefix + "a".repeat(EntityLearningProtocol.MAX_DYNAMIC_EXPRESSION_LENGTH + 99) + "b }}"
        fun scan(value: String) = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[{"type":"markdown","content":${org.json.JSONObject.quote(value)}}]}]}""",
        ).dynamicExpressions.single()

        val a = scan(first)
        val b = scan(second)

        assertEquals(EntityLearningProtocol.MAX_DYNAMIC_EXPRESSION_LENGTH, a.literal.length)
        assertTrue(a.truncated)
        assertEquals(a.literal, b.literal)
        assertFalse(a.fingerprint == b.fingerprint)
    }

    @Test fun autoEntitiesFilterExpressionsAreObservedButNotPromotedAsStaticIds() {
        val literal = "{{ states('sensor.selector_source') }}"
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[{"entity_id":${org.json.JSONObject.quote(literal)}}]}}]}]}""",
        )

        assertTrue(scan.entityIds.isEmpty())
        assertEquals(literal, scan.dynamicExpressions.single().literal)
        assertEquals(
            "dashboard.views[0].cards[0].filter.include[0].entity_id",
            scan.dynamicExpressions.single().sourceLocation,
        )
    }

    @Test fun dynamicExpressionsAreDeterministicallyOrderedBySource() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[
              {"type":"markdown","content":"{{ states('sensor.first') }}"},
              {"type":"markdown","content":"[[[ return states['sensor.second']; ]]]"}
            ]}]}""",
        )

        assertEquals(
            listOf("dashboard.views[0].cards[0].content", "dashboard.views[0].cards[1].content"),
            scan.dynamicExpressions.map { it.sourceLocation },
        )
        assertEquals(2, scan.dynamicExpressions.map { it.fingerprint }.distinct().size)
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
            EntityLearningProtocol.resolveSelectors(scan.selectors, metadata.keys, metadata).entityIds,
        )
    }

    @Test fun autoEntitiesExcludeIdsAreNotCollectedAsOrdinaryStaticReferences() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[{"domain":"light"}],"exclude":[{"entity_id":"light.hidden"}]}}]}]}""",
        )
        assertFalse("light.hidden" in scan.entityIds)
    }

    @Test fun broadSelectorIsOmittedWholeAndReportedAsUnresolved() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[{"domain":"sensor"}]}}]}]}""",
        )
        val catalog = (1..(EntityLearningProtocol.SELECTOR_ENTITY_LIMIT + 1)).map { "sensor.sample_$it" }
        val resolution = EntityLearningProtocol.resolveSelectors(scan.selectors, catalog, emptyMap())
        assertTrue(resolution.entityIds.isEmpty())
        assertTrue(resolution.unresolved.single().contains("broad-selector"))
        assertTrue(resolution.unresolved.single().contains((EntityLearningProtocol.SELECTOR_ENTITY_LIMIT + 1).toString()))
    }

    @Test fun selectorBudgetOmitsWholeLaterSelectorDeterministically() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
              {"domain":"sensor"},{"domain":"light"},{"domain":"switch"}
            ]}}]}]}""",
        )
        val perDomain = 50
        val catalog = listOf("sensor", "light", "switch").flatMap { domain ->
            (1..perDomain).map { "$domain.sample_$it" }
        }
        val resolution = EntityLearningProtocol.resolveSelectors(scan.selectors, catalog, emptyMap())
        assertEquals(100, resolution.entityIds.size)
        assertTrue(resolution.entityIds.none { it.startsWith("switch.") })
        assertTrue(resolution.unresolved.single().contains("selector-budget"))
    }

    @Test fun regexAndTemplateTargetsAreObservedAsDynamicNotSentToHomeAssistant() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[
              {"type":"custom:auto-entities","filter":{"include":[{"entity_id":"/.*_rssi/"}]}},
              {"type":"button","tap_action":{"target":{"entity_id":"{{ dynamic_entity }}","area_id":"office"}}}
            ]}]}""",
        )
        assertEquals(1, scan.targets.size)
        assertTrue(scan.targets.single().contains("area_id"))
        assertFalse(scan.targets.single().contains("entity_id"))
        assertEquals(1, scan.unresolved.count { it.contains("dynamic-target") })
    }

    @Test fun literalEntityTargetsAreResolvedLocallyWithoutHomeAssistantExpansion() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[
              {"type":"tile","entity_id":"light.kitchen"},
              {"type":"button","target":{"entity_id":["switch.fan","switch.heater"]}},
              {"type":"button","target":{"entity_id":"cover.blind","device_id":"device_123"}}
            ]}]}""",
        )
        assertEquals(setOf("light.kitchen", "switch.fan", "switch.heater", "cover.blind"), scan.entityIds)
        assertEquals(1, scan.targets.size)
        assertTrue(scan.targets.single().contains("device_id"))
        assertFalse(scan.targets.single().contains("entity_id"))
    }
}
