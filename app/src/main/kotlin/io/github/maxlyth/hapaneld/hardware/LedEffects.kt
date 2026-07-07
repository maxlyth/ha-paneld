package io.github.maxlyth.hapaneld.hardware

/**
 * Pure, deterministic frame generator for the LED effects exposed through Home Assistant's built-in
 * light `effect_list` (strobe / blink / pulse). No Android deps, so it unit-tests like [PanelHealth].
 * The coroutine driver [io.github.maxlyth.hapaneld.control.LedEffectController] calls [frame] once per
 * [Effect.stepMs] tick and pushes the result to the LED HAL.
 *
 * Colour comes from HA's own light colour control (the last commanded r/g/b) and brightness is HA's
 * brightness (both 0..255) — HA's `effect` is a bare name with no parameters, so per-effect timing is a
 * fixed sensible constant, not a tunable. Periods are kept coarse enough that even a daemon-backed LED
 * (one blocking socket write per frame) stays around a dozen writes per second.
 */
object LedEffects {

    /** The supported effects. [effectName] is the exact HA `effect_list` string; [stepMs] the tick. */
    enum class Effect(val effectName: String, val stepMs: Long) {
        STROBE("strobe", 80L),   // aggressive ~6 Hz on/off — attention-grabbing (alarm use case)
        BLINK("blink", 500L),    // gentle ~1 Hz on/off
        PULSE("pulse", 90L);     // breathing brightness ramp, ~2.5 s cycle (PULSE_STEPS × stepMs)

        companion object {
            /** The [Effect] for an HA effect name, or null for "none"/blank/unknown (= no effect). */
            fun from(name: String?): Effect? {
                val n = name?.trim() ?: return null
                return entries.firstOrNull { it.effectName.equals(n, ignoreCase = true) }
            }
        }
    }

    /** One rendered frame: an RGB triple (brightness already baked in) or the LED off. */
    sealed interface Frame {
        data class Rgb(val r: Int, val g: Int, val b: Int) : Frame
        object Off : Frame
    }

    /** Ticks in one full pulse (breathing) cycle: brightness ramps up over the first half, down the second. */
    const val PULSE_STEPS = 28

    /**
     * The frame for [effect] at 0-based [step], over base colour [r]/[g]/[b] scaled by brightness [br]
     * (all 0..255). strobe/blink alternate a lit frame with [Frame.Off]; pulse ramps the effective
     * brightness triangularly (0 → br → 0) so the colour fades in and out.
     */
    fun frame(effect: Effect, step: Int, r: Int, g: Int, b: Int, br: Int): Frame = when (effect) {
        Effect.STROBE, Effect.BLINK -> if (step % 2 == 0) lit(r, g, b, br) else Frame.Off
        Effect.PULSE -> {
            val half = PULSE_STEPS / 2
            val phase = ((step % PULSE_STEPS) + PULSE_STEPS) % PULSE_STEPS
            val up = if (phase <= half) phase else PULSE_STEPS - phase   // 0..half..0
            lit(r, g, b, br * up / half)                                 // effective brightness 0..br
        }
    }

    private fun lit(r: Int, g: Int, b: Int, br: Int): Frame.Rgb =
        Frame.Rgb(r * br / 255, g * br / 255, b * br / 255)
}
