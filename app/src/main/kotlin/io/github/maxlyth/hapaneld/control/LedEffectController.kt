package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.hardware.LedController
import io.github.maxlyth.hapaneld.hardware.LedEffects
import io.github.maxlyth.hapaneld.util.periodic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * Runs one LED [LedEffects.Effect] as a cancellable background loop, driving [led] a frame at a time
 * (one [LedEffects.frame] per [LedEffects.Effect.stepMs] tick). Modelled on [WatchdogController]:
 * self-owns an IO scope + a single `@Volatile` job; [start] cancels-and-replaces, [stop] cancels.
 *
 * Runs on [Dispatchers.IO] — mandatory, because the daemon-backed LED HAL
 * ([io.github.maxlyth.hapaneld.hardware.SocketLedController] via `HelperClient`) does blocking socket
 * I/O on every frame. The MQTT bridge owns one instance and calls [start]/[stop] from `handleLed`.
 */
class LedEffectController(private val led: LedController) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null

    /** Start (or replace) [effect], using base colour [r]/[g]/[b] scaled by brightness [br] (all 0..255). */
    fun start(effect: LedEffects.Effect, r: Int, g: Int, b: Int, br: Int) {
        job?.cancel()
        var step = 0
        job = scope.periodic(effect.stepMs, tag = TAG, name = "led-${effect.effectName}") {
            when (val f = LedEffects.frame(effect, step++, r, g, b, br)) {
                is LedEffects.Frame.Rgb -> led.setRgb(f.r, f.g, f.b)
                LedEffects.Frame.Off -> led.off()
            }
        }
    }

    /** Cancel any running effect. The caller then applies the solid colour / off state itself. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** True while an effect loop is active. */
    fun running(): Boolean = job?.isActive == true

    private companion object {
        const val TAG = "ha-paneld/led-fx"
    }
}
