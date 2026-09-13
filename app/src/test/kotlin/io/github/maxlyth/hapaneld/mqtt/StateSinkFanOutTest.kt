package io.github.maxlyth.hapaneld.mqtt

import io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateSinkFanOutTest {
    private data class Seen(val channel: String, val observation: Observation.Reportable)

    private class Recording : StateSink {
        val seen = mutableListOf<Seen>()
        val done = mutableListOf<(Boolean) -> Unit>()
        override fun invoke(channel: String, observation: Observation.Reportable, done: (Boolean) -> Unit) {
            seen += Seen(channel, observation)
            this.done += done
        }
    }

    private fun converger(sender: StateSink) = StateConverger(sender = sender, schedule = { it() })

    @Test fun anAddedSinkSeesEveryObservationThePrimarySees() {
        val primary = Recording()
        val second = Recording()
        val fanOut = StateSinkFanOut(primary).also { it.add(second) }
        var screen: Observation = Observation.Known("ON")
        val c = converger(fanOut)
        c.register(StateConverger.Channel("screen", observe = { screen }))
        c.register(StateConverger.Channel("relay1", observe = { Observation.Unavailable }))

        c.reconcileAll()
        primary.done.toList().forEach { it(true) }
        screen = Observation.Known("OFF")
        c.reconcile("screen")
        primary.done.last()(true)

        assertEquals(
            listOf(
                Seen("screen", Observation.Known("ON")),
                Seen("relay1", Observation.Unavailable),
                Seen("screen", Observation.Known("OFF")),
            ),
            primary.seen,
        )
        assertEquals(primary.seen, second.seen)
    }

    @Test fun onlyThePrimaryAcknowledgementDrivesConvergence() {
        val primary = Recording()
        val second = Recording()
        val c = converger(StateSinkFanOut(primary).also { it.add(second) })
        c.register(StateConverger.Channel("screen", observe = { Observation.Known("ON") }))

        c.reconcile("screen")
        assertEquals("the added sink saw the observation", 1, second.done.size)
        second.done.single()(true)
        assertEquals("an added sink's acknowledgement is not convergence", 1, c.status().inFlight)
        assertEquals(0L, c.status().successes)

        primary.done.single()(true)
        assertEquals(0, c.status().inFlight)
        assertEquals(0, c.status().dirty)
        assertEquals(1L, c.status().successes)
    }

    @Test fun aThrowingAddedSinkNeverBlocksOrDuplicatesThePrimaryAcknowledgement() {
        val primary = Recording()
        val after = Recording()
        val fanOut = StateSinkFanOut(primary)
        fanOut.add { _, _, _ -> error("websocket sink exploded") }
        fanOut.add(after)
        val c = converger(fanOut)
        c.register(StateConverger.Channel("screen", observe = { Observation.Known("ON") }))

        c.reconcile("screen")
        assertEquals("the converger must not see the added sink's failure", 1, c.status().inFlight)
        assertEquals(0L, c.status().failures)
        assertEquals("a later sink still sees the observation", 1, after.seen.size)

        primary.done.single()(true)
        assertEquals(1L, c.status().successes)
        assertEquals(0L, c.status().failures)
        assertEquals(0, c.status().dirty)
    }

    @Test fun aSilentAddedSinkNeverWithholdsThePrimaryAcknowledgement() {
        val primary = Recording()
        val silent = Recording()
        val c = converger(StateSinkFanOut(primary).also { it.add(silent) })
        c.register(StateConverger.Channel("screen", observe = { Observation.Known("ON") }))

        c.reconcile("screen")
        primary.done.single()(true)
        c.reconcile("screen")

        assertEquals(1L, c.status().successes)
        assertEquals(0, c.status().inFlight)
        assertEquals("acknowledged state is not republished while the added sink stays silent", 1, primary.seen.size)
    }

    @Test fun aFailingPrimaryStillReachesAddedSinksAndFailsConvergence() {
        val second = Recording()
        val fanOut = StateSinkFanOut { _, _, _ -> error("broker gone") }.also { it.add(second) }
        val c = converger(fanOut)
        c.register(StateConverger.Channel("screen", observe = { Observation.Known("ON") }))

        c.reconcile("screen")

        assertEquals(1, second.seen.size)
        assertEquals(1L, c.status().failures)
        assertEquals(1, c.status().dirty)
    }

    @Test fun channelIdsFollowTheProtocolGrammar() {
        val c = converger(Recording())
        listOf("screen", "relay3", "button_led3", "a", "a" + "b".repeat(47)).forEach {
            c.register(StateConverger.Channel(it, observe = { Observation.Unknown }))
        }
        listOf("", "Screen", "3relay", "screen/state", "http:screen", "a" + "b".repeat(48), "_x").forEach { id ->
            val refused = runCatching { c.register(StateConverger.Channel(id, observe = { Observation.Unknown })) }
            assertTrue("$id must be refused", refused.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
