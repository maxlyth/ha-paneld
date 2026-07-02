package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.RootShell
import io.github.maxlyth.hapaneld.util.HelperClient

/**
 * Display-sizing control: **density (DPI)** via `wm density` + **text size (font scale)** via
 * `settings system font_scale`. Together these decide whether a Home-Assistant dashboard designed in a
 * desktop browser renders at a matching size: density sets the dp viewport (physical px ÷ density/160 —
 * the *layout* scale), and the system font scale drives WebView text (`textZoom = fontScale × 100` —
 * the *text* scale). Panel firmware often ships a density/font that doesn't match the physical display,
 * so HA cards come out too big/small or text mis-sized vs desktop; iOS keeps these aligned, Android
 * panels frequently don't.
 *
 * Both persist across reboot (secure/system settings), so a one-shot set sticks. Both are privileged
 * (`wm density` / `settings put system font_scale`): su-direct on su-reachable panels (NSPanel Pro
 * PX30, WF1589T); on sandbox-walled panels (TPA10, `appCanSu=false`) density routes through the root
 * daemon's `DENSITY` command and font scale through its `FONTSCALE` command, so both controls are
 * available there too.
 */
class DensityController(
    private val canSu: Boolean = DeviceProfile.detect().appCanSu,
    private val root: RootShell = Su,
    private val daemon: Daemon = HelperClient,
) {

    /** Native (physical) density, or null if unreadable. */
    fun native(): Int? = if (canSu) parseSu("Physical density:") else daemonField("PHYS")

    /** Current effective density — the override if one is set, else the physical density. */
    fun current(): Int? =
        if (canSu) (parseSu("Override density:") ?: native())
        else (daemonField("OVER") ?: native())

    /** True when density is readable — i.e. we can also set it. */
    fun available(): Boolean = native() != null

    /** Set the override density (dpi). Bounded to keep the UI usable/bootable. Returns true if applied. */
    fun set(dpi: Int): Boolean {
        if (dpi < MIN_DPI || dpi > MAX_DPI) return false
        return if (canSu) root.run("wm density $dpi") else daemon.send("DENSITY $dpi") == "OK"
    }

    /** Restore the native density. */
    fun reset(): Boolean =
        if (canSu) root.run("wm density reset") else daemon.send("DENSITY reset") == "OK"

    /** Current system font scale (1.0 when unset). WebView text follows this (textZoom = scale × 100). */
    fun fontScale(): Float =
        if (canSu) (root.runOutput("settings get system font_scale 2>/dev/null") ?: "").trim().toFloatOrNull() ?: 1.0f
        else daemonScale() ?: 1.0f

    /** Set the system font scale (text size). Bounded to keep text legible. Returns true if applied. */
    fun setFontScale(scale: Float): Boolean {
        if (scale < MIN_FONT || scale > MAX_FONT) return false
        return if (canSu) root.run("settings put system font_scale $scale")
        else daemon.send("FONTSCALE $scale") == "OK"
    }

    /** Restore the default font scale (1.0). */
    fun resetFontScale(): Boolean =
        if (canSu) root.run("settings delete system font_scale")
        else daemon.send("FONTSCALE reset") == "OK"

    // su path: parse the `wm density` output ("Physical density: N" / "Override density: N").
    private fun parseSu(key: String): Int? =
        (root.runOutput("wm density 2>/dev/null") ?: "")
            .lineSequence().firstOrNull { it.contains(key) }
            ?.substringAfter(key)?.trim()?.toIntOrNull()

    // daemon path: parse the daemon's "PHYS=<n> OVER=<n|->" reply (OVER absent/"-" => no override).
    private fun daemonField(key: String): Int? =
        daemon.send("DENSITY")?.let { Regex("$key=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    // daemon path: parse the daemon's "SCALE=<v>" reply ("null" when unset => no override, read as 1.0).
    private fun daemonScale(): Float? =
        daemon.send("FONTSCALE")?.let { Regex("SCALE=([0-9.]+)").find(it)?.groupValues?.get(1)?.toFloatOrNull() }

    companion object {
        const val MIN_DPI = 80
        const val MAX_DPI = 640
        const val MIN_FONT = 0.5f
        const val MAX_FONT = 1.5f
    }
}
