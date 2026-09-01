package io.github.maxlyth.hapaneld.control

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source contract for the Android-owned controller boundary, matching the repo's Context-free tests. */
class AutoBrightnessHistoryPartitionContractTest {
    private val controller = source("control/AutoBrightnessController.kt")
    private val historyRuntime = source("control/AmbientHistoryRuntime.kt")
    private val store = source("dashboard/EntityCatalogStore.kt")
    private val mqtt = source("MqttBridge.kt")
    private val service = source("PaneldService.kt")
    private val server = source("http/PaneldServer.kt")

    @Test fun `reapply synchronously fences a changed history partition`() {
        val reapply = controller.substringAfter("@Synchronized fun reapplyLatest()")
            .substringBefore("@Synchronized fun status()")
        val reconcile = controller.substringAfter("private fun reconcileHistorySource()")
            .substringBefore("/** Re-hash only")
        assertTrue(reapply.contains("reconcileHistorySource()"))
        assertTrue(reapply.indexOf("reconcileHistorySource()") < reapply.indexOf("if (!config.autoBrightness)"))
        assertTrue(reapply.indexOf("reconcileHistorySource()") < reapply.indexOf("requestEvaluationLocked"))
        assertTrue(reconcile.contains("activeSourceKey != history.currentSourceId()"))
        assertTrue(reconcile.contains("locationContext != history.currentContextId()"))
        assertTrue(reconcile.contains("configureHistorySource()"))
    }

    @Test fun `one opaque site and source revision fences status and history`() {
        assertTrue(controller.contains("hash(\"\$locationContext|\$activeSourceKey\")"))
        assertTrue(controller.contains("sourceRevision = activeSourceRevision()"))
        assertTrue(service.contains("put(\"sourceRevision\", runtime.sourceRevision)"))
        assertTrue(service.contains("put(\"sourceRevision\", snapshot.sourceRevision)"))
        assertTrue(service.contains("put(\"latestEpochMinute\", points.lastOrNull()?.epochMinute ?: JSONObject.NULL)"))
    }

    @Test fun `adaptive status and history prohibit response caching`() {
        val statusRoute = server.substringAfter("get(\"/auto-brightness\")")
            .substringBefore("get(\"/auto-sleep\")")
        val historyRoute = server.substringAfter("get(\"/auto-brightness/history\")")
            .substringBefore("get(\"/auto-brightness/sources\")")
        assertTrue(statusRoute.contains("headers.append(\"Cache-Control\", \"no-store\")"))
        assertTrue(historyRoute.contains("headers.append(\"Cache-Control\", \"no-store\")"))
    }

    @Test fun `source identity cannot change during active-first retention`() {
        val flush = historyRuntime.substringAfter("private fun flush(reloadAfter: Boolean)")
            .substringBefore("fun closeAndJoin")
        val seed = historyRuntime.substringAfter("fun seed(")
            .substringBefore("fun reload()")
        assertTrue(flush.contains("synchronized(pendingLock)"))
        assertTrue(flush.indexOf("synchronized(pendingLock)") < flush.indexOf("store.recordAmbientHistory"))
        assertTrue(seed.contains("synchronized(pendingLock)"))
        assertTrue(seed.indexOf("synchronized(pendingLock)") < seed.indexOf("store.seedAmbientHistory"))
    }

    @Test fun `retention reserves seven days for panel and selected HA evidence`() {
        assertTrue(store.contains("AMBIENT_GLOBAL_ROW_LIMIT = 24_000"))
        assertTrue(store.contains("PANEL_AMBIENT_SOURCE_ID = \"panel\""))
        assertTrue(store.contains("WHEN context_id=? AND source_id=? THEN 0"))
        assertTrue(store.contains("WHEN source_id=? THEN 1"))
        assertTrue(store.contains("arrayOf(activeContextId, activeSourceId, PANEL_AMBIENT_SOURCE_ID)"))
    }

    @Test fun `sensitivity reapplies policy without restarting the ambient source`() {
        val handler = mqtt.substringAfter("override fun handleAutoBrightnessSensitivity")
            .substringBefore("override fun handleAutoBrightnessMinimum")

        assertTrue(handler.contains("autoBright.reapplyLatest()"))
        assertFalse(handler.contains("onAutoBrightnessConfigChanged()"))
        assertTrue(service.contains("refreshAdaptiveBrightnessInputs(restartSource = adaptiveSourceRestartRequired)"))
        assertTrue(service.contains("replacementRequired || desired.haLink != appliedNetworkConfiguration.haLink"))
    }

    private fun source(relative: String): String {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            File(working, "app/src/main/kotlin/io/github/maxlyth/hapaneld/$relative"),
            File(working, "src/main/kotlin/io/github/maxlyth/hapaneld/$relative"),
        ).first(File::isFile).readText()
    }
}
