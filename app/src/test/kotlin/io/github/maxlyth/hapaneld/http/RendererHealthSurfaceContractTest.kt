package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.AdmissionOutcome
import io.github.maxlyth.hapaneld.RendererAdmissionState
import io.github.maxlyth.hapaneld.testsupport.TestSources
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Binds the renderer projection to the surfaces that must carry it. The projection's BEHAVIOUR is
 * proven in `RendererAdmissionPresentationTest`; what cannot be proven there without an Activity and
 * a WebView is that the three shipped surfaces actually read it, and that no path publishes the raw
 * failure text the panel shows on its own screen.
 *
 * Source-coupled deliberately, in the same shape as the storage and lifecycle surface contracts:
 * these are the exact seams that drift silently, and a diagnostic that quietly stopped being emitted
 * is indistinguishable from a healthy panel — which is the entire failure this work removes.
 */
class RendererHealthSurfaceContractTest {
    private val server by lazy { TestSources.kotlin("http/PaneldServer.kt").readText() }
    private val diag by lazy { TestSources.kotlin("http/DiagReader.kt").readText() }
    private val activity by lazy { TestSources.kotlin("DashboardActivity.kt").readText() }
    private val runtime by lazy { TestSources.kotlin("RendererAdmission.kt").readText() }

    @Test fun theStatusEndpointEmitsTheRendererObjectUnconditionally() {
        assertTrue(server.contains("\\\"renderer\\\":\${rendererAdmission().statusJson()}"))
        // Unconditional means unconditional: no `?.let`, no null branch, no omission for an external
        // renderer. A consumer that has to infer applicability from an absent field is one bad
        // inference away from calling a blank fleet healthy.
        assertFalse(server.contains("rendererAdmission()?."))
    }

    @Test fun theDumpCarriesTheRendererLineAndTheProjectionIsBuiltOnce() {
        assertTrue(diag.contains("renderer?.let { appendLine(it.diagnosticLine()) }"))
        assertEquals(
            "the dump must take the projection as a parameter rather than reaching for the runtime itself",
            1,
            Regex("renderer: io\\.github\\.maxlyth\\.hapaneld\\.RendererAdmissionPresentation\\?").findAll(diag).count(),
        )
        assertFalse("the dump must not read the runtime directly", diag.contains("RendererAdmissionRuntime"))
        assertTrue(server.contains("renderer = rendererAdmission(),"))
    }

    @Test fun theInfoCardRowIsRenderedLiveRatherThanFromTheFactsCache() {
        // The facts snapshot is stale-while-revalidate. A dashboard that went down a minute ago would
        // keep saying "rendered" for a TTL if this row came from there — the reassuring-but-wrong
        // answer the row exists to stop giving.
        assertTrue(server.contains("HA_RENDERER_FACT -> rendererAdmission().statusText()"))
        assertTrue(
            server.replace(Regex("\\s+"), " ").contains(
                "\"Wi-Fi stability\", HA_RENDERER_FACT, \"MQTT state\"",
            ),
        )
        // It is a live row, so it must never also be a cached panel fact: two sources for one value
        // is how the two start disagreeing on the same page.
        assertFalse(diag.contains("\"HA renderer\""))
    }

    @Test fun theRawFailureTextNeverReachesTheRuntime() {
        // `DashboardV2ProbeResult.Unavailable.detail` is the platform's own message and can embed the
        // configured host; it belongs on the panel's screen and nowhere else. The runtime takes
        // classified evidence only, and this asserts the choke point passes exactly that.
        assertTrue(activity.contains("blocked.evidence,"))
        assertFalse(
            "a blocked recording must never forward the raw detail",
            Regex("RendererAdmissionRuntime\\.record\\((?:[^)]|\\)(?!\\s*\\n))*detail").containsMatchIn(activity),
        )
        assertFalse(runtime.contains("val detail"))
    }

    @Test fun everyBlockedScreenPublishesThroughTheOneChokePoint() {
        // Derived from the source rather than listed by hand: a future blocked screen added beside
        // these would otherwise reach the panel without any surface learning about it.
        val blockedCalls = Regex("showBlockedAdmissionScreen\\(").findAll(activity).count()
        assertTrue("expected the blocked-screen helper to have real call sites", blockedCalls > 8)
        assertEquals(
            "showBlockedAdmissionScreen must be the only site recording a BLOCKED verdict",
            1,
            Regex("state = RendererAdmissionState\\.BLOCKED").findAll(activity).count(),
        )
        assertEquals(
            "showAdmissionProgressScreen must be the only site recording CHECKING",
            1,
            Regex("state = RendererAdmissionState\\.CHECKING").findAll(activity).count(),
        )
        assertEquals(
            "the compatible branch must be the only site recording ADMITTED",
            1,
            Regex("state = RendererAdmissionState\\.ADMITTED").findAll(activity).count(),
        )
    }

    @Test fun theConnectionFlagPublishesFromItsSetterNotFromCountedCallSites() {
        // Five sites assign `frontendConnected`. A setter covers all of them and any future one; a
        // hand-maintained list beside each assignment is one edit away from a surface that claims a
        // dashboard is up after it went down.
        assertTrue(
            activity.replace(Regex("\\s+"), " ").contains(
                "private var frontendConnected = false set(value) { field = value " +
                    "RendererAdmissionRuntime.setFrontendConnected(activityOwner, value) }",
            ),
        )
        assertEquals(
            1,
            Regex("RendererAdmissionRuntime\\.setFrontendConnected\\(").findAll(activity).count(),
        )
        assertTrue("the field must still be assigned from the real transitions", assignmentsOfFrontendConnected() >= 4)
    }

    private fun assignmentsOfFrontendConnected(): Int =
        Regex("(?<!private var )frontendConnected = (true|false)").findAll(activity).count()

    @Test fun openApiDescribesTheRendererContractItActuallyEmits() {
        val api = JSONObject(File("src/main/assets/openapi.json").readText())
        val schema = api.getJSONObject("components").getJSONObject("schemas").getJSONObject("RendererHealth")
        val properties = schema.getJSONObject("properties")

        val outcomes = properties.getJSONObject("outcome").getJSONArray("enum")
            .let { array -> (0 until array.length()).map { array.getString(it) } }
        // Every outcome the panel can publish must be documented, derived from the enum rather than
        // transcribed — a new admission outcome would otherwise ship undocumented.
        AdmissionOutcome.entries.forEach {
            assertTrue("${it.name.lowercase()} is missing from the OpenAPI outcome enum", it.name.lowercase() in outcomes)
        }
        assertTrue("ok" in outcomes && "ok_cached" in outcomes && "unobserved" in outcomes)

        val states = properties.getJSONObject("state").getJSONArray("enum")
            .let { array -> (0 until array.length()).map { array.getString(it) } }
        RendererAdmissionState.entries.forEach {
            assertTrue("${it.wire} is missing from the OpenAPI state enum", it.wire in states)
        }

        val status = api.getJSONObject("paths").getJSONObject("/api/v1/status").getJSONObject("get")
        assertTrue(
            status.getJSONObject("responses").getJSONObject("200")
                .getJSONObject("content").getJSONObject("application/json")
                .getJSONObject("schema").getJSONArray("required").toString().contains("renderer"),
        )
        assertTrue(api.getJSONObject("paths").getJSONObject("/api/v1/diag").toString().contains("renderer"))
    }
}
