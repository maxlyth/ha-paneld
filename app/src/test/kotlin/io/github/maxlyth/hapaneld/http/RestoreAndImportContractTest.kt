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

    /**
     * The backup must carry the whole `app_state` table, not a projection of declared settings, so a
     * namespace added in a later release is captured without anyone remembering to extend the backup.
     */
    @Test fun theBackupCarriesTheCompleteStateTableAndSurvivesAnUnreadableDatabase() {
        val parts = source.substring(
            source.indexOf("private fun backupArchiveParts"),
            source.indexOf("private fun backupManifest"),
        )
        assertTrue("the whole table must be exported", "exportAppState()" in parts)
        assertTrue("the vault codec must be reused", "ConfigVault.encode" in parts)
        assertTrue("the entry must be declared", "STATE_BACKUP_ENTRY" in parts)
        assertTrue("an unreadable database must not lose the rest of the backup", "runCatching" in parts)
        assertTrue("no rows means no entry rather than an empty one", "takeIf { it.isNotEmpty() }" in parts)
    }

    /**
     * Restoring state must be bounded, validated, and never able to undo the configuration the owner came
     * for: it runs after the durable commit and its failure is swallowed rather than rolling config back.
     */
    @Test fun stateRestoreIsValidatedBoundedAndAppliedAfterTheDurableConfigCommit() {
        val handler = source.substring(
            source.indexOf("private suspend fun handleRestore"),
            source.indexOf("private fun planCompanionRestore"),
        )
        assertTrue("a corrupt payload must be rejected", "invalid app_state payload" in handler)
        assertTrue("the payload must be size-bounded", "MAX_STATE_BACKUP_BYTES" in handler)
        assertTrue("the digest-verifying decoder must be used", "ConfigVault.decode" in handler)
        assertTrue("the policy decides what may be written", "StateBackupPolicy.restorableRows" in handler)
        assertTrue("device-local rows need panel identity", "config.panelId" in handler)

        val afterApply = handler.substring(handler.indexOf("afterApply = afterApply@{"))
        val apply = afterApply.indexOf("AppState.applyRestoredRows")
        val profileEarlyReturn = afterApply.indexOf("profilePayload?.payload ?: return@afterApply")
        assertTrue("state must be applied inside afterApply", apply >= 0)
        assertTrue(
            "state must be applied before the profile early-return, or a backup without profiles " +
                "would silently skip its state",
            apply < profileEarlyReturn,
        )
        assertTrue(
            "a state failure must not roll back committed configuration",
            "runCatching {\n                                    AppState.applyRestoredRows" in afterApply,
        )
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
        assertTrue(
            "profile restart rejection must record whether the staged activation rollback persisted",
            "rejectFailedProfileRestart(" in handler,
        )
        assertTrue(
            "a latent pending activation must surface as a rollback failure",
            "profileRestartFailureComponent" in handler && "profileRestartRejection" in handler,
        )
    }

    @Test fun configImportDryRunHashCanRejectAStaleApply() {
        val handler = source.substring(
            source.indexOf("private suspend fun handleConfigImport"),
            source.indexOf("private suspend fun applyAccepted"),
        )
        assertTrue("dry run must expose a secret-free baseline hash", "configConcurrencyHash(current)" in handler)
        val apply = source.substring(
            source.indexOf("private suspend fun applyAccepted"),
            source.indexOf("private fun applyRendererEffects"),
        )
        assertTrue("apply must compare expected_cfg", "configConcurrencyHash(currentValues()) != expectedConfig" in apply)
        assertTrue(
            "expected_cfg comparison must be inside the serialized renderer/config transaction",
            apply.indexOf("rendererPreparation.transaction") < apply.indexOf("configConcurrencyHash(currentValues())"),
        )
        assertTrue("stale preview must be a conflict", "\"status\":\"stale-preview\"" in handler)
        assertTrue("stale preview must be HTTP 409", "HttpStatusCode.Conflict" in handler)
    }

    @Test fun configImportApprovalBindsTheOriginalBodyAndCompleteQueryMultimap() {
        val handler = source.substring(
            source.indexOf("private suspend fun handleConfigImport"),
            source.indexOf("private suspend fun applyAccepted"),
        )
        assertTrue("approval must hash the original request bytes", "sha256Hex(bodyBytes)" in handler)
        assertTrue(
            "approval must use the HTTP binding that includes the complete query multimap",
            "exactHttpApprovalPayload(call, importDigest)" in handler,
        )
    }

    @Test fun builtInRendererEffectsUseTheResolvedAutoRenderer() {
        val apply = source.substring(
            source.indexOf("private fun applyRendererEffects"),
            source.indexOf("private fun requireRendererResult"),
        )
        assertTrue("built-in reload must handle auto resolving to built-in", "effects.reloadBuiltin && effectiveDashboardIsBuiltin()" in apply)
        assertTrue("built-in relaunch must handle auto resolving to built-in", "effects.relaunchBuiltin && effectiveDashboardIsBuiltin()" in apply)
        assertTrue("literal package gate would skip fresh auto panels", "config.dashboardPackage == SystemController.BUILTIN_DASHBOARD" !in apply)
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

    /**
     * Upgrade, restore and import must read a historical value the same way. They did not: a home
     * dashboard stored as a whole address before the setting had a validator was converted on upgrade
     * and on restore, and silently dropped on import — found by importing a real v0.9.6 export onto a
     * panel running the candidate, where both a same-origin and a foreign address were refused alike.
     */
    @Test fun importReadsAHistoricalValueTheSameWayRestoreDoes() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        val handler = source.substring(
            source.indexOf("private suspend fun handleConfigImport"),
            source.indexOf("would skip (invalid)"),
        )
        assertTrue(
            "import must normalize a historical value through the shared rule, not validate it verbatim",
            "restorableSettingValue(key, raw, canonicalHaOrigin(config.haUrl))" in handler,
        )
    }
}
