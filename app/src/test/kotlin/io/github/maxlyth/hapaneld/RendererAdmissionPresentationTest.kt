package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.util.HaTransportEvidence
import io.github.maxlyth.hapaneld.util.HaTransportFault
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The projection is the contract three surfaces share, so it is tested behaviourally rather than by
 * reading the source that renders it: what a soak, a pasted dump and the info card each see for every
 * state the panel can actually be in.
 */
class RendererAdmissionPresentationTest {

    private val url = "https://home-assistant.example.invalid:8123"

    private fun record(
        state: RendererAdmissionState,
        outcome: AdmissionOutcome? = null,
        cached: Boolean = false,
        evidence: HaTransportEvidence = HaTransportEvidence.NONE,
        at: Long = 1_000L,
    ) = RendererAdmissionRuntime.Record(state, outcome, cached, evidence, at)

    private fun present(
        record: RendererAdmissionRuntime.Record?,
        connected: Boolean = false,
        mode: RendererMode = RendererMode.BUILTIN,
        haUrl: String = url,
        family: String = "Automatic",
        now: Long = 61_000L,
    ) = RendererAdmissionPresentation.of(
        mode = mode,
        haUrl = haUrl,
        addressFamilyPolicy = family,
        live = RendererAdmissionRuntime.Live(owner = 7L, record = record, frontendConnected = connected),
        nowElapsedMs = now,
    )

    private fun json(p: RendererAdmissionPresentation) = JSONObject(p.statusJson())

    // --- the healthy cases -------------------------------------------------------------------

    @Test fun aLiveVersionCheckWithAConnectedFrontendIsRendered() {
        val p = present(record(RendererAdmissionState.ADMITTED), connected = true)
        assertEquals(RendererAdmissionState.RENDERED, p.state)
        assertEquals(RendererAdmissionPresentation.OUTCOME_OK, p.outcome)
        assertEquals(HaTransportFault.NONE, p.fault)
        assertNull(p.faultDetail)
        assertEquals("", p.action)
        val j = json(p)
        assertEquals("rendered", j.getString("state"))
        assertTrue(j.getBoolean("rendered"))
        assertEquals(60_000L, j.getLong("observed_age_ms"))
    }

    @Test fun admittedOnACachedVersionIsNotRenderedUntilTheFrontendConnects() {
        // The admitted-and-blank shape: the version check could not complete, the previously verified
        // version admitted the renderer, and the page then hit the same wall. Every other check on
        // the panel reads green, because none of them asks whether a dashboard actually appeared.
        val cached = record(
            RendererAdmissionState.ADMITTED,
            cached = true,
            evidence = HaTransportEvidence(HaTransportFault.TLS_TRUST, "CertPathValidatorException"),
        )
        val blank = present(cached, connected = false)
        assertEquals(RendererAdmissionState.ADMITTED, blank.state)
        assertEquals(RendererAdmissionPresentation.OUTCOME_OK_CACHED, blank.outcome)
        assertEquals(HaTransportFault.TLS_TRUST, blank.fault)
        assertFalse(json(blank).getBoolean("rendered"))
        assertTrue(blank.summary.contains("previously verified"))

        // The same verdict WITH a live frontend is genuinely up, and still says how it was admitted.
        val up = present(cached, connected = true)
        assertEquals(RendererAdmissionState.RENDERED, up.state)
        assertEquals(RendererAdmissionPresentation.OUTCOME_OK_CACHED, up.outcome)
        assertTrue(up.summary.contains("previously verified"))
    }

    @Test fun aBlockedVerdictIsNeverUpgradedByAStaleConnection() {
        // A blocked screen tears the page down without ending the renderer generation, so a
        // connection flag that had not yet been cleared must not resurrect "rendered".
        val p = present(
            record(RendererAdmissionState.BLOCKED, AdmissionOutcome.BRIDGE_HANDSHAKE_MISSED),
            connected = true,
        )
        assertEquals(RendererAdmissionState.BLOCKED, p.state)
        assertFalse(json(p).getBoolean("rendered"))
    }

    // --- the blocked matrix ------------------------------------------------------------------

    @Test fun aTransportFailureNamesTheClassifiedFaultAndRetriesOnItsOwn() {
        val p = present(
            record(
                RendererAdmissionState.BLOCKED,
                AdmissionOutcome.TRANSPORT_FAILED,
                evidence = HaTransportEvidence(HaTransportFault.TLS_TRUST, "CertPathValidatorException"),
            ),
        )
        assertEquals(RendererAdmissionState.BLOCKED, p.state)
        assertEquals("transport_failed", p.outcome)
        assertEquals(HaTransportFault.TLS_TRUST, p.fault)
        assertEquals("CertPathValidatorException", p.faultDetail)
        assertEquals("from_base", p.recovery)
        assertTrue(p.action.contains("recovers unattended"))
        assertTrue(p.summary.contains("tls_trust"))
    }

    @Test fun anAbsentCredentialIsBlockedAndSaysItNeedsAPerson() {
        val p = present(record(RendererAdmissionState.BLOCKED, AdmissionOutcome.SIGN_IN_REQUIRED))
        assertEquals("sign_in_required", p.outcome)
        assertEquals("manual_only", p.recovery)
        // No transport failure was involved, so no fault is invented and none is appended to the copy.
        assertEquals(HaTransportFault.NONE, p.fault)
        assertFalse(p.summary.contains("("))
        assertTrue(p.action.contains("will not clear on its own"))
    }

    @Test fun aRefusedCredentialIsBlockedButKeepsAskingTheServer() {
        val p = present(record(RendererAdmissionState.BLOCKED, AdmissionOutcome.CREDENTIAL_REFUSED))
        assertEquals("credential_refused", p.outcome)
        // NOT manual_only: a re-enabled user or reissued token is a server-side repair with no event
        // to tell the panel, so a latched screen would outlive the fault it reports.
        assertEquals("at_ceiling", p.recovery)
        assertEquals(HaTransportFault.NONE, p.fault)
        assertFalse(p.action.contains("will not clear on its own"))
    }

    @Test fun anUnsupportedServerIsBlockedAndTerminalUntilSomeoneUpgradesIt() {
        val p = present(record(RendererAdmissionState.BLOCKED, AdmissionOutcome.UNSUPPORTED_HA))
        assertEquals("unsupported_ha", p.outcome)
        assertEquals("manual_only", p.recovery)
        assertTrue(p.summary.contains("older than the supported floor"))
    }

    @Test fun everyAdmissionOutcomeProducesItsOwnBlockedSummaryAndAKnownRecovery() {
        // Enumerated from the enum rather than listed by hand: a new outcome that nobody described
        // would otherwise ship with whatever copy the fallback happened to give it.
        val summaries = AdmissionOutcome.entries.associateWith { outcome ->
            present(record(RendererAdmissionState.BLOCKED, outcome))
        }
        summaries.forEach { (outcome, p) ->
            assertEquals(outcome.name.lowercase(), p.outcome)
            assertTrue("$outcome has no recovery class", p.recovery in setOf("from_base", "at_ceiling", "manual_only"))
            assertTrue("$outcome blocked copy is empty", p.summary.startsWith("blocked — "))
            assertTrue("$outcome has no action", p.action.isNotBlank())
        }
        assertEquals(
            "two outcomes share one summary, so a report cannot tell them apart",
            AdmissionOutcome.entries.size,
            summaries.values.map { it.summary }.toSet().size,
        )
    }

    // --- the uninformative cases -------------------------------------------------------------

    @Test fun nothingObservedIsNeverAStatementOfHealth() {
        val p = present(record = null)
        assertEquals(RendererAdmissionState.UNOBSERVED, p.state)
        assertEquals(RendererAdmissionPresentation.OUTCOME_UNOBSERVED, p.outcome)
        assertNull(p.observedAgeMs)
        assertFalse(json(p).getBoolean("rendered"))
        assertTrue(json(p).isNull("observed_age_ms"))
        assertTrue(p.diagnosticLine().contains("observed_age=never"))
        assertNotEquals("rendered", p.state.wire)
    }

    @Test fun aCheckInFlightIsNotAnOutcome() {
        val p = present(record(RendererAdmissionState.CHECKING))
        assertEquals(RendererAdmissionState.CHECKING, p.state)
        assertEquals(RendererAdmissionPresentation.OUTCOME_UNOBSERVED, p.outcome)
        assertEquals("none", p.recovery)
    }

    @Test fun anExternalRendererSaysItIsNotObservedRatherThanOmittingTheObject() {
        val p = present(record = null, mode = RendererMode.EXTERNAL)
        assertEquals("external", json(p).getString("mode"))
        assertEquals("unobserved", json(p).getString("state"))
        assertTrue(p.statusText()!!.contains("not observed by ha-paneld"))
        // The battery caught this test passing while an external renderer was routed through the
        // built-in branch: `statusText` switches on the mode by itself, so the row read correctly
        // while the machine-readable summary and action silently described a built-in panel. The
        // JSON body is what a fleet check reads, so it is what must be asserted.
        assertTrue(json(p).getString("summary").contains("external renderer is configured"))
        assertTrue(json(p).getString("action").contains("cannot verify a foreign renderer"))
        assertEquals("none", json(p).getString("recovery"))
        // A consumer must be able to tell "not applicable" from "applicable and silent" without
        // inferring anything from an absent field.
        assertNotEquals(present(record = null).statusText(), p.statusText())
        assertNotEquals(present(record = null).summary, p.summary)
    }

    @Test fun noConfiguredRendererEarnsNoInfoRowButStillReportsItsMode() {
        val p = present(record = null, mode = RendererMode.NONE)
        assertEquals("none", json(p).getString("mode"))
        assertNull(p.statusText())
    }

    // --- redaction ---------------------------------------------------------------------------

    @Test fun noSurfaceCarriesTheHostTheUrlOrRawExceptionText() {
        val p = present(
            record(
                RendererAdmissionState.BLOCKED,
                AdmissionOutcome.TRANSPORT_FAILED,
                evidence = HaTransportEvidence(HaTransportFault.DNS, "UnknownHostException"),
            ),
        )
        listOf(p.statusJson(), p.diagnosticLine(), p.statusText().orEmpty()).forEach { rendered ->
            assertFalse(rendered, rendered.contains("example.invalid"))
            assertFalse(rendered, rendered.contains("8123"))
            assertFalse(rendered, rendered.contains(url))
        }
        // The scheme alone survives, because "is this panel talking TLS at all?" is the first
        // question a certificate failure raises and a scheme identifies nobody.
        assertEquals("https", p.transport)
        assertEquals("https", json(p).getString("transport"))
    }

    @Test fun anUnparseableEndpointReportsAnUnknownTransportRatherThanGuessing() {
        assertEquals("unknown", present(record = null, haUrl = "").transport)
        assertEquals("unknown", present(record = null, haUrl = "hass.example.net").transport)
        assertEquals("http", present(record = null, haUrl = "HTTP://hass.example.net").transport)
    }

    @Test fun theAddressFamilyPolicyIsNormalisedToAWireToken() {
        assertEquals("prefer_ipv4", present(record = null, family = "Prefer IPv4").addressFamilyPolicy)
        assertEquals("force_ipv4", present(record = null, family = "Force IPv4").addressFamilyPolicy)
        assertEquals("automatic", present(record = null, family = "  ").addressFamilyPolicy)
    }

    // --- shape -------------------------------------------------------------------------------

    @Test fun theStatusObjectCarriesEveryFieldASoakReads() {
        val j = json(present(record(RendererAdmissionState.ADMITTED), connected = true))
        listOf(
            "mode", "state", "outcome", "fault", "fault_detail", "recovery", "transport",
            "address_family_policy", "observed_age_ms", "rendered", "summary", "action",
        ).forEach { assertTrue("missing $it", j.has(it)) }
    }

    @Test fun theDiagnosticLineIsOneLineAndSelfLabelling() {
        val line = present(record(RendererAdmissionState.BLOCKED, AdmissionOutcome.NO_LEGAL_DASHBOARD)).diagnosticLine()
        assertTrue(line.startsWith("[renderer] "))
        assertFalse(line.contains("\n"))
        assertTrue(line.contains("outcome=no_legal_dashboard"))
        assertTrue(line.contains("recovery=at_ceiling"))
        assertTrue(line.contains("detail=none"))
    }

    @Test fun theInfoRowStatesHowOldTheObservationIs() {
        val text = present(record(RendererAdmissionState.ADMITTED, at = 1_000L), connected = true, now = 601_000L)
            .statusText()!!
        assertTrue(text.startsWith("built-in · "))
        assertTrue(text.contains("seen 10m ago"))
    }

    @Test fun aClockThatWentBackwardsReportsZeroRatherThanANegativeAge() {
        val p = present(record(RendererAdmissionState.ADMITTED, at = 5_000L), now = 1_000L)
        assertEquals(0L, p.observedAgeMs)
    }

    @Test fun ageFormattingCoversEachMagnitude() {
        assertEquals("23s", RendererAdmissionPresentation.fmtAge(23_000L))
        assertEquals("47m", RendererAdmissionPresentation.fmtAge(47 * 60_000L))
        assertEquals("5h12m", RendererAdmissionPresentation.fmtAge((5 * 3600 + 12 * 60) * 1000L))
        assertEquals("3d2h", RendererAdmissionPresentation.fmtAge((3 * 86400 + 2 * 3600) * 1000L))
    }
}
