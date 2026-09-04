package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.util.InstallPresentation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallServerPresentationContractTest {
    @Test fun statusWarningOverlayPreservesOrderNullsAndEmptyCardinality() {
        val warnings = listOf("first English warning", "arbitrary diagnostic", "third English warning")
        val presentations = listOf(
            InstallPresentation("status-no-renderer"),
            null,
            InstallPresentation("status-power-caution"),
        )

        assertEquals(
            """[{"code":"status-no-renderer","params":{}},null,{"code":"status-power-caution","params":{}}]""",
            installWarningPresentationsJson(warnings, presentations),
        )
        assertEquals("[]", installWarningPresentationsJson(emptyList(), emptyList()))
    }

    @Test fun statusWarningOverlayFailsClosedOnMismatchOrImpossibleCardinality() {
        assertNull(
            installWarningPresentationsJson(
                listOf("one", "two"),
                listOf(InstallPresentation("status-no-renderer")),
            ),
        )
        assertNull(
            installWarningPresentationsJson(
                List(12) { "warning-$it" },
                List(12) { InstallPresentation("status-no-renderer") },
            ),
        )
    }

    @Test fun everyFrozenRestoreRefusalHasAnExplicitTypedBranch() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        val restore = source.substring(
            source.indexOf("private suspend fun handleRestore"),
            source.indexOf("private fun planCompanionRestore"),
        )
        val expected = setOf(
            "restore-passphrase-required",
            "restore-passphrase-or-bundle-invalid",
            "restore-not-panel-backup",
            "restore-schema-missing",
            "restore-config-missing",
            "restore-config-invalid",
            "restore-legacy-too-large",
            "restore-companion-section-invalid",
            "restore-entity-object-invalid",
            "restore-profiles-object-invalid",
            "restore-state-object-invalid",
            "restore-archive-metadata-invalid",
            "restore-archive-entries-invalid",
            "restore-entity-state-invalid",
            "restore-entity-owner-missing",
            "restore-app-state-invalid",
            "restore-profile-archive-invalid",
            "restore-profile-catalog-invalid",
            "restore-profile-catalog-not-restorable",
            "restore-profile-restore-unavailable",
            "restore-companion-helper-required",
        )

        assertEquals(21, expected.size)
        expected.forEach { code ->
            assertTrue("missing restore refusal presentation $code", restore.contains("InstallPresentation(\"$code\")"))
        }
    }

    @Test fun nestedRestoreResultsAndTerminalProgressRetainTypedMetadata() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        val restore = source.substring(
            source.indexOf("private suspend fun handleRestore"),
            source.indexOf("private fun jarr"),
        )
        listOf(
            "component-not-present",
            "companion-unsupported-package",
            "companion-payload-invalid",
            "companion-helper-busy",
            "companion-marker-failed",
            "companion-urls-repaired",
            "companion-owner-restored",
            "companion-relaunch-unconfirmed",
            "companion-prior-files-retained",
            "companion-rollback-failed",
            "companion-helper-unavailable",
            "companion-rejected-before-commit",
            "companion-indeterminate",
        ).forEach { code ->
            assertTrue("missing nested restore presentation $code", restore.contains("\"$code\""))
        }
        listOf(
            "restore-preview-complete",
            "restore-request-rejected",
            "restore-completed",
            "restore-completed-with-state",
            "restore-partial",
            "restore-failed",
        ).forEach { code ->
            assertTrue("missing terminal restore presentation $code", restore.contains("\"$code\""))
        }
        assertTrue(restore.contains("result.presentation"))
        assertTrue(restore.contains("InstallProgress.finish("))
    }

    @Test fun installDocumentAccountsForEveryRuntimeNamespaceAndEnglishEvidence() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        val start = source.indexOf("get(\"/install\")")
        val route = source.substring(
            start,
            source.indexOf("get(\"/install/status\")", start),
        )
        listOf(
            "shell.",
            "configure.hardened.",
            "dashboard.banner.",
            "install.",
            "runtime.",
        ).forEach { prefix -> assertTrue("missing language namespace $prefix", route.contains("\"$prefix\"")) }
        assertTrue(route.contains("+ AppLocale.ENGLISH"))
    }

    @Test fun serviceCarriesProducerMetadataIntoTheServerWithoutEnglishInference() {
        val service = File("src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt").readText()
        assertTrue(service.contains("mdnsHealthWarning(health) to mdnsHealthPresentation(health)"))
        assertTrue(service.contains("CompanionDb.repairInternalUrlResult("))
        assertTrue(service.contains("InstallOperationResult(result.message, result.presentation)"))
        assertTrue(service.contains("prepared.presentation"))
        assertTrue(service.contains("presentation = it.presentation"))
    }
}
