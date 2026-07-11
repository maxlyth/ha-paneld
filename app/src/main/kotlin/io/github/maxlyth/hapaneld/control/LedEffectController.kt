package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.hardware.LedController
import io.github.maxlyth.hapaneld.hardware.LedEffects
import io.github.maxlyth.hapaneld.util.periodic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

/**
 * Runs one LED [LedEffects.Effect] as a cancellable background loop, driving [led] a frame at a time
 * (one [LedEffects.frame] per [LedEffects.Effect.stepMs] tick). Modelled on [WatchdogController]:
 * a single `@Volatile` job; [start] cancels-and-replaces, [stop] cancels.
 *
 * OWNED BY THE SERVICE, not the MQTT bridge: `PaneldService` holds the single instance and injects it
 * into every [io.github.maxlyth.hapaneld.MqttBridge] it builds. A bridge rebuild (panel_id / broker
 * change via `reconfigure()`) therefore cannot orphan a running loop — there is only ever ONE loop, and
 * it survives the rebuild (the new bridge re-issues [start] with the persisted colour, a seamless
 * cancel-and-replace). Its lifetime matches the LED hardware's, like [led] itself.
 *
 * Runs on [Dispatchers.IO] — mandatory, because the daemon-backed LED HAL
 * ([io.github.maxlyth.hapaneld.hardware.SocketLedController] via `HelperClient`) does blocking socket
 * I/O on every frame. [start]/[stop] are called from the MQTT callback thread (off-main) in `handleLed`.
 *
 * [start] and [stop] are `@Synchronized` and tear the previous job down with **cancel-and-join**, so no
 * in-flight frame can write to [led] after the caller then applies a solid colour / off — otherwise a
 * frame mid-write (a blocking socket round-trip on TPA10) could land *after* `led.off()` and leave the
 * LED stuck lit while HA shows OFF. The join is bounded by one frame write (a cancelled `delay` throws
 * at once; a blocking HAL write is ≤ one ioctl / socket round-trip), which is safe off the main thread.
 *
 * [scope] is injectable purely so unit tests can drive the loop on a real test dispatcher; production
 * always uses the default IO scope. It must be a real dispatcher, not a virtual-time TestScope — the
 * cancel-and-join runs under [runBlocking] and would deadlock against virtual time.
 */
class LedEffectController(
    private val led: LedController,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Volatile private var job: Job? = null

    /** Start (or replace) [effect], using base colour [r]/[g]/[b] scaled by brightness [br] (all 0..255). */
    @Synchronized
    fun start(effect: LedEffects.Effect, r: Int, g: Int, b: Int, br: Int) {
        cancelAndJoinCurrent()
        var step = 0
        job = scope.periodic(effect.stepMs, tag = TAG, name = "led-${effect.effectName}") {
            when (val f = LedEffects.frame(effect, step++, r, g, b, br)) {
                is LedEffects.Frame.Rgb -> led.setRgb(f.r, f.g, f.b)
                LedEffects.Frame.Off -> led.off()
            }
        }
    }

    /**
     * Cancel any running effect and wait for its in-flight frame to finish. The caller then applies the
     * solid colour / off state itself, guaranteed to be the last write to [led].
     */
    @Synchronized
    fun stop() {
        cancelAndJoinCurrent()
    }

    /** True while an effect loop is active. */
    fun running(): Boolean = job?.isActive == true

    /** Cancel the current job and block until its last frame has finished, so no stale frame survives. */
    private fun cancelAndJoinCurrent() {
        val j = job ?: return
        job = null
        runBlocking { j.cancelAndJoin() }
    }

    private companion object {
        const val TAG = "ha-paneld/led-fx"
    }
}
