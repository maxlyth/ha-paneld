package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreAndImportContractTest {
    private val source by lazy {
        listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        ).first { it.isFile }.readText()
    }

    @Test fun restoreClaimsAdmissionBeforeReadingAndReleasesNonTransferredRequests() {
        val handler = source.substring(
            source.indexOf("private suspend fun handleRestore"),
            source.indexOf("private fun planCompanionRestore"),
        )
        assertTrue(handler.indexOf("InstallProgress.start") < handler.indexOf("DeadlineBoundedBody.copy"))
        assertTrue("early returns must release admission", "finally" in handler)
        assertTrue("only a launched restore may retain admission", "if (!transferredToJob)" in handler)
        assertTrue("busy admission must be an HTTP conflict", "HttpStatusCode.Conflict" in handler)
        assertTrue("body receipt needs a whole-request deadline", "DeadlineBoundedBody.copy" in handler)
        assertTrue("body timeout must be structured", "\"bundle-timeout\"" in handler)
        assertTrue("body timeout must be HTTP 408", "HttpStatusCode.RequestTimeout" in handler)
    }

    @Test fun completedRestoreSeparatesConfigCompanionAndRollbackResults() {
        val handler = source.substring(
            source.indexOf("private suspend fun handleRestore"),
            source.indexOf("private fun planCompanionRestore"),
        )
        assertTrue("config result missing", "config =" in handler)
        assertTrue("Companion result missing", "companion =" in handler)
        assertTrue("rollback result missing", "rollback = rollback" in handler)
        assertTrue("component failure must trigger config rollback", "applyAccepted(before, expectedRevision" in handler)
        assertTrue("official backups must carry config", "backup contains no config object" in handler)
        assertTrue("restore must migrate from the declared schema", "planRestoreConfig(cfgObj, backupSchema)" in handler)
        assertTrue("invalid config must be rejected before launch", "invalid backup config" in handler)
    }

    @Test fun configImportDryRunHashCanRejectAStaleApply() {
        val handler = source.substring(
            source.indexOf("private suspend fun handleConfigImport"),
            source.indexOf("private suspend fun applyAccepted"),
        )
        assertTrue("dry run must expose a baseline hash", "ConfigHash.of(current)" in handler)
        val apply = source.substring(
            source.indexOf("private suspend fun applyAccepted"),
            source.indexOf("private fun applyRendererEffects"),
        )
        assertTrue("apply must compare expected_cfg", "ConfigHash.of(currentValues()) != expectedConfig" in apply)
        assertTrue(
            "expected_cfg comparison must be inside the serialized renderer/config transaction",
            apply.indexOf("rendererPreparation.transaction") < apply.indexOf("ConfigHash.of(currentValues())"),
        )
        assertTrue("stale preview must be a conflict", "\"status\":\"stale-preview\"" in handler)
        assertTrue("stale preview must be HTTP 409", "HttpStatusCode.Conflict" in handler)
    }

    @Test fun restoreRollbackFenceMatchesOnlyTheGenerationDurableBeforeRendererPreparation() {
        val apply = source.substring(
            source.indexOf("private suspend fun applyAccepted"),
            source.indexOf("private fun applyRendererEffects"),
        )
        val live = apply.indexOf("for ((k, v) in phase.live)")
        val callback = apply.indexOf("afterCommitBeforeRenderer(")
        assertTrue("all live values must be durable before external restore work starts", live in 0 until callback)
        assertTrue(
            "rollback fence must hash the actual durable revision at the callback boundary",
            "ConfigHash.of(revisionValues())" in apply,
        )
        assertTrue(
            "rollback ownership must advance with each durable live generation",
            "onDurableRevision(io.github.maxlyth.hapaneld.config.ConfigHash.of(revisionValues()))" in apply,
        )
        val liveLoop = apply.substring(
            apply.indexOf("for ((k, v) in phase.live)"),
            apply.indexOf("val homeChanged"),
        )
        assertTrue(
            "a persisted live value must advance rollback ownership before actuation failure is raised",
            liveLoop.indexOf("onDurableRevision") < liveLoop.indexOf("check(applied)"),
        )
        assertTrue(
            "pending live values must not be projected into a not-yet-durable rollback generation",
            "putAll(accepted)" !in apply,
        )
    }
}
