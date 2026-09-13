package io.github.maxlyth.hapaneld.mqtt

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The converger's one sender, delivering every observation to a primary sink and any added sinks.
 *
 * Only the primary's acknowledgement reaches the converger, so convergence keeps its single authority
 * and the primary's own acknowledgement rule. An added sink sees the same observations but owns its own
 * delivery: its acknowledgement is discarded, and a throwing or silent added sink can neither withhold
 * nor duplicate the primary's completion. Each added sink is isolated from the others' exceptions.
 *
 * Added sinks receive an observation before the primary. The primary's acknowledgement frees the
 * channel for its next observation, possibly before it returns; delivering to added sinks afterwards
 * could hand them an older value behind a newer one, and the protocol carries no sequence number.
 */
class StateSinkFanOut(private val primary: StateSink) : StateSink {
    private val added = CopyOnWriteArrayList<StateSink>()

    fun add(sink: StateSink) {
        added += sink
    }

    override fun invoke(
        channel: String,
        observation: StateConverger.Observation.Reportable,
        done: (Boolean) -> Unit,
    ) {
        for (sink in added) {
            try {
                sink(channel, observation, IGNORED)
            } catch (_: Exception) {
                // An added sink never affects the primary's outcome or the other sinks.
            }
        }
        // A primary failure propagates; the converger turns it into done(false).
        primary(channel, observation, done)
    }

    private companion object {
        val IGNORED: (Boolean) -> Unit = {}
    }
}
