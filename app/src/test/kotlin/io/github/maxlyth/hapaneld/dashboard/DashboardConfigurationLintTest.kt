package io.github.maxlyth.hapaneld.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardConfigurationLintTest {
    @Test
    fun slashRegexSafetyRejectsBacktrackingConstructsButKeepsCommonEntityPatterns() {
        assertTrue(DashboardConfigurationLint.isSafeEntitySelectorRegex("^sensor\\..*_temperature$"))
        assertTrue(DashboardConfigurationLint.isSafeEntitySelectorRegex("^sensor\\.[a-z0-9_]+$"))
        assertFalse(DashboardConfigurationLint.isSafeEntitySelectorRegex("^sensor\\..*.*_temperature$"))
        assertFalse(DashboardConfigurationLint.isSafeEntitySelectorRegex("^.*sensor.*$"))
        assertFalse(DashboardConfigurationLint.isSafeEntitySelectorRegex("^sensor\\.[a-z]+_[a-z]+$"))
        assertFalse(DashboardConfigurationLint.isSafeEntitySelectorRegex("(a+)+$"))
        assertFalse(DashboardConfigurationLint.isSafeEntitySelectorRegex("(a|aa)+$"))
        assertFalse(DashboardConfigurationLint.isSafeEntitySelectorRegex("^(.*.*.*.*.*)z$"))
        assertFalse(DashboardConfigurationLint.isSafeEntitySelectorRegex("^(sensor)\\.\\1$"))
    }

    @Test fun resolvesStructuralSupersetsWithoutUsingDynamicRefinementsOrDisplayLimits() {
        val config = """{"views":[{"title":"Overview","path":"overview","cards":[
          {"type":"custom:auto-entities","title":"Room lights","filter":{"include":[
            {"domain":"light","area":"study","state":"on","options":{"secondary_info":"last-changed"}}
          ],"exclude":[{"entity_id":"light.study_hidden"}]},"sort":{"count":1}}
        ]}]}"""
        val catalog = listOf("light.study_main", "light.study_hidden", "light.kitchen", "sensor.study")
        val metadata = mapOf(
            "light.study_main" to """{"ai":"study"}""",
            "light.study_hidden" to """{"ai":"study"}""",
            "light.kitchen" to """{"ai":"kitchen"}""",
            "sensor.study" to """{"ai":"study"}""",
        )

        val result = DashboardConfigurationLint.analyze(config, catalog, metadata)

        assertEquals(setOf("light.study_main"), result.safeEntityIds)
        assertFalse(result.blocking)
    }

    @Test fun supportsExactGlobRegexAreaFloorAndLabelStructuralBounds() {
        val config = """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
          {"entity_id":"sensor.exact"},
          {"entity_id":"light.study_*"},
          {"entity_id":"/.*_rssi$/"},
          {"area":"study"},
          {"floor":"upper"},
          {"label":"important"}
        ]}}]}]}"""
        val catalog = listOf(
            "sensor.exact", "light.study_main", "sensor.router_rssi", "binary_sensor.study_window",
            "cover.upper_blind", "switch.priority", "sensor.other",
        )
        val metadata = mapOf(
            "binary_sensor.study_window" to """{"ai":"study"}""",
            "cover.upper_blind" to """{"fi":"upper"}""",
            "switch.priority" to """{"lb":["important"]}""",
        )

        val result = DashboardConfigurationLint.analyze(config, catalog, metadata)

        assertEquals(catalog.dropLast(1).toSet(), result.safeEntityIds)
        assertTrue(result.issues.isEmpty())
    }

    @Test fun dynamicExcludeDoesNotUnsafelyReduceCandidateSuperset() {
        val config = """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{
          "include":[{"domain":"sensor"}],
          "exclude":[{"domain":"sensor","state":"unavailable"}]
        }}]}]}"""
        val catalog = (1..65).map { "sensor.sample_$it" }

        val result = DashboardConfigurationLint.analyze(config, catalog, emptyMap())

        assertTrue(result.safeEntityIds.isEmpty())
        assertEquals(DashboardConfigurationLint.IssueType.BROAD_SELECTOR, result.issues.single().type)
        assertEquals(65, result.issues.single().candidateCount)
    }

    @Test fun friendlyNameRegexBoundsCommonAlarmSelectorsBeforeApplyingDynamicStateRules() {
        val config = """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{
          "include":[{"domain":"binary_sensor","name":"/otion [Gg]roup/","state":"on","sort":{"method":"friendly_name"}}],
          "exclude":[{"state":"unknown"},{"state":"unavailable"}]
        }}]}]}"""
        val catalog = entities("binary_sensor", 537)
        val names = catalog.associateWith { id ->
            when (id) {
                "binary_sensor.sample_7" -> "Ground Floor Motion Group"
                "binary_sensor.sample_42" -> "Garage motion group"
                else -> "Contact ${id.substringAfterLast('_')}"
            }
        }

        val result = DashboardConfigurationLint.analyze(config, catalog, emptyMap(), names)

        assertEquals(setOf("binary_sensor.sample_7", "binary_sensor.sample_42"), result.safeEntityIds)
        assertTrue(result.issues.isEmpty())
    }

    @Test fun friendlyNameMatchingIsCaseSensitiveAndMissingNamesNeverMatch() {
        val config = """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
          {"domain":"binary_sensor","name":"Window Group"}
        ]}}]}]}"""
        val catalog = listOf("binary_sensor.exact", "binary_sensor.lower", "binary_sensor.missing")

        val result = DashboardConfigurationLint.analyze(
            config, catalog, emptyMap(),
            mapOf("binary_sensor.exact" to "Window Group", "binary_sensor.lower" to "window group"),
        )

        assertEquals(setOf("binary_sensor.exact"), result.safeEntityIds)
        assertTrue(result.issues.isEmpty())
    }

    @Test fun unsafeFriendlyNamePatternCannotNarrowABroadDomain() {
        val config = """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
          {"domain":"binary_sensor","name":"/(.*.*)group/"}
        ]}}]}]}"""
        val catalog = entities("binary_sensor", 65)
        val names = catalog.associateWith { "Window group" }

        val result = DashboardConfigurationLint.analyze(config, catalog, emptyMap(), names)

        assertTrue(result.safeEntityIds.isEmpty())
        assertEquals(DashboardConfigurationLint.IssueType.BROAD_SELECTOR, result.issues.single().type)
        assertEquals(65, result.issues.single().candidateCount)
    }

    @Test fun structuralExcludeCanBoundASelectorWithoutSamplingIt() {
        val config = """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{
          "include":[{"domain":"sensor"}],
          "exclude":[{"entity_id":"sensor.omit_*"}]
        }}]}]}"""
        val catalog = entities("sensor", 64) + (1..20).map { "sensor.omit_$it" }

        val result = DashboardConfigurationLint.analyze(config, catalog, emptyMap())

        assertEquals(64, result.safeEntityIds.size)
        assertTrue(result.safeEntityIds.none { it.startsWith("sensor.omit_") })
        assertTrue(result.issues.isEmpty())
    }

    @Test fun unboundedTemplateIsBlockingAndNeverLeaksTemplateText() {
        val secretMarker = "private_marker"
        val config = """{"views":[{"title":"Overview","cards":[
          {"type":"custom:auto-entities","title":"{{ $secretMarker }}","filter":{"include":[
            {"entity_id":"{{ states('input_text.$secretMarker') }}","state":"on"}
          ]}}
        ]}]}"""

        val result = DashboardConfigurationLint.analyze(config, listOf("light.sample"), emptyMap())
        val issue = result.issues.single()

        assertTrue(result.blocking)
        assertEquals(DashboardConfigurationLint.IssueType.UNBOUNDED_SELECTOR, issue.type)
        assertNull(issue.cardTitle)
        assertFalse(issue.toJson().toString().contains(secretMarker))
    }

    @Test fun broadRepeatedRulesAreGroupedPerViewWithEverySourceLocation() {
        fun card(domain: String) = """{"type":"custom:auto-entities","filter":{"include":[{"domain":"$domain"}]}}"""
        val cards = listOf(
            card("light"), card("light"),
            card("binary_sensor"), card("binary_sensor"), card("binary_sensor"), card("binary_sensor"), card("binary_sensor"),
            card("sensor"), card("sensor"), card("sensor"), card("sensor"),
            card("switch"),
        ).joinToString(",")
        val config = """{"views":[{"title":"Overview","path":"overview","cards":[$cards]}]}"""
        val catalog = entities("light", 72) + entities("binary_sensor", 537) +
            entities("sensor", 1_600) + entities("switch", 263)

        val result = DashboardConfigurationLint.analyze(config, catalog, emptyMap())

        assertTrue(result.safeEntityIds.isEmpty())
        assertEquals(4, result.issues.size)
        assertEquals(2_472, result.issues.sumOf { it.candidateCount ?: 0 })
        assertEquals(listOf(2, 5, 4, 1), listOf("light", "binary_sensor", "sensor", "switch").map { domain ->
            result.issues.single { it.ruleSummary == "domain $domain" }.sourceLocations.size
        })
        assertTrue(result.issues.all { it.blocking && it.viewPath == "overview" })
    }

    @Test fun selectorUnionOverBudgetIsBlockingWithoutDiscardingBoundedEvidence() {
        val config = """{"views":[{"cards":[
          {"type":"custom:auto-entities","filter":{"include":[{"domain":"sensor"}]}},
          {"type":"custom:auto-entities","filter":{"include":[{"domain":"light"}]}},
          {"type":"custom:auto-entities","filter":{"include":[{"domain":"switch"}]}}
        ]}]}"""
        val catalog = entities("sensor", 50) + entities("light", 50) + entities("switch", 50)

        val result = DashboardConfigurationLint.analyze(config, catalog, emptyMap())

        assertEquals(150, result.safeEntityIds.size)
        val issue = result.issues.single()
        assertEquals(DashboardConfigurationLint.IssueType.SELECTOR_BUDGET, issue.type)
        assertEquals(150, issue.candidateCount)
        assertEquals(3, issue.sourceLocations.size)
    }

    @Test fun issueJsonUsesStableUiContractAndFingerprint() {
        val config = """{"views":[{"title":"Overview","path":"overview","cards":[
          {"type":"custom:auto-entities","title":"Sensors","filter":{"include":[{"domain":"sensor"}]}}
        ]}]}"""
        val catalog = entities("sensor", 65)
        val first = DashboardConfigurationLint.analyze(config, catalog, emptyMap()).issues.single()
        val second = DashboardConfigurationLint.analyze(config, catalog.reversed(), emptyMap()).issues.single()
        val json = first.toJson()

        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals("error", json.getString("severity"))
        assertEquals("broad_selector", json.getString("type"))
        assertEquals("Overview", json.getString("view_title"))
        assertEquals("overview", json.getString("view_path"))
        assertEquals("Sensors", json.getString("card_title"))
        assertEquals(65, json.getInt("candidate_count"))
        assertTrue(json.getJSONArray("source_locations").getString(0).endsWith("cards[0]"))
    }

    private fun entities(domain: String, count: Int) = (1..count).map { "$domain.sample_$it" }
}
