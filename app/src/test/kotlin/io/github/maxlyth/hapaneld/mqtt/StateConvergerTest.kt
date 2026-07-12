package io.github.maxlyth.hapaneld.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateConvergerTest {
    private data class Sent(val topic: String, val payload: String, val done: (Boolean) -> Unit)
    private fun converger(sent: MutableList<Sent>) = StateConverger(
        sender = { topic, payload, _, done -> sent += Sent(topic, payload, done) },
        schedule = { it() },
    )

    @Test fun acknowledgedStateSuppressesStableRepublish() {
        var value = "ON"
        val sent = mutableListOf<Sent>()
        val c = converger(sent)
        c.register(StateConverger.Channel("screen", "screen/state", observe = { StateConverger.Observation.Known(value) }))

        c.reconcile("screen")
        sent.single().done(true)
        c.reconcile("screen")

        assertEquals(1, sent.size)
    }

    @Test fun failedPublishRemainsDirtyAndRetries() {
        val sent = mutableListOf<Sent>()
        val c = converger(sent)
        c.register(StateConverger.Channel("screen", "screen/state", observe = { StateConverger.Observation.Known("OFF") }))

        c.reconcile("screen")
        sent.last().done(false)
        c.reconcile("screen")

        assertEquals(2, sent.size)
        assertEquals(1, c.status().dirty)
    }

    @Test fun outageRetainsRealStateAndDrainsAfterReconnectWithoutInventingFallbacks() {
        var screen: StateConverger.Observation = StateConverger.Observation.Known("""{"state":"ON","brightness":73}""")
        var relay: StateConverger.Observation = StateConverger.Observation.Unknown
        val sent = mutableListOf<Sent>()
        val c = converger(sent)
        c.register(StateConverger.Channel("screen", "screen/state", observe = { screen }))
        c.register(StateConverger.Channel("relay", "relay/state", observe = { relay }))

        c.reconcileAll()
        assertEquals(listOf("""{"state":"ON","brightness":73}"""), sent.map { it.payload })
        sent.single().done(false) // broker/auth outage
        c.markAllDirty()
        assertEquals(2, c.status().dirty)

        c.reconcileAll() // reconnect: known state drains; unknown state publishes no OFF/zero/unavailable
        assertEquals(listOf("""{"state":"ON","brightness":73}""", """{"state":"ON","brightness":73}"""), sent.map { it.payload })
        sent.last().done(true)
        assertEquals(0, c.status().dirty)
        assertEquals(1, c.status().unknown)
    }

    @Test fun statusShowsAcknowledgedConvergence() {
        val sent = mutableListOf<Sent>()
        val c = converger(sent)
        c.register(StateConverger.Channel("volume", "volume/state", observe = {
            StateConverger.Observation.Known("50")
        }))
        c.reconcile("volume")
        assertEquals(1, c.status().inFlight)
        sent.single().done(true)
        assertEquals(0, c.status().dirty)
        assertEquals(1, c.status().successes)
    }

    @Test fun olderAcknowledgementCannotOverrideNewerObservation() {
        var value = "ON"
        val sent = mutableListOf<Sent>()
        val c = converger(sent)
        c.register(StateConverger.Channel("screen", "screen/state", observe = { StateConverger.Observation.Known(value) }))

        c.reconcile("screen")
        value = "OFF"
        c.reconcile("screen")
        sent[0].done(true)
        sent[1].done(true)
        c.reconcile("screen")

        assertEquals(listOf("ON", "OFF"), sent.map { it.payload })
    }

    @Test fun unknownAndUnavailableNeverInventState() {
        val sent = mutableListOf<Sent>()
        var observation: StateConverger.Observation = StateConverger.Observation.Unknown
        val c = converger(sent)
        c.register(StateConverger.Channel("relay", "relay/state", observe = { observation }))

        c.reconcile("relay")
        observation = StateConverger.Observation.Unavailable
        c.reconcile("relay")

        assertTrue(sent.isEmpty())
        assertEquals(1, c.status().unknown)
    }

    @Test fun forceDoesNotDuplicateIdenticalInFlightPayload() {
        val sent = mutableListOf<Sent>()
        val c = converger(sent)
        c.register(StateConverger.Channel("screen", "screen/state", observe = {
            StateConverger.Observation.Known("ON")
        }))
        c.reconcile("screen", force = true)
        c.reconcile("screen", force = true)
        assertEquals(1, sent.size)
    }

    @Test fun boundedOutboxPumpsAfterAcknowledgement() {
        val sent = mutableListOf<Sent>()
        val c = StateConverger(
            sender = { topic, payload, _, done -> sent += Sent(topic, payload, done) },
            schedule = { it() },
        )
        repeat(6) { n ->
            c.register(StateConverger.Channel("c$n", "state/$n", observe = {
                StateConverger.Observation.Known(n.toString())
            }))
        }
        c.reconcileAll()
        assertEquals(4, sent.size)
        assertEquals(4, c.status().inFlight)
        sent.first().done(true)
        assertEquals(5, sent.size)
        assertEquals(4, c.status().inFlight)
    }

    @Test fun acknowledgementPumpDoesNotReobserveCleanNoisyChannel() {
        var reads = 0
        val sent = mutableListOf<Sent>()
        val c = converger(sent)
        c.register(StateConverger.Channel("temperature", "temperature/state", observe = {
            StateConverger.Observation.Known((reads++).toString())
        }))
        c.reconcile("temperature")
        sent.single().done(true)
        assertEquals(1, reads)
        assertEquals(1, sent.size)
    }

    @Test fun semanticChangeBeatsNumericDeadband() {
        val equivalent = StateConverger.numericDeadband(5.0)
        assertTrue(equivalent("50", "53"))
        assertEquals(false, equivalent("50", "OFF"))
        assertEquals(false, equivalent("unknown", "50"))
    }
}
