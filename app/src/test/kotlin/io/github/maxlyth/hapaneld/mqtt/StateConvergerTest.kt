package io.github.maxlyth.hapaneld.mqtt

import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostRegistry
import io.github.maxlyth.hapaneld.util.MonotonicDeadline
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateConvergerTest {
    private data class Sent(val topic: String, val payload: String, val retain: Boolean, val done: (Boolean) -> Unit)
    private fun converger(sent: MutableList<Sent>) = StateConverger(
        sender = { topic, payload, retain, done -> sent += Sent(topic, payload, retain, done) },
        schedule = { it() },
    )

    @Test fun closeDeadlineDoesNotPretendABlockedObservationDrained() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var observations = 0
        val c = converger(mutableListOf())
        c.register(StateConverger.Channel("screen", "screen/state", observe = {
            observations++
            entered.countDown()
            release.await()
            StateConverger.Observation.Known("ON")
        }))
        val worker = Thread { c.reconcile("screen") }.apply { start() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(c.closeAndDrain(MonotonicDeadline(0L)))
        } finally {
            release.countDown()
            worker.join(1_000L)
        }
        assertTrue(c.closeAndDrain(MonotonicDeadline(1_000L)))
        c.reconcile("screen")
        assertEquals(1, observations)
    }

    @Test fun closeDeadlineDoesNotPretendABlockedSenderDrained() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val c = StateConverger(
            sender = { _, _, _, _ ->
                entered.countDown()
                release.await()
            },
            schedule = { it() },
        )
        c.register(StateConverger.Channel("screen", "screen/state", observe = {
            StateConverger.Observation.Known("ON")
        }))
        val worker = Thread { c.reconcile("screen") }.apply { start() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(c.closeAndDrain(MonotonicDeadline(0L)))
        } finally {
            release.countDown()
            worker.join(1_000L)
        }
        assertTrue(c.closeAndDrain(MonotonicDeadline(1_000L)))
    }

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
        assertEquals(listOf("ON"), sent.map { it.payload })
        sent[0].done(true)
        assertEquals(listOf("ON", "OFF"), sent.map { it.payload })
        sent[1].done(true)
        c.reconcile("screen")

        assertEquals(listOf("ON", "OFF"), sent.map { it.payload })
    }

    @Test fun alternatingFloodConflatesToOnePhysicalPublishPerChannel() {
        var value = "ON"
        val sent = mutableListOf<Sent>()
        val c = converger(sent)
        c.register(StateConverger.Channel("screen", "screen/state", observe = {
            StateConverger.Observation.Known(value)
        }))

        c.reconcile("screen")
        repeat(1_000) {
            value = if (it % 2 == 0) "OFF" else "ON"
            c.reconcile("screen", force = true)
        }
        value = "OFF"
        c.reconcile("screen", force = true)
        assertEquals(1, sent.size)
        assertEquals(1, c.status().inFlight)

        sent.single().done(true)
        assertEquals(listOf("ON", "OFF"), sent.map { it.payload })
        assertEquals(1, c.status().inFlight)
        sent.last().done(true)
        assertEquals(0, c.status().inFlight)
    }

    @Test fun connectionInvalidationRejectsOldAckAndRepublishesStableState() {
        val sent = mutableListOf<Sent>()
        val c = converger(sent)
        c.register(StateConverger.Channel("screen", "screen/state", observe = {
            StateConverger.Observation.Known("ON")
        }))

        c.reconcile("screen")
        val oldConnection = sent.single()
        c.markAllDirty()
        oldConnection.done(true)

        assertEquals(1, c.status().dirty)
        assertEquals(0, c.status().successes)
        c.reconcileAll()
        assertEquals(listOf("ON", "ON"), sent.map { it.payload })
        sent.last().done(true)
        assertEquals(0, c.status().dirty)
        assertEquals(1, c.status().successes)
    }

    @Test fun connectionInvalidationCancelsTheOrphanedPublishCostSpan() {
        var wall = 0L
        val costs = FeatureCostRegistry(
            wallNanos = { wall },
            threadCpuNanos = { -1L },
            threadId = { 1L },
        )
        val sent = mutableListOf<Sent>()
        val c = StateConverger(
            sender = { topic, payload, retain, done -> sent += Sent(topic, payload, retain, done) },
            schedule = { it() },
            featureCosts = costs,
        )
        c.register(StateConverger.Channel("screen", "screen/state", observe = {
            StateConverger.Observation.Known("ON")
        }))

        c.reconcile("screen")
        assertEquals(1, costOperation(costs).getInt("in_flight"))
        wall += 5_000_000L
        c.markAllDirty()

        val cancelled = costOperation(costs)
        assertEquals(0, cancelled.getInt("in_flight"))
        assertEquals(1L, cancelled.getLong("cancelled"))
        assertEquals(5_000_000L, cancelled.getLong("wall_ns_total"))
        sent.single().done(true)
        assertEquals(0L, costOperation(costs).getLong("succeeded"))
    }

    @Test fun unknownObservationCannotReuseAcknowledgementFromOldConnection() {
        var observation: StateConverger.Observation = StateConverger.Observation.Known("ON")
        val sent = mutableListOf<Sent>()
        val c = converger(sent)
        c.register(StateConverger.Channel("relay", "relay/state", observe = { observation }))

        c.reconcile("relay")
        sent.single().done(true)
        c.markAllDirty()
        observation = StateConverger.Observation.Unknown
        c.reconcile("relay")
        observation = StateConverger.Observation.Known("ON")
        c.reconcile("relay")

        assertEquals(listOf("ON", "ON"), sent.map { it.payload })
    }

    @Test fun unknownInventsNoStateAndUnavailableClearsRetainedState() {
        val sent = mutableListOf<Sent>()
        var observation: StateConverger.Observation = StateConverger.Observation.Unknown
        val c = converger(sent)
        c.register(StateConverger.Channel("relay", "relay/state", observe = { observation }))

        c.reconcile("relay")
        observation = StateConverger.Observation.Unavailable
        c.reconcile("relay")

        assertEquals(listOf(""), sent.map { it.payload })
        assertTrue(sent.single().retain)
        assertEquals(1, c.status().unknown)
    }

    @Test fun unavailableClearWaitsForOlderInFlightValueAndWinsLast() {
        var observation: StateConverger.Observation = StateConverger.Observation.Known("Private network")
        val sent = mutableListOf<Sent>()
        val c = converger(sent)
        c.register(StateConverger.Channel("ssid", "wifi/state", observe = { observation }))

        c.reconcile("ssid")
        observation = StateConverger.Observation.Unavailable
        c.reconcile("ssid", force = true)
        assertEquals(listOf("Private network"), sent.map { it.payload })

        sent.single().done(true)
        assertEquals(listOf("Private network", ""), sent.map { it.payload })
        sent.last().done(true)
        assertEquals(0, c.status().dirty)
        assertEquals(1, c.status().unknown)
    }

    @Test fun unknownObservationDoesNotReleaseAnInFlightSlot() {
        var first: StateConverger.Observation = StateConverger.Observation.Known("0")
        val sent = mutableListOf<Sent>()
        val c = converger(sent)
        repeat(5) { n ->
            c.register(StateConverger.Channel("c$n", "state/$n", observe = {
                if (n == 0) first else StateConverger.Observation.Known(n.toString())
            }))
        }

        c.reconcileAll()
        assertEquals(4, sent.size)
        first = StateConverger.Observation.Unknown
        c.reconcile("c0")
        c.reconcile("c4")

        assertEquals(4, sent.size)
        assertEquals(4, c.status().inFlight)
        sent.first().done(true)
        assertEquals(5, sent.size)
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

    @Test fun closeRejectsQueuedAuditsAndLateAcknowledgements() {
        val sent = mutableListOf<Sent>()
        val c = converger(sent)
        c.register(StateConverger.Channel("screen", "screen/state", observe = {
            StateConverger.Observation.Known("ON")
        }))

        c.reconcile("screen")
        c.close()
        sent.single().done(true)
        c.reconcileAll(force = true)

        assertEquals(1, sent.size)
        assertEquals(0, c.status().inFlight)
        assertEquals(0, c.status().dirty)
        assertEquals(0, c.status().successes)
    }

    @Test fun closeCancelsTheOrphanedPublishCostSpan() {
        val costs = FeatureCostRegistry(
            wallNanos = { 0L },
            threadCpuNanos = { -1L },
            threadId = { 1L },
        )
        val sent = mutableListOf<Sent>()
        val c = StateConverger(
            sender = { topic, payload, retain, done -> sent += Sent(topic, payload, retain, done) },
            schedule = { it() },
            featureCosts = costs,
        )
        c.register(StateConverger.Channel("screen", "screen/state", observe = {
            StateConverger.Observation.Known("ON")
        }))

        c.reconcile("screen")
        c.close()
        sent.single().done(false)

        val operation = costOperation(costs)
        assertEquals(0, operation.getInt("in_flight"))
        assertEquals(1L, operation.getLong("cancelled"))
        assertEquals(0L, operation.getLong("failed"))
    }

    @Test fun boundedOutboxPumpsAfterAcknowledgement() {
        val sent = mutableListOf<Sent>()
        val c = StateConverger(
            sender = { topic, payload, retain, done -> sent += Sent(topic, payload, retain, done) },
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

    @Test fun capacityRefusalDoesNotSilentlyDropAJustCommandedState() {
        var relay = "OFF"
        val sent = mutableListOf<Sent>()
        val c = converger(sent)
        for (n in 1..4) {
            c.register(StateConverger.Channel("busy$n", "busy$n/state", observe = { StateConverger.Observation.Known("x") }))
        }
        c.register(StateConverger.Channel("relay1", "relay1/state", observe = { StateConverger.Observation.Known(relay) }))

        // Converge every channel CLEAN first — the drop path only existed for a clean channel.
        c.reconcile("busy1")
        sent.single().done(true)   // the ACK pump drains the remaining registered-dirty channels
        assertEquals(5, sent.size)
        sent.drop(1).forEach { it.done(true) }
        assertEquals(0, c.status().dirty)
        assertEquals(0, c.status().inFlight)

        // Four channels refill the bounded outbox, then a command's forced read-back arrives.
        for (n in 1..4) c.reconcile("busy$n", force = true)
        assertEquals(4, c.status().inFlight)
        relay = "ON"
        c.reconcile("relay1", force = true)

        // Capacity refusal admits no fifth publish, but the observation must survive as dirty:
        // the four admitted channels hold dirty until their ACK, and the refused relay1 makes five.
        assertEquals(9, sent.size)
        assertEquals(5, c.status().dirty)

        // The first acknowledgement pumps the dirty drain and the commanded state publishes —
        // not up to a full audit period later.
        sent[5].done(true)
        assertEquals("relay1/state", sent.last().topic)
        assertEquals("ON", sent.last().payload)
    }

    @Test fun semanticChangeBeatsNumericDeadband() {
        val equivalent = StateConverger.numericDeadband(5.0)
        assertTrue(equivalent("50", "53"))
        assertEquals(false, equivalent("50", "OFF"))
        assertEquals(false, equivalent("unknown", "50"))
    }

    private fun costOperation(costs: FeatureCostRegistry): JSONObject {
        val operations = JSONObject(costs.json()).getJSONArray("operations")
        return (0 until operations.length()).asSequence()
            .map(operations::getJSONObject)
            .first { it.getString("id") == FeatureCostOperation.MQTT_STATE_OUTBOX.id }
    }
}
