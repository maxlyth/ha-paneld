package io.github.maxlyth.hapaneld.dashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The manager-side policies behind recovering a panel held on a strategy-generated default dashboard. */
class EntityLearningHoldRecoveryTest {
    private fun issue(type: String, fingerprint: String, ignorable: Boolean = true, blocking: Boolean = true) =
        JSONObject()
            .put("type", type)
            .put("blocking", blocking)
            .put("would_block", blocking)
            .put("ignorable", ignorable)
            .put("fingerprint", fingerprint)

    private val strategy = issue("unbounded_selector", "0123456789abcdef")
    private val fence = issue("diagnostic_limit", "fedcba9876543210", ignorable = false)
    private val advisory = issue("runtime_coverage", "1111222233334444", ignorable = false, blocking = false)

    @Test fun `upgrade recovery needs the migration latch, a retained filter and a non-empty recovered set`() {
        val retained = listOf("light.kitchen", "sensor.hall")
        val recovered = listOf("light.kitchen")
        assertTrue(upgradeRecoveryRestoresFilter(true, retained, recovered))
        // A person's own reset also forces a bootstrap; it is not the migration and does not qualify.
        assertFalse(upgradeRecoveryRestoresFilter(false, retained, recovered))
        // No retained list means filtering was never accepted on this dashboard.
        assertFalse(upgradeRecoveryRestoresFilter(true, emptyList(), recovered))
        // An empty recovered set would commit the fail-closed sentinel: a blank dashboard.
        assertFalse(upgradeRecoveryRestoresFilter(true, retained, emptyList()))
    }

    @Test fun `the recovery preview counts only what survives the commit`() {
        // `commitSync` clears referenced_by_config and re-sets it for THIS scan's derived ids, so a
        // preview that counted the catalogue's static rows would count rows the apply then drops: an
        // analyzer-policy upgrade with no pins and no runtime evidence would pass this check and commit
        // the empty fail-closed sentinel. Pins and runtime rows survive; this scan's own ids are added
        // directly. The wiring needs a live scan, so it is pinned against the source.
        val manager = listOf(
            java.io.File("src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityLearningManager.kt"),
            java.io.File("app/src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityLearningManager.kt"),
        ).first { it.isFile }.readText()
        val preview = manager.substringAfter("val upgradeRecovery = snapshot.upgradeRebootstrapPending")
            .substringBefore("val defaultIgnoredFingerprints")
        assertTrue("the preview must exclude the catalogue's static rows", preview.contains("includeStatic = false"))
        assertTrue(preview.contains("includeRuntime = snapshot.autoRuntime"))
        assertTrue(
            "this scan's own ids must be projected through the ignore set the recovery would create",
            preview.contains("effectiveLintEntityIds(lint, candidateIgnoredFingerprints)"),
        )
        // The candidate set is the stored ignores plus what this recovery would add, never more.
        assertTrue(manager.contains("storedIgnoredFingerprints + ignorableIssueFingerprints(rawIssues)"))
        assertTrue(manager.contains("val ignoredFingerprints = storedIgnoredFingerprints + defaultIgnoredFingerprints"))
    }

    @Test fun `only ignorable blocking findings are ever eligible for a default`() {
        assertEquals(
            setOf("0123456789abcdef"),
            ignorableIssueFingerprints(listOf(strategy, fence, advisory)),
        )
        assertEquals(emptySet<String>(), ignorableIssueFingerprints(listOf(fence, advisory)))
    }

    @Test fun `upgrade recovery records ignorable blocking rules as ignored and nothing else`() {
        val issues = listOf(strategy, fence, advisory)
        assertEquals(
            setOf("0123456789abcdef"),
            defaultIgnoredIssueFingerprints(
                initialActivationPending = false, bootstrap = true, issues = issues, upgradeRecovery = true,
            ),
        )
        // Only a bootstrap may take the default; an applied filter keeps its own decisions.
        assertEquals(
            emptySet<String>(),
            defaultIgnoredIssueFingerprints(
                initialActivationPending = false, bootstrap = false, issues = issues, upgradeRecovery = true,
            ),
        )
        // Without either trigger the rule holds the renderer exactly as before.
        assertEquals(
            emptySet<String>(),
            defaultIgnoredIssueFingerprints(
                initialActivationPending = false, bootstrap = true, issues = issues,
            ),
        )
        // The first-run path is unchanged by the new parameter.
        assertEquals(
            setOf("0123456789abcdef"),
            defaultIgnoredIssueFingerprints(
                initialActivationPending = true, bootstrap = true, issues = issues,
            ),
        )
    }

    @Test fun `a probe reports a change only for a different dashboard or a different revision`() {
        assertEquals(
            DashboardProbeOutcome.UNCHANGED,
            dashboardProbeOutcome(resolvedPath = "/lovelace", boundPath = "/lovelace", liveRevision = "aa", storedRevision = "aa"),
        )
        assertEquals(
            DashboardProbeOutcome.CHANGED,
            dashboardProbeOutcome(resolvedPath = "/lovelace", boundPath = "/lovelace", liveRevision = "bb", storedRevision = "aa"),
        )
        // The account default moved to another dashboard: the bound scope no longer describes the panel.
        assertEquals(
            DashboardProbeOutcome.CHANGED,
            dashboardProbeOutcome(resolvedPath = "/dashboard-wall", boundPath = "/lovelace", liveRevision = "aa", storedRevision = "aa"),
        )
        // A view of the same dashboard is the same scope.
        assertEquals(
            DashboardProbeOutcome.UNCHANGED,
            dashboardProbeOutcome(resolvedPath = "/lovelace/kiosk", boundPath = "/lovelace", liveRevision = "aa", storedRevision = "aa"),
        )
        // Nothing committed yet: a scan is owed whatever is live.
        assertEquals(
            DashboardProbeOutcome.CHANGED,
            dashboardProbeOutcome(resolvedPath = "/lovelace", boundPath = "/lovelace", liveRevision = "aa", storedRevision = ""),
        )
    }

    @Test fun `only a credential rejection turns a failed probe into a recorded problem`() {
        assertTrue(probeFailureSurfacesProblem("states request failed: HTTP 401"))
        assertTrue(probeFailureSurfacesProblem("Home Assistant credential rejected"))
        assertTrue(probeFailureSurfacesProblem("Home Assistant token unavailable"))
        // Home Assistant being down between two probes is the reconnect case: logged, never persisted,
        // so the decision screen is not replaced by a scan-failure screen.
        assertFalse(probeFailureSurfacesProblem("connection timed out for private-host.example"))
        assertFalse(probeFailureSurfacesProblem("Timed out waiting for 60000 ms"))
        assertFalse(probeFailureSurfacesProblem(null))
    }

    @Test fun `the daily tick probes a decision hold and syncs everything else that is due`() {
        val day = 24L * 3_600_000L
        assertEquals(PeriodicMaintenanceAction.NONE, periodicMaintenanceAction(day - 1, day, heldOnDecision = true))
        assertEquals(PeriodicMaintenanceAction.NONE, periodicMaintenanceAction(day - 1, day, heldOnDecision = false))
        assertEquals(PeriodicMaintenanceAction.PROBE, periodicMaintenanceAction(day, day, heldOnDecision = true))
        assertEquals(PeriodicMaintenanceAction.SYNC, periodicMaintenanceAction(day, day, heldOnDecision = false))
    }

    @Test fun `the hold reason names what the person off-panel can act on`() {
        assertNull(entityBootstrapHoldReason(held = false, blockingIssues = 3, problem = null))
        assertEquals(
            EntityBootstrapHoldReason.DECISION,
            entityBootstrapHoldReason(held = true, blockingIssues = 1, problem = null),
        )
        assertEquals(
            EntityBootstrapHoldReason.AUTHENTICATION,
            entityBootstrapHoldReason(held = true, blockingIssues = 1, problem = EntityBootstrapProblem.AUTHENTICATION),
        )
        assertEquals(
            EntityBootstrapHoldReason.SYNCHRONIZATION,
            entityBootstrapHoldReason(held = true, blockingIssues = 0, problem = EntityBootstrapProblem.SYNCHRONIZATION),
        )
        assertEquals(
            EntityBootstrapHoldReason.SYNCHRONIZING,
            entityBootstrapHoldReason(held = true, blockingIssues = 0, problem = null),
        )
        assertEquals("decision", EntityBootstrapHoldReason.DECISION.wireName)
    }

    @Test fun `a strategy-generated default dashboard is recovered on upgrade and still asks otherwise`() {
        // Home Assistant's default dashboard as the scanner sees it: a root strategy and nothing static.
        val config = """{"strategy":{"type":"original-states"}}"""
        val lint = DashboardConfigurationLint.analyze(config, listOf("light.kitchen", "sensor.hall"), emptyMap())
        val raw = lint.issues.map(DashboardConfigurationLint.Issue::toJson)
        val flagged = raw.single()
        assertEquals("unbounded_selector", flagged.getString("type"))
        assertEquals(listOf("dashboard.strategy"), List(flagged.getJSONArray("source_locations").length()) {
            flagged.getJSONArray("source_locations").getString(it)
        })
        assertTrue(flagged.getBoolean("blocking"))
        assertTrue(flagged.getBoolean("ignorable"))

        // The reported panel: upgrade latch pending, a 347-entity filter retained, catalogue rows present.
        val recovery = upgradeRecoveryRestoresFilter(
            upgradeRebootstrapPending = true,
            retainedFilterIds = List(347) { "sensor.retained_$it" },
            recoveredIds = List(347) { "sensor.retained_$it" },
        )
        val ignored = defaultIgnoredIssueFingerprints(
            initialActivationPending = false, bootstrap = true, issues = raw, upgradeRecovery = recovery,
        )
        val effective = JSONArray(EntityCatalogIssuePersistence.applyIgnores(JSONArray(raw), ignored))
        val issue = effective.getJSONObject(0)
        assertFalse(issue.getBoolean("blocking"))
        assertTrue(issue.getBoolean("ignored"))
        assertTrue(issue.getBoolean("would_block"))
        assertEquals("warning", issue.getString("severity"))
        assertEquals(
            AutomaticSyncDecision.BOOTSTRAP,
            automaticSyncDecision(
                learningEnabled = true, applied = false, configuredIds = emptyList(),
                blockingIssues = false, forceBootstrap = true,
            ),
        )

        // The same dashboard on a panel that never ran a filter on it keeps asking.
        val fresh = defaultIgnoredIssueFingerprints(
            initialActivationPending = false, bootstrap = true, issues = raw,
            upgradeRecovery = upgradeRecoveryRestoresFilter(true, emptyList(), emptyList()),
        )
        val stillBlocking = JSONArray(EntityCatalogIssuePersistence.applyIgnores(JSONArray(raw), fresh))
        assertTrue(stillBlocking.getJSONObject(0).getBoolean("blocking"))
        assertEquals(
            AutomaticSyncDecision.BLOCKED,
            automaticSyncDecision(
                learningEnabled = true, applied = false, configuredIds = emptyList(),
                blockingIssues = true, forceBootstrap = true,
            ),
        )
    }

    @Test fun `the lint revision helper and the scan agree on a dashboard's identity`() {
        val config = """{"title":"Home","strategy":{"type":"original-states"}}"""
        val reordered = """{"strategy":{"type":"original-states"},"title":"Home"}"""
        val scanned = DashboardConfigurationLint.analyze(config, emptyList(), emptyMap()).dashboardRevision
        assertEquals(scanned, DashboardConfigurationLint.revision(config))
        assertEquals(scanned, DashboardConfigurationLint.revision(reordered))
        assertTrue(DashboardConfigurationLint.revision("""{"title":"Away"}""") != scanned)
    }
}
