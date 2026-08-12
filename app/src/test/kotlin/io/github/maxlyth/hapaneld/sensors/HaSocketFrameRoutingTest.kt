package io.github.maxlyth.hapaneld.sensors

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame classification for the shared Home Assistant socket.
 *
 * These assertions live at the decision itself rather than behind the stream's fake transport: a fake
 * that emits an already-classified message proves nothing about how a real frame is classified, which
 * is how the non-fatal-rejection contract came to be claimed without being covered.
 */
class HaSocketFrameRoutingTest {
    private val lifecycleIds = mapOf(
        11 to HaLifecycleEvent.STOP,
        12 to HaLifecycleEvent.FINAL_WRITE,
        13 to HaLifecycleEvent.CLOSE,
        14 to HaLifecycleEvent.START,
        15 to HaLifecycleEvent.STARTED,
    )
    private val registryIds = setOf(8, 9, 10)

    private fun result(id: Int, success: Boolean, message: String? = null) = JSONObject()
        .put("id", id)
        .put("type", "result")
        .put("success", success)
        .also { json -> message?.let { json.put("error", JSONObject().put("message", it)) } }

    // ---- the contract that protects the shared stream --------------------------------------------

    @Test fun aRefusedLifecycleSubscriptionIsNotFatal() {
        lifecycleIds.keys.forEach { id ->
            assertEquals(
                "id $id is a lifecycle subscription; refusing it must not tear down the stream",
                HaResultOutcome.LifecycleRejected,
                haResultOutcome(result(id, success = false, message = "unauthorized"), lifecycleIds.keys, 240),
            )
        }
    }

    @Test fun aRefusedEntitySubscriptionRemainsFatal() {
        val outcome = haResultOutcome(result(1, success = false, message = "boom"), lifecycleIds.keys, 240)
        assertTrue(outcome is HaResultOutcome.Fatal)
        assertEquals("boom", (outcome as HaResultOutcome.Fatal).message)
    }

    @Test fun aRefusedRegistrySubscriptionRemainsFatal() {
        registryIds.forEach { id ->
            assertTrue(
                "id $id is not a lifecycle subscription and must stay fatal",
                haResultOutcome(result(id, success = false, message = "nope"), lifecycleIds.keys, 240)
                    is HaResultOutcome.Fatal,
            )
        }
    }

    @Test fun withNoLifecycleSubscriptionEveryFailureIsFatal() {
        // A panel that never demanded lifecycle events must not gain a new way to swallow a real error.
        assertTrue(
            haResultOutcome(result(11, success = false, message = "x"), emptySet(), 240) is HaResultOutcome.Fatal,
        )
    }

    @Test fun anySuccessfulResultIsIgnoredWhicheverSubscriptionItAnswers() {
        // Acceptance is not tracked: the panel reports what it OBSERVES rather than which route it
        // believes is covered, so a success frame carries no information it needs.
        assertEquals(HaResultOutcome.Ignored, haResultOutcome(result(1, true), lifecycleIds.keys, 240))
        assertEquals(HaResultOutcome.Ignored, haResultOutcome(result(11, true), lifecycleIds.keys, 240))
    }

    @Test fun aFatalResultWithoutAnErrorMessageStillCarriesAReason() {
        val outcome = haResultOutcome(result(1, success = false), lifecycleIds.keys, 240)
        assertEquals(
            HaResultOutcome.Fatal("Home Assistant rejected the entity subscription"),
            outcome,
        )
    }

    @Test fun aBlankErrorMessageFallsBackRatherThanReportingNothing() {
        val outcome = haResultOutcome(result(1, success = false, message = "   "), lifecycleIds.keys, 240)
        assertEquals(HaResultOutcome.Fatal("Home Assistant rejected the entity subscription"), outcome)
    }

    @Test fun anOversizedErrorMessageIsTruncated() {
        val outcome = haResultOutcome(result(1, success = false, message = "x".repeat(500)), lifecycleIds.keys, 240)
        assertEquals(240, (outcome as HaResultOutcome.Fatal).message.length)
    }

    // ---- event routing ---------------------------------------------------------------------------

    @Test fun everyLifecycleIdRoutesToItsOwnEventType() {
        lifecycleIds.forEach { (id, event) ->
            assertEquals(HaEventRoute.Lifecycle(event), haEventRoute(id, registryIds, lifecycleIds))
        }
    }

    @Test fun registryIdsRouteToRegistryAndUnknownIdsToEntities() {
        registryIds.forEach { assertEquals(HaEventRoute.Registry, haEventRoute(it, registryIds, lifecycleIds)) }
        assertEquals(HaEventRoute.Entities, haEventRoute(1, registryIds, lifecycleIds))
        assertEquals(HaEventRoute.Entities, haEventRoute(9999, registryIds, lifecycleIds))
    }

    @Test fun withoutALifecycleSubscriptionThoseIdsAreOrdinaryEntityFrames() {
        assertEquals(HaEventRoute.Entities, haEventRoute(11, registryIds, emptyMap()))
    }

    @Test fun theRouteIsDecidedByIdAloneAndNeverReadsTheEventBody() {
        // A frame whose body claims a different type must not change the routing: trusting the payload
        // would let any entity event impersonate a shutdown.
        val route = haEventRoute(1, registryIds, lifecycleIds)
        assertEquals(HaEventRoute.Entities, route)
    }
}
