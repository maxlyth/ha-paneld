package io.github.maxlyth.hapaneld.control

import android.os.SystemClock
import io.github.maxlyth.hapaneld.util.localIpv4
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Point-in-time panel diagnostics for the optional HA diagnostic sensors (all opt-in). Deliberately
 * self-contained and independent of [io.github.maxlyth.hapaneld.http.PerfReader] — that sampler is
 * page-view gated and killed by the instrumentation master switch, neither of which should affect an
 * always-on HA sensor.
 *
 * All reads are cheap, world-readable `/proc` + `/sys` (no root). On sandbox-walled panels (TPA10,
 * appCanSu=false) SELinux denies `/proc/stat` and the thermal zones, so [cpuPercent] and [socTempC]
 * return null there — those sensors simply report nothing; IP, memory and boot time work everywhere.
 * Called once per heartbeat tick from the MQTT bridge (single-threaded), so the CPU-delta state needs
 * no synchronisation.
 */
object Diagnostics {
    // /proc/stat aggregate jiffies at the previous cpuPercent() call, for the delta.
    private var prevIdle = -1L
    private var prevTotal = -1L

    // Boot wall-clock, computed once — a constant timestamp sensor never flaps (vs an ever-rising uptime).
    private val bootIso: String by lazy {
        val bootMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(bootMs))
    }

    /** LAN IPv4 (the headline request — "for those who don't remember them"). Null if none. */
    fun ipAddress(): String? = localIpv4()

    /** Boot time as an ISO-8601 UTC timestamp (constant until reboot → HA shows "N hours ago"). */
    fun bootTime(): String = bootIso

    /** Used memory as a percentage (0–100), or null if `/proc/meminfo` is unreadable. */
    fun memoryPercent(): Int? = runCatching {
        val m = File("/proc/meminfo").readLines().associate {
            val p = it.split(":")
            p[0].trim() to (p.getOrNull(1)?.trim()?.removeSuffix(" kB")?.trim()?.toLongOrNull() ?: 0L)
        }
        val total = m["MemTotal"] ?: return null
        val avail = m["MemAvailable"] ?: m["MemFree"] ?: return null
        if (total <= 0) null else (((total - avail) * 100) / total).toInt().coerceIn(0, 100)
    }.getOrNull()

    /** Max SoC/thermal-zone temperature in °C, or null (no zone / SELinux-denied on sandbox panels). */
    fun socTempC(): Double? = runCatching {
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
            ?.mapNotNull { File(it, "temp").takeIf { t -> t.exists() }?.readText()?.trim()?.toLongOrNull() }
            ?.maxOrNull()
            ?.let { if (it > 1000) it / 1000.0 else it.toDouble() }
    }.getOrNull()

    /** Overall CPU busy percentage since the previous call (a ~tick-length average), or null on the
     *  first call / when `/proc/stat` is unreadable. */
    fun cpuPercent(): Int? {
        val fields = runCatching {
            File("/proc/stat").readLines().first { it.startsWith("cpu ") }
                .trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
        }.getOrNull() ?: return null
        val idle = fields.getOrElse(3) { 0 } + fields.getOrElse(4) { 0 }   // idle + iowait
        val total = fields.sum()
        val pIdle = prevIdle; val pTotal = prevTotal
        prevIdle = idle; prevTotal = total
        if (pTotal < 0) return null                                        // first sample — no delta yet
        val dTotal = total - pTotal; val dIdle = idle - pIdle
        return if (dTotal > 0) (((dTotal - dIdle) * 100) / dTotal).toInt().coerceIn(0, 100) else null
    }
}
