package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `POST /api/v1/backup` used to read an absent `include_companion` as `true`, so the simplest possible
 * backup request refused with 422 on every panel that has no HA Companion installed — which is every panel
 * in a fleet whose Companion registrations were removed. The fix is not a new default but a third answer:
 * the request either named the Companion or it did not, and only the unnamed case is allowed to look at
 * what the panel actually has.
 *
 * These are the properties that make that safe. Naming the Companion must survive to the capture path
 * intact, so an explicit request can still refuse loudly; omitting it must degrade *only* when there is
 * genuinely nothing to capture, so an omitted request can never hand back an archive that quietly lacks a
 * login the panel really holds.
 */
class CompanionBackupRequestTest {
    private val serverSource by lazy { TestSources.kotlin("http/PaneldServer.kt").readText() }
    private val routeSource by lazy { TestSources.kotlin("http/ControlPlaneRoutes.kt").readText() }

    private fun refusingProbe(): () -> Boolean = { fail("the installation probe must not be consulted"); false }

    @Test fun `the documented values parse and every other spelling is refused`() {
        assertEquals(CompanionBackupRequest.OMITTED, parseCompanionBackupRequest(null))
        assertEquals(CompanionBackupRequest.REQUIRED, parseCompanionBackupRequest("true"))
        assertEquals(CompanionBackupRequest.REQUIRED, parseCompanionBackupRequest("1"))
        assertEquals(CompanionBackupRequest.EXCLUDED, parseCompanionBackupRequest("false"))
        assertEquals(CompanionBackupRequest.EXCLUDED, parseCompanionBackupRequest("0"))
        // Each of these has a plausible intent and no defensible reading. Returning EXCLUDED for any of
        // them is the silent downgrade the caller would never see, so the parser refuses instead.
        listOf("", " ", "yes", "no", "on", "off", "TRUE", "False", "2", "-1", "null", "include").forEach { raw ->
            assertNull("$raw must not be interpreted", parseCompanionBackupRequest(raw))
        }
    }

    @Test fun `a request that named the Companion is answered without consulting the panel`() {
        // Structural, not incidental: if REQUIRED ever read the probe, a panel that answered "absent" —
        // through an uninstall race, a PackageManager failure, or a future caching bug — would turn an
        // explicit request into a config-only archive with a 200 and no explanation.
        assertTrue(resolveCompanionInclusion(CompanionBackupRequest.REQUIRED, refusingProbe()))
        assertFalse(resolveCompanionInclusion(CompanionBackupRequest.EXCLUDED, refusingProbe()))
    }

    @Test fun `an omitted request follows the panel and reads it exactly once`() {
        var probes = 0
        assertTrue(
            resolveCompanionInclusion(CompanionBackupRequest.OMITTED) { probes++; true },
        )
        assertEquals(1, probes)

        probes = 0
        assertFalse(
            "a panel with no Companion has nothing to include, so it gets its own backup instead of a refusal",
            resolveCompanionInclusion(CompanionBackupRequest.OMITTED) { probes++; false },
        )
        assertEquals("one observation decides the archive; a second could disagree with the first", 1, probes)
    }

    @Test fun `the staging reservation describes the resolved backup rather than the largest possible one`() {
        val withCompanion = backupStagingRequirement(includeCompanion = true, encrypted = false)
        val configOnly = backupStagingRequirement(includeCompanion = false, encrypted = false)
        assertTrue("a Companion capture must reserve strictly more room", withCompanion > configOnly)

        // Resolving first is what makes the bound honest. Reserving the Companion peak for an omitted
        // request on a Companion-free panel could refuse a config-only backup for storage it never needs.
        val omittedOnCompanionFreePanel =
            resolveCompanionInclusion(CompanionBackupRequest.OMITTED) { false }
        assertEquals(
            configOnly,
            backupStagingRequirement(omittedOnCompanionFreePanel, encrypted = false),
        )
    }

    /**
     * `buildBackupArtifact` is an Android-bound member, so its ordering is pinned by source rather than
     * executed here. Three facts carry the composition proof: the request is resolved before anything is
     * reserved, the reservation is fed the resolved value, and the resolved `true` still reaches the
     * unchanged capture path — which is what keeps an omitted request on a Companion-installed panel
     * failing loudly when the capture itself fails.
     */
    @Test fun `the artifact builder resolves first, reserves from the resolution, and still captures`() {
        val start = serverSource.indexOf("private fun buildBackupArtifact")
        assertTrue("buildBackupArtifact must be present", start > 0)
        val body = serverSource.substring(start, serverSource.indexOf("\n    }", start))

        val resolve = body.indexOf("resolveCompanionInclusion(request)")
        val reserve = body.indexOf("backupStagingRequirement(includeCompanion")
        val capture = body.indexOf("if (includeCompanion) captureCompanion() else null")
        assertTrue("the request must be resolved before storage is reserved", resolve in 0 until reserve)
        assertTrue("the reservation must use the resolved value", reserve in 0 until capture)
        assertTrue("a resolved inclusion must still reach the unchanged capture path", capture > 0)
        assertTrue(
            "only installation may make an omitted request skip the capture",
            "CompanionInstaller.installedPkg(appContext) != null" in body,
        )
        assertFalse(
            "the builder must not take a Boolean again; that is the conflation this replaced",
            "buildBackupArtifact(includeCompanion: Boolean" in serverSource,
        )
    }

    /**
     * That the refusal happens before the approval and delivery gates is proven behaviourally, with exact
     * counts, by `ControlPlaneRoutesTest`. All that is left to pin here is that the parse this replaced
     * cannot come back: it is the one expression that could reintroduce the true-by-default reading
     * without changing anything a route test would notice.
     */
    @Test fun `the retired truthy-or-default parse is gone from the route`() {
        val start = routeSource.indexOf("private suspend fun handleBackup")
        assertTrue("handleBackup must be present", start > 0)
        val body = routeSource.substring(start, routeSource.indexOf("\nprivate val PLAY_URL", start))
        assertTrue("the route must parse through the shared tri-state reader", "parseCompanionBackupRequest(" in body)
        assertFalse(
            "the old truthy-or-true parse must be gone",
            """parameters["include_companion"]?.let { it == "true" || it == "1" } ?: true""" in body,
        )
    }

    /**
     * The published contract is the only surface that ever claimed a default, and the claim was the bug.
     * `allow_plaintext` genuinely defaults to false and must keep saying so.
     */
    @Test fun `the OpenAPI document describes the conditional behaviour instead of a default`() {
        val openApi = TestSources.asset("openapi.json").readText()
        val start = openApi.indexOf("\"/api/v1/backup\"")
        assertTrue("the backup path must be documented", start > 0)
        val backup = openApi.substring(start, openApi.indexOf("\"/api/v1/uninstall\"", start))
        assertTrue("the slice must cover the whole backup operation", "include_companion" in backup)
        assertFalse(
            "include_companion must not advertise a default it does not have",
            """"include_companion": { "type": "boolean", "default": true }""" in backup,
        )
        assertFalse("no default of any value belongs on include_companion", "\"default\": true" in backup)
        assertTrue(
            "the omitted behaviour must be documented",
            "only when a supported Companion is installed" in backup,
        )
        assertTrue("the explicit-true refusal must be documented", "fails with 422 when it cannot be captured" in backup)
        assertTrue("the refused-value behaviour must be documented", "is rejected" in backup)
        assertTrue(
            "allow_plaintext really does default to false and must keep saying so",
            """"allow_plaintext": { "type": "boolean", "default": false""" in backup,
        )
    }
}
