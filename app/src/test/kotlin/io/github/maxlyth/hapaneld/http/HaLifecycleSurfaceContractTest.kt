package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.sensors.HaLifecycle
import io.github.maxlyth.hapaneld.sensors.HaLifecycleMessage
import io.github.maxlyth.hapaneld.sensors.HaLifecycleSource
import io.github.maxlyth.hapaneld.sensors.HaLifecycleState
import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Binds the lifecycle surfaces to what is actually shipped: the `/health` token, its OpenAPI
 * description, and the page-shell/JS pair that renders it. These are source-coupled on purpose — the
 * three drift apart silently otherwise, and a stale banner is exactly the failure this feature is
 * meant to remove.
 */
class HaLifecycleSurfaceContractTest {
    private val server by lazy { TestSources.kotlin("http/PaneldServer.kt").readText() }
    private val buildwatch by lazy { TestSources.asset("buildwatch.js").readText() }

    private fun snapshot(state: HaLifecycleState, source: HaLifecycleSource?) =
        HaLifecycle.Snapshot(state, source, refused = false, revision = 1L, backOnlineRemainingMs = 0L)

    @Test fun theHealthTokenIsEmittedFromBothHealthResponders() {
        assertEquals(
            "both /health and /api/v1/health must carry the token, or a page's banner depends on which it polled",
            2,
            // Interpolation sites only — the declaration must not be counted as a responder.
            Regex("\\$\\{haLifecycleHealthToken\\(\\)}").findAll(server).count(),
        )
    }

    @Test fun theHealthTokenIsAbsentWhenThePanelIsNotWatchingOrUnowned() {
        // The token must not degrade to a default value: an absent token means "nothing to say", which is
        // what keeps the /health line byte-identical for every existing consumer.
        assertEquals("", haLifecycleHealthToken(false, snapshot(HaLifecycleState.SHUTTING_DOWN, HaLifecycleSource.SOCKET)))
        assertEquals("", haLifecycleHealthToken(true, null))
    }

    // ---- the pure token: one snapshot in, truthful tokens out --------------------------------------

    @Test fun theTokenPairsTheStateWithTheSourceFromTheSameSnapshot() {
        assertEquals(
            " ha=shutting_down ha_src=mqtt",
            haLifecycleHealthToken(true, snapshot(HaLifecycleState.SHUTTING_DOWN, HaLifecycleSource.MQTT)),
        )
        assertEquals(
            " ha=back_online ha_src=socket",
            haLifecycleHealthToken(true, snapshot(HaLifecycleState.BACK_ONLINE, HaLifecycleSource.SOCKET)),
        )
    }

    @Test fun statesNobodyObservedCarryNoSourceToken() {
        // The initial normal and a locally noticed connection loss are the panel's own inferences;
        // naming a source for them would claim an observation nobody made.
        assertEquals(" ha=normal", haLifecycleHealthToken(true, snapshot(HaLifecycleState.NORMAL, null)))
        assertEquals(
            " ha=connection_lost",
            haLifecycleHealthToken(true, snapshot(HaLifecycleState.CONNECTION_LOST, null)),
        )
    }

    /**
     * The responders must feed the pure token ONE atomic snapshot. Reading state and source through
     * separate runtime calls can straddle a transition — or an ownership change — and serve a
     * combination that never existed.
     */
    @Test fun theRespondersRenderFromOneAtomicSnapshotRead() {
        assertTrue(
            server.contains("haLifecycleHealthToken(HaLifecycleRuntime.watching, HaLifecycleRuntime.snapshot())"),
        )
    }

    /**
     * Every lifecycle surface converges on ONE observation — the ten-second `/health` poll. A
     * server-rendered advisory banner used to sit beside the live bar: during an outage both showed,
     * and on recovery only the live one could retract, leaving a stale warning until the page was
     * reloaded. It was deleted rather than synchronised, because a one-shot render cannot retract.
     */
    @Test fun thereIsNoSecondOneShotLifecycleSurfaceToGoStale() {
        assertFalse("the server must not render its own lifecycle banner", server.contains("haLifecycleBanner"))
        val reportable = HaLifecycleState.entries.filter {
            it != HaLifecycleState.NORMAL && it != HaLifecycleState.CONNECTION_LOST
        }
        reportable.forEach { state ->
            HaLifecycleMessage.text(state, HaLifecycleSource.SOCKET)?.let { copy ->
                assertFalse(
                    "${state.wireValue} advisory copy must not be server-rendered anywhere",
                    server.contains(copy),
                )
            }
        }
    }

    @Test fun theDiagnosticsRowIsRefreshedByTheSameObservationAsTheBanner() {
        assertTrue("the row needs an identity the script can reach", server.contains("id=\\\"halifecell\\\""))
        assertTrue(buildwatch.contains("getElementById(\"halifecell\")"))
        assertTrue(
            "one poll drives both surfaces",
            Regex("haBanner\\(mh").containsMatchIn(buildwatch) &&
                buildwatch.substringAfter("function haBanner").contains("halifebar") &&
                buildwatch.substringAfter("function haBanner").contains("halifecell"),
        )
    }

    /** The refusal explains the idle row, so it rides the same observation instead of a second read. */
    @Test fun theRefusalRidesTheSameObservation() {
        assertEquals(
            " ha=normal ha_refused=1",
            haLifecycleHealthToken(true, snapshot(HaLifecycleState.NORMAL, null).copy(refused = true)),
        )
        assertTrue(buildwatch.contains("ha_refused=1"))
    }

    @Test fun openApiDocumentsEveryLifecycleStateTheTokenCanCarry() {
        val document = JSONObject(TestSources.asset("openapi.json").readText())
        val description = document
            .getJSONObject("paths").getJSONObject("/api/v1/health")
            .getJSONObject("get").getJSONObject("responses").getJSONObject("200")
            .getString("description")
        HaLifecycleState.entries.forEach { state ->
            assertTrue(
                "OpenAPI must name the ${state.wireValue} state the token can carry",
                description.contains(state.wireValue),
            )
        }
        assertTrue("and must say the token can be absent", description.contains("absent"))
    }

    @Test fun thePageShellCarriesTheBannerElementTheScriptWritesTo() {
        assertTrue("the shell must render the element", server.contains("id=\"halifebar\""))
        assertTrue("the script must target it", buildwatch.contains("getElementById(\"halifebar\")"))
    }

    @Test fun theScriptRendersOnlyTheThreeReportableStates() {
        val reportable = HaLifecycleState.entries.filter {
            it != HaLifecycleState.NORMAL && it != HaLifecycleState.CONNECTION_LOST
        }
        reportable.forEach {
            assertTrue("the script must render ${it.wireValue}", buildwatch.contains("${it.wireValue}:"))
        }
        // A plain connection loss keeps the EXISTING generic recovery path; giving it Home Assistant
        // wording here is the mislabelling the feature exists to prevent.
        assertFalse(
            "connection_lost must not get Home Assistant shutdown wording",
            buildwatch.contains("connection_lost:"),
        )
    }

    @Test fun theScriptTreatsAnAbsentTokenAsNothingToSay() {
        assertTrue(buildwatch.contains("haBanner(mh ? mh[1] : \"\", ms ? ms[1] : \"\", !!mr)"))
        // Absent token -> nothing to say on EITHER surface: the bar hides and the row empties, rather
        // than either falling back to a default state.
        assertTrue(buildwatch.contains("if (!text) { b.style.display = \"none\"; }"))
        assertTrue(buildwatch.contains("if (!state) { row.textContent = \"\"; return; }"))
    }

    @Test fun theBannerUsesTextContentSoAServerStateCannotInjectMarkup() {
        assertTrue("the lifecycle banner must not use innerHTML", buildwatch.contains("b.textContent = text"))
    }

    @Test fun theHealthLineCarriesTheSourceAlongsideTheState() {
        assertTrue("the token must name its source", server.contains("ha_src="))
        assertTrue("and the script must read it", buildwatch.contains("ha_src=(\\S+)"))
    }

    @Test fun onlyTheSocketMayClaimADeliberateShutdownAndTheWeakerClaimIsTheDefault() {
        // Only the socket proves intent, so the stronger wording must require ha_src=socket BY NAME;
        // an MQTT source or an absent one falls through to the claim the panel can defend.
        assertTrue(buildwatch.contains("src === \"socket\""))
        val defaultBlock = buildwatch.substringAfter("var HA_TEXT = {").substringBefore("};")
        assertTrue("the default shutdown wording is the weaker claim", defaultBlock.contains("gone offline"))
        assertFalse("the default must not claim intent", defaultBlock.contains("is shutting down"))
        val socketBlock = buildwatch.substringAfter("var HA_TEXT_SOCKET").substringBefore("};")
        assertTrue(socketBlock.contains("is shutting down"))
        listOf("starting", "back_online").forEach {
            assertFalse("$it must not be duplicated per source", socketBlock.contains("$it:"))
        }
    }

    @Test fun theDiagnosticsCardHasARowForTheLifecycleState() {
        assertTrue(server.contains("\"Log shipping\", HA_LIFECYCLE_FACT,"))
    }

    /**
     * The row must be read live, never from the panel-facts cache. During a real Home Assistant restart
     * the cached row still described the idle watch while `/health` on the same screen already reported
     * the outage, so the page contradicted itself for as long as the snapshot was stale.
     */
    /**
     * The row is rendered even with nothing to say — an empty cell the poll can fill. Omitting it left
     * a panel that begins watching AFTER the render (the watch waits for the renderer to settle) with
     * no element to populate, so the row could only ever go from present to absent, never the reverse.
     */
    @Test fun theLifecycleRowIsAlwaysPresentSoItCanAppearWithoutAReload() {
        assertTrue(server.contains("(HaLifecycleRuntime.statusText() ?: \"\")"))
    }

    @Test fun theLifecycleRowIsReadLiveRatherThanFromTheStaleFactsCache() {
        assertTrue(
            "the row must call the runtime directly",
            server.contains("if (key == HA_LIFECYCLE_FACT) (HaLifecycleRuntime.statusText() ?: \"\") else s.facts[key]"),
        )
        val service = TestSources.kotlin("PaneldService.kt").readText()
        assertFalse(
            "and must not be written into the cached panel facts, or the stale copy returns",
            service.contains("extras[\"HA lifecycle\"]"),
        )
    }
}
