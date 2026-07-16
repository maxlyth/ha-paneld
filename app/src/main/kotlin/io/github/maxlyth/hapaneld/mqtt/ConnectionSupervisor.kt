package io.github.maxlyth.hapaneld.mqtt

/**
 * Pure decision logic for the MQTT reconnect watchdog — the state machine behind the incident history:
 * an auto-reconnect that stalled after a broker/HA restart, a half-open "connected but dead" socket that
 * published into the void, and a rebuild that wedged inside the client and disabled healing. Extracted
 * from the watchdog thread so it can be unit-tested without HiveMQ or Android; the thread only executes
 * the [Action] returned and owns the timing + off-thread execution.
 *
 * Each [tick] decides whether to force a full client rebuild:
 *  - LIVENESS-stale: the client reports up but nothing has been broker-ACKed for [staleMs] → rebuild.
 *  - STATE-stuck: not connected for [STUCK_TICKS] consecutive ticks → rebuild.
 * A rebuild already in flight is not stacked — unless it has been running past [rebuildAbandonMs], when
 * it is presumed wedged and a fresh one is started, so healing can never be permanently disabled.
 *
 * Not thread-safe; the single watchdog thread calls [tick] serially.
 */
class ConnectionSupervisor(
    private val staleMs: Long,
    private val rebuildAbandonMs: Long,
) {
    private var staleTicks = 0
    private var rebuildStartedAt = 0L

    sealed interface Action {
        /** Link healthy or still within tolerance — do nothing this tick. */
        object None : Action

        /** Force a full client rebuild; [reason] is "liveness" or "state". */
        data class Rebuild(val reason: String) : Action

        /** A rebuild is wanted but one started [inFlightMs] ago is still running and not yet presumed wedged. */
        data class SkipRebuild(val reason: String, val inFlightMs: Long) : Action
    }

    /**
     * @param state           the client's self-reported state ("connected" / "disabled" / anything else)
     * @param lastOkMs        timestamp of the last broker ACK, or 0 if none yet (liveness not armed)
     * @param sinceOkMs       ms since the last broker ACK
     * @param now             monotonic now (same clock across calls), for the wedged-rebuild guard
     * @param rebuildInFlight whether a rebuild started by a prior [Action.Rebuild] is still running
     */
    fun tick(state: String, lastOkMs: Long, sinceOkMs: Long, now: Long, rebuildInFlight: Boolean): Action {
        val reason = when {
            // AuthRecovery owns this failure class with a bounded fresh-client schedule. The generic
            // watchdog must neither stack retries nor flip address families for credential rejection.
            isAuthRecoveryState(state) -> { staleTicks = 0; return Action.None }
            state != DISABLED && lastOkMs != 0L && sinceOkMs > staleMs -> { staleTicks = 0; "liveness" }
            state == CONNECTED || state == DISABLED -> { staleTicks = 0; return Action.None }
            else -> {
                staleTicks++
                if (staleTicks >= STUCK_TICKS) { staleTicks = 0; "state" } else return Action.None
            }
        }
        if (rebuildInFlight && now - rebuildStartedAt < rebuildAbandonMs) {
            return Action.SkipRebuild(reason, now - rebuildStartedAt)
        }
        rebuildStartedAt = now
        return Action.Rebuild(reason)
    }

    companion object {
        const val STUCK_TICKS = 2
        private const val CONNECTED = "connected"
        private const val DISABLED = "disabled"
    }
}

/**
 * Generation-aware admission for sacrificial MQTT heartbeat threads.
 *
 * A live heartbeat suppresses another probe only while it belongs to the current runtime. Once runtime
 * replacement advances the generation, that old client may remain wedged forever; the replacement still
 * needs exactly one heartbeat thread of its own so its broker liveness can be established.
 */
internal object HeartbeatAdmission {
    sealed interface Decision {
        data object NoCurrentRuntime : Decision
        data object CurrentHeartbeatAlive : Decision
        data class Admit(val generation: Long, val replacingStranded: Boolean) : Decision
        data class EscalateRecovery(val strandedGenerations: Int) : Decision
    }

    fun decide(
        currentGeneration: Long?,
        liveTrackedGenerations: Collection<Long>,
        maxStrandedGenerations: Int = MAX_STRANDED_GENERATIONS,
    ): Decision {
        currentGeneration ?: return Decision.NoCurrentRuntime
        if (currentGeneration in liveTrackedGenerations) {
            return Decision.CurrentHeartbeatAlive
        }
        val stranded = liveTrackedGenerations.count { it != currentGeneration }
        if (stranded > maxStrandedGenerations) {
            return Decision.EscalateRecovery(stranded)
        }
        return Decision.Admit(
            generation = currentGeneration,
            replacingStranded = stranded > 0,
        )
    }

    const val MAX_STRANDED_GENERATIONS = 2
}
