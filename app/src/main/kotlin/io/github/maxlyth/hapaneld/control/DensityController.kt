package io.github.maxlyth.hapaneld.control

/**
 * Display-sizing control: **density (DPI)** via `wm density` + **text size (font scale)** via
 * `settings system font_scale`. Together these decide whether a Home-Assistant dashboard designed in a
 * desktop browser renders at a matching size: density sets the dp viewport (physical px ÷ density/160 —
 * the *layout* scale), and the system font scale drives WebView text (`textZoom = fontScale × 100` —
 * the *text* scale). Panel firmware often ships a density/font that doesn't match the physical display,
 * so HA cards come out too big/small or text mis-sized vs desktop; iOS keeps these aligned, Android
 * panels frequently don't.
 *
 * Both persist across reboot (secure/system settings), so a one-shot set sticks. Root path (Su) →
 * available on su-reachable panels (NSPanel Pro PX30, WF1589T); absent where the app can't reach su
 * (TPA10).
 */
class DensityController {

    /** Native (physical) density, or null if unreadable. */
    fun native(): Int? = parse("Physical density:")

    /** Current effective density — the override if one is set, else the physical density. */
    fun current(): Int? = parse("Override density:") ?: native()

    /** True when `wm density` is readable via su — i.e. we can also set it. */
    fun available(): Boolean = native() != null

    /** Set the override density (dpi). Bounded to keep the UI usable/bootable. Returns true if applied. */
    fun set(dpi: Int): Boolean {
        if (dpi < MIN_DPI || dpi > MAX_DPI) return false
        return Su.run("wm density $dpi")
    }

    /** Restore the native density. */
    fun reset(): Boolean = Su.run("wm density reset")

    /** Current system font scale (1.0 when unset). WebView text follows this (textZoom = scale × 100). */
    fun fontScale(): Float =
        (Su.runOutput("settings get system font_scale 2>/dev/null") ?: "").trim().toFloatOrNull() ?: 1.0f

    /** Set the system font scale (text size). Bounded to keep text legible. Returns true if applied. */
    fun setFontScale(scale: Float): Boolean {
        if (scale < MIN_FONT || scale > MAX_FONT) return false
        return Su.run("settings put system font_scale $scale")
    }

    /** Restore the default font scale (1.0). */
    fun resetFontScale(): Boolean = Su.run("settings delete system font_scale")

    private fun parse(key: String): Int? =
        (Su.runOutput("wm density 2>/dev/null") ?: "")
            .lineSequence().firstOrNull { it.contains(key) }
            ?.substringAfter(key)?.trim()?.toIntOrNull()

    companion object {
        const val MIN_DPI = 80
        const val MAX_DPI = 640
        const val MIN_FONT = 0.5f
        const val MAX_FONT = 1.5f
    }
}
