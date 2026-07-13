package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.RootShell

internal data class BacklightNode(val directory: String, val maximum: Int)
internal data class BacklightReading(val actual: Int, val maximum: Int)

internal enum class BrightnessWriteRoute { SU, HELPER, NONE }

/** Applies a 0–255 brightness command to the real hardware backlight, accepting only actual results. */
internal class BrightnessHardwareWriter(
    private val root: RootShell,
    private val daemon: Daemon,
) {
    fun write(level: Int, node: BacklightNode?): BrightnessWriteRoute {
        val commanded = level.coerceIn(0, 255)
        node?.takeIf { it.maximum > 0 }?.let {
            val hardware = scale(commanded, it.maximum)
            val path = it.directory.trimEnd('/') + "/brightness"
            if (root.run("echo $hardware > $path")) return BrightnessWriteRoute.SU
        }

        val reading = parseBacklightReading(daemon.send("BLREAD")) ?: return BrightnessWriteRoute.NONE
        val hardware = scale(commanded, reading.maximum)
        return if (daemon.send("BLSET $hardware") == "OK") {
            BrightnessWriteRoute.HELPER
        } else {
            BrightnessWriteRoute.NONE
        }
    }

    private fun scale(level: Int, maximum: Int): Int =
        (level.toLong() * maximum / 255).toInt().coerceIn(0, maximum)
}

internal fun parseBacklightReading(reply: String?): BacklightReading? {
    val fields = reply?.trim()?.split(Regex("\\s+")) ?: return null
    if (fields.size != 2) return null
    val actual = fields[0].toIntOrNull() ?: return null
    val maximum = fields[1].toIntOrNull() ?: return null
    return BacklightReading(actual, maximum).takeIf { actual in 0..maximum && maximum > 0 }
}
