package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.haLifecycleFromMqttStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second lifecycle source: Home Assistant's MQTT birth and will.
 *
 * It exists because Home Assistant refuses the WebSocket lifecycle subscription for a non-administrator
 * user, which is what every panel signs in as — so on real hardware the socket route reports nothing and
 * this one does the work.
 */
class HaLifecycleMqttSourceTest {
    private fun payload(text: String) = text.toByteArray(Charsets.UTF_8)

    // ---- payload mapping -------------------------------------------------------------------------

    @Test fun theWillMapsToAShutdownAndTheBirthToRecovery() {
        assertEquals(HaLifecycleEvent.STOP, haLifecycleFromMqttStatus(payload("offline"), retained = false))
        assertEquals(HaLifecycleEvent.STARTED, haLifecycleFromMqttStatus(payload("online"), retained = false))
    }

    @Test fun caseAndSurroundingWhitespaceDoNotChangeTheMeaning() {
        assertEquals(HaLifecycleEvent.STOP, haLifecycleFromMqttStatus(payload("  OFFLINE\n"), retained = false))
        assertEquals(HaLifecycleEvent.STARTED, haLifecycleFromMqttStatus(payload("Online "), retained = false))
    }

    @Test fun anUnrecognisedPayloadIsIgnoredRatherThanGuessed() {
        listOf("", "  ", "unknown", "offlin", "onlineish", "0", "1").forEach {
            assertNull("payload ${it.trim()} must not be interpreted", haLifecycleFromMqttStatus(payload(it), false))
        }
    }

    /**
     * The defect this guards is specific: a retained birth is replayed to EVERY new subscriber, so a panel
     * reconnecting to the broker would announce "Home Assistant is back online" every single time.
     */
    @Test fun aRetainedMessageIsHistoryAndIsNeverActedOn() {
        assertNull(haLifecycleFromMqttStatus(payload("online"), retained = true))
        assertNull(haLifecycleFromMqttStatus(payload("offline"), retained = true))
    }

    // ---- source authority ------------------------------------------------------------------------

    @Test fun aBrokerWillIsWordedAsGoneOfflineNotAsAShutdown() {
        val ha = HaLifecycle()
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 1_000)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(1_000))
        assertEquals(HaLifecycleSource.MQTT, ha.snapshot(1_000).source)

        val snap = ha.snapshot(1_000)
        val text = HaLifecycleMessage.text(snap.state, snap.source).orEmpty()
        // The will also fires when Home Assistant merely loses its broker link, so intent is unproven.
        assertTrue("MQTT wording must not claim a deliberate shutdown", text.contains("gone offline"))
        assertTrue(text.contains("Home Assistant"))
    }

    @Test fun theSocketWordingClaimsTheShutdownBecauseItProvesIntent() {
        val ha = HaLifecycle()
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.SOCKET, 1_000)
        val snap = ha.snapshot(1_000)
        val text = HaLifecycleMessage.text(snap.state, snap.source).orEmpty()
        assertTrue(text.contains("shutting down"))
        assertNotEquals(
            HaLifecycleMessage.text(HaLifecycleState.SHUTTING_DOWN, HaLifecycleSource.MQTT),
            HaLifecycleMessage.text(HaLifecycleState.SHUTTING_DOWN, HaLifecycleSource.SOCKET),
        )
    }

    @Test fun aLaterBrokerWillDoesNotDowngradeAShutdownTheSocketAlreadyProved() {
        val ha = HaLifecycle()
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.SOCKET, 1_000)
        // Both sources report the same outage; the broker's will arrives second because it waits on TCP.
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 1_500)
        assertEquals(
            "the authoritative source must win",
            HaLifecycleSource.SOCKET,
            ha.snapshot(1_500).source,
        )
        ha.snapshot(1_500).let { assertTrue(HaLifecycleMessage.text(it.state, it.source).orEmpty().contains("shutting down")) }
    }

    @Test fun aSocketShutdownArrivingAfterABrokerWillUpgradesTheWording() {
        val ha = HaLifecycle()
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 1_000)
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.SOCKET, 1_100)
        assertEquals(HaLifecycleSource.SOCKET, ha.snapshot(1_100).source)
    }

    // ---- the two sources compose -----------------------------------------------------------------

    @Test fun aBrokerRestartCycleReportsOutageThenRecovery() {
        val ha = HaLifecycle(backOnlineWindowMs = 8_000L)
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 1_000)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(1_000))
        ha.onEvent(HaLifecycleEvent.STARTED, HaLifecycleSource.MQTT, 40_000)
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(40_000))
        assertEquals(HaLifecycleState.NORMAL, ha.state(48_000))
    }

    @Test fun theMqttPathSkipsStartingBecauseTheBirthMeansAlreadyUp() {
        // Documented limitation: the broker carries no equivalent of homeassistant_start, so a panel on
        // the MQTT route goes straight from the outage to recovery with no "starting" stage.
        val ha = HaLifecycle()
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 1_000)
        ha.onEvent(HaLifecycleEvent.STARTED, HaLifecycleSource.MQTT, 2_000)
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(2_000))
    }

    // ---- the panel bar's own copy ----------------------------------------------------------------

    /**
     * The bar is read from across a room on a 480x480 panel at density 160. The full sentence cannot be
     * rendered four times larger there without overflowing, so the panel form is short by contract.
     */
    @Test fun thePanelFormIsShortEnoughToRenderLargeOnTheSmallestPanel() {
        listOf(
            HaLifecycleState.SHUTTING_DOWN to HaLifecycleSource.MQTT,
            HaLifecycleState.SHUTTING_DOWN to HaLifecycleSource.SOCKET,
            HaLifecycleState.STARTING to HaLifecycleSource.SOCKET,
            HaLifecycleState.BACK_ONLINE to HaLifecycleSource.SOCKET,
        ).forEach { (state, source) ->
            val panel = HaLifecycleMessage.panelText(state, source).orEmpty()
            assertTrue("$state must have panel copy", panel.isNotEmpty())
            // 4 lines at ~11 characters per line is what 48sp affords once the 96dp mark is placed.
            assertTrue("$state panel copy is too long to enlarge: '$panel'", panel.length <= 44)
            assertTrue("$state must name Home Assistant", panel.contains("Home Assistant"))
            assertFalse("$state must not blame ha-paneld", panel.contains("ha-paneld"))
            assertTrue(
                "the panel form must be shorter than the full sentence",
                panel.length < HaLifecycleMessage.text(state, source).orEmpty().length,
            )
        }
    }

    @Test fun thePanelFormStaysSilentWhenThereIsNothingToReport() {
        listOf(HaLifecycleState.NORMAL, HaLifecycleState.CONNECTION_LOST).forEach {
            assertNull("$it must render no bar", HaLifecycleMessage.panelText(it, HaLifecycleSource.SOCKET))
        }
    }

    @Test fun theSupportingLineSaysWhatItMeansAndThatNothingNeedDoing() {
        // The headline only helps someone who knows what Home Assistant is. This line is for the person
        // standing in front of the panel: the panel is not broken, and nobody has to act.
        val outage = HaLifecycleMessage.panelDetail(HaLifecycleState.SHUTTING_DOWN).orEmpty()
        assertTrue("it must say controls are unavailable", outage.contains("Controls unavailable"))
        assertTrue("and that recovery is automatic", outage.contains("automatically"))

        assertTrue(
            HaLifecycleMessage.panelDetail(HaLifecycleState.BACK_ONLINE).orEmpty().contains("returned"),
        )
        assertTrue(
            HaLifecycleMessage.panelDetail(HaLifecycleState.STARTING).orEmpty().contains("shortly"),
        )
    }

    @Test fun theSupportingLineIsSubordinateAndSilentWhenThereIsNothingToReport() {
        listOf(HaLifecycleState.NORMAL, HaLifecycleState.CONNECTION_LOST).forEach {
            assertNull("$it must render no supporting line", HaLifecycleMessage.panelDetail(it))
        }
        listOf(HaLifecycleState.SHUTTING_DOWN, HaLifecycleState.STARTING, HaLifecycleState.BACK_ONLINE)
            .forEach { state ->
                val detail = HaLifecycleMessage.panelDetail(state).orEmpty()
                // It explains; it does not re-announce. Repeating the headline's subject would waste the
                // one line the panel has for saying something new.
                assertFalse("$state supporting line must not repeat the headline", detail.contains("Home Assistant"))
                assertTrue("$state supporting line must be short", detail.length <= 60)
            }
    }

    @Test fun thePanelFormKeepsTheSourceDistinction() {
        assertTrue(
            HaLifecycleMessage.panelText(HaLifecycleState.SHUTTING_DOWN, HaLifecycleSource.MQTT)
                .orEmpty().contains("is offline"),
        )
        assertTrue(
            HaLifecycleMessage.panelText(HaLifecycleState.SHUTTING_DOWN, HaLifecycleSource.SOCKET)
                .orEmpty().contains("shutting down"),
        )
    }

    @Test fun duplicateWillsDoNotReannounce() {
        val ha = HaLifecycle()
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 1_000)
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 1_500)
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 2_000)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(2_000))
    }
}
