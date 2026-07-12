package io.github.maxlyth.hapaneld.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateConvergerTest {
    private data class Sent(val topic: String, val payload: String, val done: (Boolean) -> Unit)

    @Test fun acknowledgedStateSuppressesStableRepublish() {
        var value = "ON"
        val sent = mutableListOf<Sent>()
        val c = StateConverger { topic, payload, _, done -> sent += Sent(topic, payload, done) }
        c.register(StateConverger.Channel("screen", "screen/state", observe = { StateConverger.Observation.Known(value) }))

        c.reconcile("screen")
        sent.single().done(true)
        c.reconcile("screen")

        assertEquals(1, sent.size)
    }

    @Test fun failedPublishRemainsDirtyAndRetries() {
        val sent = mutableListOf<Sent>()
        val c = StateConverger { topic, payload, _, done -> sent += Sent(topic, payload, done) }
        c.register(StateConverger.Channel("screen", "screen/state", observe = { StateConverger.Observation.Known("OFF") }))

        c.reconcile("screen")
        sent.last().done(false)
        c.reconcile("screen")

        assertEquals(2, sent.size)
        assertEquals(1, c.status().dirty)
    }

    @Test fun statusShowsAcknowledgedConvergence() {
        val sent = mutableListOf<Sent>()
        val c = StateConverger { topic, payload, _, done -> sent += Sent(topic, payload, done) }
        c.register(StateConverger.Channel("volume", "volume/state", observe = {
            StateConverger.Observation.Known("50")
        }))
        c.reconcile("volume")
        assertEquals(StateConverger.Status(1, 1, 1, 0), c.status())
        sent.single().done(true)
        assertEquals(StateConverger.Status(1, 0, 0, 0), c.status())
    }

    @Test fun olderAcknowledgementCannotOverrideNewerObservation() {
        var value = "ON"
        val sent = mutableListOf<Sent>()
        val c = StateConverger { topic, payload, _, done -> sent += Sent(topic, payload, done) }
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
        val c = StateConverger { topic, payload, _, done -> sent += Sent(topic, payload, done) }
        c.register(StateConverger.Channel("relay", "relay/state", observe = { observation }))

        c.reconcile("relay")
        observation = StateConverger.Observation.Unavailable
        c.reconcile("relay")

        assertTrue(sent.isEmpty())
        assertEquals(StateConverger.Status(1, 0, 0, 1), c.status())
    }

    @Test fun forceDoesNotDuplicateIdenticalInFlightPayload() {
        val sent = mutableListOf<Sent>()
        val c = StateConverger { topic, payload, _, done -> sent += Sent(topic, payload, done) }
        c.register(StateConverger.Channel("screen", "screen/state", observe = {
            StateConverger.Observation.Known("ON")
        }))
        c.reconcile("screen", force = true)
        c.reconcile("screen", force = true)
        assertEquals(1, sent.size)
    }

    @Test fun semanticChangeBeatsNumericDeadband() {
        val equivalent = StateConverger.numericDeadband(5.0)
        assertTrue(equivalent("50", "53"))
        assertEquals(false, equivalent("50", "OFF"))
        assertEquals(false, equivalent("unknown", "50"))
    }
}
