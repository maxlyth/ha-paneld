package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /api/v1/setup` is polled every couple of seconds while a step is in flight, which makes its cost
 * a correctness property rather than an optimisation.
 *
 * It is deliberately not part of `/api/v1/status`: that endpoint's inputs are root- and daemon-backed
 * stale-while-revalidate reads, so polling it would keep kicking off privileged refreshes for data a
 * wizard never looks at. Nor may it run discovery — a multicast browse takes seconds and would turn a
 * poll into a network sweep. Everything it reports comes from memory.
 */
class SetupStateEndpointContractTest {
    private val server = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
    ).first { it.isFile }.readText()

    private fun body(name: String, until: String): String =
        server.substring(server.indexOf(name), server.indexOf(until))

    @Test fun theStateBuilderTouchesNoExpensiveSource() {
        val builder = body("private fun setupJourneyInputs()", "private data class HaAreaSnapshot(")
        listOf(
            "snapStaleOk(",
            "companionServersStaleOk(",
            "UpdateChecker",
            "configDiscoverySuggestions(",
            "Dispatchers.IO",
        ).forEach {
            assertFalse("$it makes the setup poll expensive", builder.contains(it))
        }
    }

    @Test fun theResponseIsNeverCached() {
        // Staleness here is dangerous rather than merely untidy: a cached response would leave the wizard
        // showing a step the panel has already moved past, or hide one it is stuck on.
        val route = body("""get("/setup")""", "haOAuthRoutes(")
        assertTrue(route.contains("no-store"))
        assertFalse("an ETag would let a proxy or the browser serve a stale journey", route.contains("ETag"))
    }

    @Test fun genericSetupReadersCannotClaimWizardPresence() {
        val route = body("""get("/setup")""", "haOAuthRoutes(")
        assertTrue(route.contains("call.request.headers[SETUP_PRESENCE_HEADER] == SETUP_PRESENCE_ACTIVE"))
        assertTrue(route.contains("GuidedSetupPresence.noteHeartbeat"))
        assertFalse(route.contains("GuidedSetupPresence.notePoll"))
    }

    @Test fun theRouteIsDocumented() {
        // ApiRouteSpecContractTest enforces this too; asserting it here names the reason on failure.
        val openapi = listOf(
            File("src/main/assets/openapi.json"),
            File("app/src/main/assets/openapi.json"),
        ).first { it.isFile }.readText()
        assertTrue(openapi.contains("\"/api/v1/setup\""))
    }

    @Test fun theMqttStateArrivesAsACanonicalTokenNotProse() {
        // Round-tripping through the info page's prose would reintroduce the two-vocabulary drift the
        // adapter exists to remove, and it fails silently: an unrecognised string reads as "connecting".
        val builder = body("private fun setupJourneyInputs()", "private fun setupProofFingerprint()")
        assertTrue(builder.contains("MqttSetupState.of(mqttState())"))
        assertFalse(builder.contains("""facts["MQTT"]"""))
    }

    @Test fun anAlreadyConfiguredPanelIsNotSentBackToConfirmItsName() {
        // The confirmation flag defaults false, so an install that predates it would otherwise be told to
        // confirm a name its owner chose long ago — and a working panel would be dragged back to step one.
        // Caught on the first canary: an established panel reported `next: identity` while its real gap
        // was a missing Home Assistant login.
        val builder = body("private fun setupJourneyInputs()", "private fun panelConfiguredBeforeSetupTracking()")
        assertTrue(builder.contains("config.setupIdentityConfirmed || panelConfiguredBeforeSetupTracking()"))
        val inference = body("private fun panelConfiguredBeforeSetupTracking()", "private fun setupProofFingerprint()")
        // Durable configuration is the evidence, and a genuinely fresh install has none of it.
        listOf("mqttBroker", "haUrl", "dashboardPackage").forEach {
            assertTrue("$it should count as evidence of prior setup", inference.contains(it))
        }
    }

    @Test fun theUpgradeInferenceDiesTheMomentGuidedSetupBegins() {
        // The wizard itself writes a broker at step 2, so inferring "upgraded install" from durable config
        // alone force-satisfied the later question stages MID-JOURNEY: on the first fresh-panel hardware
        // walk the journey reported complete the instant the HA token committed, the sign-in callback sent
        // the browser to Configure, the dashboard/filter pages never showed, and the panel deadlocked on
        // its hold screen while this authority claimed nothing was needed. Identity confirmation is the
        // begin-marker: once recorded, the later-stage escapes must be off.
        val builder = body("private fun setupJourneyInputs()", "private fun panelConfiguredBeforeSetupTracking()")
        assertTrue(builder.contains("val preTracking = !config.setupIdentityConfirmed && panelConfiguredBeforeSetupTracking()"))
        assertTrue(builder.contains("entityFilterAnswered = config.setupEntityFilterAnswered || preTracking"))
        assertTrue(builder.contains("homeDashboardChosen = config.setupHomeDashboardChosen || preTracking"))
        // The raw inference may remain ONLY on the identity input — that is the upgrade case it exists for.
        assertEquals(
            "the raw escape must not leak onto any later stage",
            2, // the identity input + the preTracking definition itself
            Regex("panelConfiguredBeforeSetupTracking\\(\\)").findAll(builder).count(),
        )
    }

    @Test fun theProofFingerprintCarriesNoSecretMaterial() {
        // This runs on the request path of an unauthenticated LAN endpoint. stableOwner() would be the
        // natural identity but it holds the refresh and access tokens, and materialising those as text
        // here risks them reaching a log or heap dump for no benefit — only the hash is ever emitted.
        val fingerprint = body("private fun setupProofFingerprint()", "private fun setupJourneyJson()")
        listOf("stableOwner", "haToken)", "refreshToken", "config.haToken,", "config.haRefreshToken,").forEach {
            assertFalse("$it puts secret material in the fingerprint", fingerprint.contains(it))
        }
        assertTrue(fingerprint.contains("ha_client_id"))
    }
}
