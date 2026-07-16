package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.backup.CompanionRestore
import io.github.maxlyth.hapaneld.backup.PanelBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CompanionBackupCheckpointTest {
    @Test fun `Companion UI and restore admission require the exact helper capability not direct su`() {
        val source = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        ).first { it.isFile }.readText()
        val install = source.substring(source.indexOf("private fun installBody"), source.indexOf("private fun installWarning"))
        val restore = source.substring(source.indexOf("private suspend fun handleRestore"), source.indexOf("private fun planCompanionArchive"))
        assertTrue(install.contains("val companionHelper = companionHelperCache.get()"))
        assertTrue(install.contains("backupCardHtml(companionHelper)"))
        assertFalse(install.contains("backupCardHtml(su)"))
        assertTrue(restore.contains("ensureCompanionHelper()"))
        assertFalse(restore.contains("Companion restore needs su"))
    }

    @Test fun `production capture checkpoints only helper-framed private-cache bytes`() {
        val source = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        ).first { it.isFile }.readText()
        val start = source.indexOf("private fun captureCompanion")
        val end = source.indexOf("private suspend fun handleRestore", start)
        val capture = source.substring(start, end)
        val helper = capture.indexOf("HelperClient.backupCompanion")
        val validation = capture.indexOf("checkpointCapturedDatabase")
        val refusal = capture.indexOf(
            "throw CompanionBackupUnavailable(\"Companion login database could not be checkpointed safely\")",
        )
        val gate = capture.indexOf("CompanionDataOperationGate.acquire")
        val relaunchFinally = capture.indexOf("finally {", validation)
        assertTrue(gate >= 0)
        assertTrue(helper > gate)
        assertTrue(validation > helper)
        assertTrue(validation >= 0)
        assertTrue(refusal > validation)
        assertTrue(relaunchFinally > refusal)
        assertTrue(capture.indexOf("system.launchHome(pkg)", relaunchFinally) > relaunchFinally)
        assertFalse(capture.contains("sqlite3"))
        assertFalse(capture.contains("Su."))
        assertFalse(capture.contains("protectedPathCheck"))
        assertFalse(capture.contains("runToFileBounded"))
    }

    @Test fun `legacy object materialization stays far below streamed archive payload ceiling`() {
        assertTrue(PaneldServer.MAX_BACKUP_MANIFEST_BYTES < PaneldServer.MAX_RESTORE_BYTES)
        assertTrue(PaneldServer.MAX_LEGACY_BACKUP_JSON_BYTES < PaneldServer.MAX_RESTORE_BYTES / 2L)
        assertTrue(PaneldServer.MAX_BACKUP_MANIFEST_BYTES <= 1L * 1024L * 1024L)
        assertTrue(PaneldServer.MAX_PROFILE_BACKUP_ENTRY_BYTES >= 2L * 4L * 1024L * 1024L)
        assertEquals(13_000_000L, PaneldServer.MAX_ENTITY_BACKUP_TEXT_BYTES)
    }

    @Test fun `self-generated backup ceilings are symmetric with restore admission`() {
        assertEquals(CompanionRestore.MAX_AGGREGATE_BYTES, PaneldServer.MAX_COMPANION_BACKUP_BYTES)
        assertEquals(
            PaneldServer.MAX_RESTORE_BYTES - PanelBackup.SEALED_OVERHEAD_BYTES,
            PanelBackup.maxSealablePlaintextBytes(PaneldServer.MAX_RESTORE_BYTES),
        )
    }

    @Test fun `backup storage admission covers sources plaintext ciphertext and raw Companion frames`() {
        val configPlain = backupStagingRequirement(includeCompanion = false, encrypted = false)
        val configEncrypted = backupStagingRequirement(includeCompanion = false, encrypted = true)
        val companionEncrypted = backupStagingRequirement(includeCompanion = true, encrypted = true)
        assertTrue(configPlain > PaneldServer.BACKUP_STORAGE_MARGIN_BYTES + PaneldServer.MAX_RESTORE_BYTES)
        assertEquals(PaneldServer.MAX_RESTORE_BYTES, configEncrypted - configPlain)
        assertTrue(companionEncrypted > configEncrypted)
        assertTrue(
            companionEncrypted >=
                PaneldServer.BACKUP_STORAGE_MARGIN_BYTES +
                io.github.maxlyth.hapaneld.util.CompanionHelperProtocol.MAX_BACKUP_STREAM_BYTES,
        )
    }

    @Test fun `large profile and owner entity payloads are separate archive entries`() {
        val source = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        ).first { it.isFile }.readText()
        val manifest = source.substring(
            source.indexOf("private fun backupManifest("),
            source.indexOf("private fun captureCompanion"),
        )
        assertTrue(manifest.contains("entityBackupArchiveJson"))
        assertTrue(manifest.contains("PROFILE_BACKUP_ENTRY"))
        assertTrue(manifest.contains("ENTITY_STATE_CONFIG_KEYS"))
        assertFalse(manifest.contains(".append(it.toJson())"))
        assertTrue(source.contains("ENTITY_FILTER_BACKUP_ENTRY = \"entity/filter-ids.txt\""))
        assertTrue(source.contains("ENTITY_OVERRIDES_BACKUP_ENTRY = \"entity/overrides.txt\""))
        assertTrue(source.contains("PROFILE_BACKUP_ENTRY = \"profiles/catalog.json\""))
    }
}
