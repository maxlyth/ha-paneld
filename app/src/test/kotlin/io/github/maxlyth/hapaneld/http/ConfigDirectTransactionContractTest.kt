package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigDirectTransactionContractTest {
    private val source = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
    ).first(File::isFile).readText()

    @Test fun `direct config plans changes before persistence and dispatches only planned live values`() {
        val handler = source.substring(
            source.indexOf("private suspend fun handleConfigPost"),
            source.indexOf("private fun recordLiveApplyOutcome"),
        )
        assertTrue(handler.indexOf("planDirectConfigMutation(") < handler.indexOf("config.applyBatch"))
        assertTrue(handler.contains("for ((key, raw) in mutationPlan.changedLive)"))
        assertFalse(handler.contains("for (key in HTTP_LIVE_KEYS)"))
        assertTrue(handler.contains("mutationPlan.changedLive.firstOrNull { it.first == \"home_dashboard\" }"))
    }

    @Test fun `no-op and local live commits cannot trigger broad reconfigure`() {
        val handler = source.substring(
            source.indexOf("private suspend fun handleConfigPost"),
            source.indexOf("private fun recordLiveApplyOutcome"),
        )
        assertTrue(handler.contains("if (mutationPlan.isNoOp)"))
        assertTrue(handler.contains("if (reconfigureKeys.isNotEmpty()) onReconfigure(reconfigureKeys)"))
        assertFalse(handler.contains("snapInvalidate()\n        onReconfigure()"))
    }

    @Test fun `MQTT address family joins the bespoke atomic connection commit`() {
        val handler = source.substring(
            source.indexOf("private suspend fun handleConfigPost"),
            source.indexOf("private fun recordLiveApplyOutcome"),
        )

        assertTrue(handler.contains("broker != null || user != null || pw != null || mqttAddressFamily != null"))
        assertTrue(handler.contains("pw, mqttAddressFamily,"))
        assertTrue(source.contains("\\\"mqtt_address_family\\\":\${s(config.mqttAddressFamily)}"))
    }

    @Test fun `direct save owns operation lane across persistence dispatch and response`() {
        val handler = source.substring(
            source.indexOf("private suspend fun handleConfigPost"),
            source.indexOf("private fun recordLiveApplyOutcome"),
        )
        val admission = handler.indexOf("InstallProgress.startConfigMutation()")
        val persistence = handler.indexOf("config.applyBatch")
        val dispatch = handler.indexOf("for ((key, raw) in mutationPlan.changedLive)")
        val release = handler.indexOf("InstallProgress.finishConfigMutation(configMutationTicket)")

        assertTrue(admission in 0 until persistence)
        assertTrue(persistence < dispatch)
        assertTrue(dispatch < release)
        assertTrue(handler.contains("\"operation-busy\""))
        assertTrue(handler.contains("HttpStatusCode.Conflict"))
    }

    @Test fun `json and html responses share explicit applied pending rejected outcome`() {
        val responder = source.substring(
            source.indexOf("private suspend fun respondConfigMutation"),
            source.indexOf("private fun updateTameSelection"),
        )
        assertTrue(responder.contains("configJson("))
        assertTrue(responder.contains("ContentType.Application.Json"))
        assertTrue(responder.contains("ContentType.Text.Html"))
        assertTrue(source.contains("\\\"apply_pending\\\":{\$pendingDesired}"))
        assertTrue(source.contains("HttpStatusCode.Accepted"))
        assertTrue(source.contains("saved-partial"))
    }
}
