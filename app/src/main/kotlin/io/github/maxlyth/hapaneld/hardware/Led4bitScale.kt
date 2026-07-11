package io.github.maxlyth.hapaneld.hardware

/**
 * Maps an 8-bit LED channel value (0..255) to the rk3576 panel's 4-bit PWM range (0..15).
 *
 * **Truncates** (`v*15/255`) rather than rounds, on purpose. Rounding is marginally more accurate in the
 * mid-range but, at the dim end of a `pulse`, it lets a colour's close channels collapse onto the SAME
 * low level — e.g. a muted blue (65,89,132) at ~19% brightness rounds every channel to 1 → `(1,1,1)` =
 * a **white flash** just before the trough. Truncation instead drops each weaker channel to 0 before the
 * dominant one, so a saturated colour dims toward its own hue and then off — never through white. (16
 * levels can't hold a near-grey colour's ratio at the very bottom regardless; truncation keeps the
 * failure mode hue-dominant, not white.) Pure + unit-tested (Led4bitScaleTest).
 */
object Led4bitScale {
    fun to4bit(v: Int): Int = (v.coerceIn(0, 255) * 15) / 255
}
