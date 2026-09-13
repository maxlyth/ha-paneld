package io.github.maxlyth.hapaneld.mqtt

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The converger's one sender, delivering every observation to a primary sink and any added sinks.
 *
 * Only the primary's acknowledgement reaches the converger, so convergence keeps its single authority
 * and the primary's own acknowledgement rule. An added sink sees the same observations but owns its own
 * delivery: its acknowledgement is discarded, and a throwing or silent added sink can neither withhold
 * nor duplicate the primary's completion. Each sink is isolated from the others' exceptions.
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
        val primaryFailure = try {
            primary(channel, observation, done)
            null
        } catch (failure: Exception) {
            failure
        }
        for (sink in added) {
            try {
                sink(channel, observation, IGNORED)
            } catch (_: Exception) {
                // An added sink never affects the primary's outcome or the other sinks.
            }
        }
        // Rethrown after every sink has seen the observation; the converger turns it into done(false).
        primaryFailure?.let { throw it }
    }

    private companion object {
        val IGNORED: (Boolean) -> Unit = {}
    }
}
