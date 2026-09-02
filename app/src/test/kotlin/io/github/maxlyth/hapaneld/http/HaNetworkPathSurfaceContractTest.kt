package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.sensors.HaNetworkPathPresentation
import io.github.maxlyth.hapaneld.sensors.HaNetworkPathSeverity
import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every network-path surface consumes ONE state owner, `HaNetworkPathRuntime`, and nothing else
 * reads the sample ring. Source-coupled on purpose: a surface that grows its own read is exactly how
 * two surfaces come to disagree about the same moment. The behavioural half (one injected snapshot,
 * every projection) is `HaNetworkPathRuntimeTest`.
 */
class HaNetworkPathSurfaceContractTest {
    private val server by lazy { TestSources.kotlin("http/PaneldServer.kt").readText() }
    private val perf by lazy { TestSources.kotlin("http/PerfReader.kt").readText() }
    private val service by lazy { TestSources.kotlin("PaneldService.kt").readText() }
    private val activity by lazy { TestSources.kotlin("DashboardActivity.kt").readText() }
    private val chip by lazy { TestSources.kotlin("HaNetworkChip.kt").readText() }
    private val owner by lazy { TestSources.kotlin("sensors/HaExactEntityStreamOwner.kt").readText() }
    private val diag by lazy { TestSources.kotlin("http/DiagReader.kt").readText() }
    private val buildwatch by lazy { TestSources.asset("buildwatch.js").readText() }
    private val info by lazy { TestSources.asset("info.js").readText() }

    @Test fun theServiceIsTheOnlyInstallerAndTheOwnerIsTheOnlyFeed() {
        assertEquals(1, Regex("HaNetworkPathRuntime\\.install\\(").findAll(service).count())
        assertTrue(service.contains("haExactEntityStream.bindNetworkPath(haNetworkPath)"))
        // Install precedes bind: the first demand announcement must already be readable.
        assertTrue(service.indexOf("HaNetworkPathRuntime.install(haNetworkPath)") < service.indexOf("bindNetworkPath(haNetworkPath)"))
        // Teardown is identity-gated and re-pokes the renderer only when it actually cleared.
        assertTrue(service.contains("HaNetworkPathRuntime.uninstall(haNetworkPath)"))
        // The socket owner and its transport share one clock, or the round trip is two clocks apart.
        assertEquals(2, Regex("monotonicMillis = haSocketClock").findAll(service).count())
    }

    @Test fun everySurfaceReadsTheHolderAndNothingReadsTheRing() {
        // Each surface: exactly one holder read, none of the monitor's internals.
        assertTrue(server.contains("HaNetworkPathRuntime.healthToken()"))
        assertTrue(server.contains("HaNetworkPathRuntime.statusText() ?: \"\""))
        assertTrue(server.contains("\\\"ha_network\\\":${'$'}{HaNetworkPathRuntime.statusJson()}"))
        assertTrue(server.contains("haNetwork = HaNetworkPathRuntime.diagnosticLine()"))
        assertTrue(perf.contains("HaNetworkPathRuntime.snapshot()"))
        assertTrue(activity.contains("networkChip?.update(io.github.maxlyth.hapaneld.sensors.HaNetworkPathRuntime.snapshot())"))
        listOf("server" to server, "perf" to perf, "activity" to activity, "chip" to chip, "diag" to diag).forEach { (name, text) ->
            assertFalse("$name must not touch the monitor directly", text.contains("HaNetworkPathMonitor"))
            assertFalse("$name must not construct its own path state", text.contains("HaNetworkPath("))
        }
        // Measurement ownership: only the stream owner may say the socket is live, and it does so
        // from the authenticated onLive seam rather than from a demand change.
        assertTrue(owner.contains("reportSocketState(HaSocketState.LIVE)"))
        assertTrue(owner.contains("reportSocketState(HaSocketState.STOPPED)"))
        listOf(server, perf, activity, chip, diag, service).forEach { text ->
            assertFalse("only the stream owner reports socket state", text.contains("onSocketState("))
        }
        // The state owner's feed is the socket owner alone: no other production file reports events.
        val reporters = listOf(server, perf, service, activity, chip, diag).count { it.contains(".onRoundTrip(") || it.contains(".onProbeTimeout(") }
        assertEquals(0, reporters)
        assertTrue(owner.contains("observer.onRoundTrip("))
        assertTrue(owner.contains("observer.onProbeTimeout()"))
    }

    @Test fun theHealthTokensRideBothHealthRespondersBesideTheLifecycleToken() {
        assertEquals(2, Regex("\\$\\{haLifecycleHealthToken\\(\\)}\\$\\{haNetworkHealthToken\\(\\)}").findAll(server).count())
    }

    @Test fun thePageShellCarriesABannerAndARowTheScriptWritesToFromOneObservation() {
        assertTrue(server.contains("id=\"hanetbar\""))
        assertTrue(server.contains("id=\\\"hanetcell\\\""))
        assertTrue(buildwatch.contains("getElementById(\"hanetbar\")"))
        assertTrue(buildwatch.contains("getElementById(\"hanetcell\")"))
        // Rendered from the same /health fetch as the lifecycle pair, inside the same handler.
        val handler = buildwatch.substringAfter("function vc()").substringBefore("var mb = t.match")
        assertTrue(handler.contains("ha_net=(\\S+)"))
        assertTrue(handler.contains("haNetBanner("))
        assertTrue(handler.contains("haBanner("))
    }

    @Test fun theBannerReusesTheSevereWarningPresentationAndNeverInjectsMarkup() {
        val banner = buildwatch.substringAfter("function haNetBanner").substringBefore("function vc()")
        assertTrue("severe uses the existing crit tone", banner.contains("state === \"severe\" ? \"setup crit\" : \"setup\""))
        assertTrue(banner.contains("b.textContent = text"))
        assertFalse("the banner must not use innerHTML", banner.contains("innerHTML"))
        // Absent token: banner hidden, row emptied; no default verdict is invented.
        assertTrue(banner.contains("if (!text) { b.style.display = \"none\"; }"))
        assertTrue(banner.contains("if (!state) { row.textContent = \"\"; return; }"))
        // Responsiveness is a clause in the same row and can never raise the banner: the banner text
        // table is keyed on the PATH state alone and has no responsiveness entry.
        assertTrue(banner.contains("var clause = HA_RESP_CLAUSE[resp]"))
        assertFalse("latency must not reach the banner", banner.contains("HA_NET_TEXT[resp]"))
    }

    @Test fun theScriptAndTheKotlinPresentationShareOneCopy() {
        assertTrue(buildwatch.contains("warning: \"${HaNetworkPathPresentation.BANNER_WARNING_PREFIX}\""))
        assertTrue(buildwatch.contains("severe: \"${HaNetworkPathPresentation.BANNER_SEVERE_PREFIX}\""))
        assertTrue(buildwatch.contains("\"${HaNetworkPathPresentation.BANNER_ADVICE}\""))
        HaNetworkPathSeverity.entries.forEach {
            assertTrue("the row must word ${it.wireValue}", buildwatch.contains("${it.wireValue}: \""))
        }
    }

    @Test fun theDiagnosticsCardRowSitsBetweenWifiStabilityAndTheRenderer() {
        val contextKeys = server.substringAfter("private val CONTEXT_KEYS").substringBefore("private val BEHAVIOUR_FACT_KEYS")
        assertTrue(contextKeys.contains("HA_NETWORK_FACT"))
        assertTrue(contextKeys.indexOf("\"Wi-Fi stability\"") < contextKeys.indexOf("HA_NETWORK_FACT"))
        assertTrue(contextKeys.indexOf("HA_NETWORK_FACT") < contextKeys.indexOf("HA_RENDERER_FACT"))
        assertTrue(server.contains("private val HA_NETWORK_FACT = \"HA network path\""))
    }

    @Test fun thereIsNoServerRenderedNetworkBannerToGoStale() {
        val banners = server.substringAfter("private fun bannersHtml(").substringBefore("\n    }\n")
        assertFalse(banners.contains("HaNetworkPath"))
        assertFalse(server.contains(HaNetworkPathPresentation.BANNER_WARNING_PREFIX))
        assertFalse(server.contains(HaNetworkPathPresentation.BANNER_SEVERE_PREFIX))
    }

    @Test fun theDiagLineIsAppendedRightAfterTheRendererLine() {
        val rendererAt = diag.indexOf("renderer?.let { appendLine(it.diagnosticLine()) }")
        val networkAt = diag.indexOf("haNetwork?.let { appendLine(it) }")
        assertTrue(rendererAt in 0 until networkAt)
    }

    @Test fun theLikelyCauseRanksTheMeasuredPathFirstAndTheScriptLabelsIt() {
        val telemetry = TestSources.kotlin("dashboard/DashboardTelemetry.kt").readText()
        val classify = telemetry.substringAfter("private fun classify(").substringBefore("private fun histogramPercentile")
        assertTrue(classify.indexOf("NETWORK_PATH_CAUSE") < classify.indexOf("\"state_stream\""))
        assertTrue(info.contains("${HaNetworkPathPresentation.LIKELY_CAUSE}:i18nText('dashboard.cause.ha_network_path','Network path to Home Assistant')"))
        assertTrue(perf.contains("takeIf { it.degraded }"))
    }

    @Test fun theNativeChipIsAttachedDetachedAndRedrawnWithTheLifecycleBar() {
        assertTrue(activity.contains("networkChip = HaNetworkChip.attach(this, container)"))
        val detach = activity.substringAfter("private fun detachLifecycleBar()").substringBefore("\n    }\n")
        assertTrue(detach.contains("networkChip?.detach()"))
        assertTrue(detach.contains("networkChip = null"))
        // The chip must never take a touch from the dashboard beneath it, and must have no dismiss.
        assertTrue(chip.contains("isClickable = false"))
        assertTrue(chip.contains("isFocusable = false"))
        assertFalse(chip.contains("setOnClickListener"))
        assertTrue(chip.contains("view.text = HaNetworkPathPresentation.PANEL_TEXT"))
        assertFalse("no drawable resource is added for the glyph", chip.contains("R.drawable"))
    }

    /** The script's behaviour, driven through the real asset by node: hide, warn, escalate, retract. */
    @Test fun theBannerAndRowBehaveAsSpecifiedForEveryToken() {
        val working = java.io.File(requireNotNull(System.getProperty("user.dir")))
        val fixture = listOf(
            java.io.File(working, "app/src/test/js/ha-network-banner-test.mjs"),
            java.io.File(working, "src/test/js/ha-network-banner-test.mjs"),
        ).first(java.io.File::isFile)
        val asset = listOf(
            java.io.File(working, "app/src/main/assets/buildwatch.js"),
            java.io.File(working, "src/main/assets/buildwatch.js"),
        ).first(java.io.File::isFile)
        val process = ProcessBuilder("node", fixture.absolutePath, asset.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
        assertTrue(output, output.contains("ha network banner cases passed"))
    }

    @Test fun openApiDocumentsTheTokensAndTheStatusObject() {
        val document = JSONObject(TestSources.asset("openapi.json").readText())
        val health = document.getJSONObject("paths").getJSONObject("/api/v1/health")
            .getJSONObject("get").getJSONObject("responses").getJSONObject("200").getString("description")
        listOf("ha_net=", "ha_resp=", "ha_net_p95=", "ha_net_n=", "ha_net_miss=", "ha_net_age=").forEach {
            assertTrue("OpenAPI must name the $it token", health.contains(it))
        }
        HaNetworkPathSeverity.entries.forEach { assertTrue(health.contains(it.wireValue)) }
        assertTrue(health.contains("absent"))
        val status = document.getJSONObject("paths").getJSONObject("/api/v1/status")
            .getJSONObject("get").getJSONObject("responses").getJSONObject("200")
        val schema = status.getJSONObject("content").getJSONObject("application/json").getJSONObject("schema")
        assertTrue(schema.getJSONObject("properties").has("ha_network"))
        assertTrue(schema.getJSONArray("required").toString().contains("\"ha_network\""))
        val component = document.getJSONObject("components").getJSONObject("schemas").getJSONObject("HaNetworkPath")
        listOf(
            "measuring", "state", "responsiveness", "settling", "socket", "p95_ms", "loss_percent",
            "consecutive_failures", "server_failures", "last_round_trip_age_ms",
        ).forEach {
            assertTrue("OpenAPI must document the $it property", component.getJSONObject("properties").has(it))
        }
        val states = component.getJSONObject("properties").getJSONObject("state").getJSONArray("enum").toString()
        listOf("idle", "settling", "healthy", "warning", "severe").forEach { assertTrue(states.contains("\"$it\"")) }
    }
}
