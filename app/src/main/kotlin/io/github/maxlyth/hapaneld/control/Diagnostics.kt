package io.github.maxlyth.hapaneld.control

import android.os.SystemClock
import io.github.maxlyth.hapaneld.metrics.PanelMetrics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Point-in-time panel diagnostics for the optional HA diagnostic sensors (all opt-in). A thin facade over
 * the shared [PanelMetrics] reader — CPU / memory / SoC-temperature / IP all come from the one reader that
 * [io.github.maxlyth.hapaneld.http.PerfReader] also uses, so there is no duplicated (and previously
 * divergent) `/proc`+`/sys` logic to keep in step, and the reader's direct→daemon fallback means these
 * always-on sensors now read on sandbox-walled panels too (TPA10), where the old direct-only code returned
 * null. The reader keeps a single CPU-delta baseline + a freshness cache, so calling these several times in
 * one heartbeat coalesces onto one read.
 *
 * Boot time stays here: it's an Android-only constant (never a `/proc` poll), computed once so the sensor
 * shows a stable timestamp rather than an ever-rising uptime.
 */
object Diagnostics {

    // Boot wall-clock, computed once — a constant timestamp sensor never flaps (vs an ever-rising uptime).
    private val bootIso: String by lazy {
        val bootMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(bootMs))
    }

    /** LAN IPv4 (the headline request — "for those who don't remember them"). Null if none. */
    fun ipAddress(): String? = PanelMetrics.shared.ipAddress()

    /** Boot time as an ISO-8601 UTC timestamp (constant until reboot → HA shows "N hours ago"). */
    fun bootTime(): String = bootIso

    /** Used memory as a percentage (0–100), or null if `/proc/meminfo` is unreadable. */
    fun memoryPercent(): Int? = PanelMetrics.shared.systemSnapshot().memPercent

    /** Max SoC/thermal-zone temperature in °C, or null (no zone / unreadable, with no daemon fallback). */
    fun socTempC(): Double? = PanelMetrics.shared.systemSnapshot().socTempC

    /** Overall CPU busy percentage since the previous read (a ~tick-length average), or null on the first
     *  read / when unreadable. */
    fun cpuPercent(): Int? = PanelMetrics.shared.systemSnapshot().cpuOverall

    /** Room air temperature in °C from the CHT8305 (daemon-only; panels with the chip), or null. RAW — the
     *  calibration offset is applied by the publisher, not here. Coalesced with [roomHumidity] onto one read. */
    fun roomTempC(): Double? = PanelMetrics.shared.roomClimate()?.tempC

    /** Room relative humidity (%RH) from the CHT8305, or null. */
    fun roomHumidity(): Double? = PanelMetrics.shared.roomClimate()?.humidityPct
}
