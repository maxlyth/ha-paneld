package io.github.maxlyth.hapaneld.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Background performance sampler for the info page. A coroutine ticks every [INTERVAL_MS], computes
 * CPU/GPU/RAM utilisation and keeps the last [MAX] samples in an in-RAM FIFO, so `GET /perf` returns
 * the latest values **plus** the history — the chart is populated immediately on page load and the
 * series survives reloads (it's lost only on app restart). All from world-readable /proc + /sys
 * (no root; readable as the app uid on Android 8.1 + 14).
 *
 * CPU % is the delta between consecutive ticks (so the first tick has no value). GPU is Rockchip
 * Mali devfreq load ("<load>@<freq>Hz"); absent on panels without it.
 */
object PerfReader {
    private const val MAX = 120          // ~4 min at 2s
    private const val INTERVAL_MS = 2000L

    private val lock = Any()
    private var prevStat: List<LongArray>? = null
    private val cpuHist = ArrayDeque<Int>()
    private val ramHist = ArrayDeque<Int>()
    private val gpuHist = ArrayDeque<Int>()
    @Volatile private var latestFields = """"cpu":0,"cores":[],"load":[],"freqMhz":[],"gpu":null,"gpuMhz":0,"tempC":null,"memUsedMb":0,"memTotalMb":0"""

    /** Start the sampling loop on [scope] (idempotent enough for a single service lifetime). */
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(INTERVAL_MS)
            }
        }
    }

    /** Latest sample + history FIFO, as JSON, for `GET /perf`. */
    fun json(): String = synchronized(lock) {
        """{$latestFields,"hist":{"cpu":${cpuHist.toList()},"ram":${ramHist.toList()},"gpu":${gpuHist.toList()}}}"""
    }

    private fun push(q: ArrayDeque<Int>, v: Int) {
        q.addLast(v)
        while (q.size > MAX) q.removeFirst()
    }

    private fun readStat(): List<LongArray> =
        File("/proc/stat").readLines()
            .filter { it.startsWith("cpu") }
            .map { line -> line.trim().split(Regex("\\s+")).drop(1).map { it.toLongOrNull() ?: 0L }.toLongArray() }

    private fun tick() {
        val stat = runCatching { readStat() }.getOrNull()
        val pct = ArrayList<Int>()
        val prev = prevStat
        if (stat != null && prev != null && stat.size == prev.size) {
            for (i in stat.indices) {
                val totA = prev[i].sum(); val totB = stat[i].sum()
                val idleA = prev[i].getOrElse(3) { 0 } + prev[i].getOrElse(4) { 0 }
                val idleB = stat[i].getOrElse(3) { 0 } + stat[i].getOrElse(4) { 0 }
                val dTot = totB - totA; val dIdle = idleB - idleA
                pct.add(if (dTot > 0) (((dTot - dIdle) * 100) / dTot).toInt().coerceIn(0, 100) else 0)
            }
        }
        if (stat != null) prevStat = stat
        if (pct.isEmpty()) return // first tick (no delta yet)

        val overall = pct.first()
        val cores = if (pct.size > 1) pct.drop(1) else emptyList()

        val load = runCatching { File("/proc/loadavg").readText().trim().split(" ").take(3) }.getOrNull() ?: emptyList()
        val freqMhz = runCatching {
            File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu[0-9]+")) }
                ?.sortedBy { it.name }
                ?.map { File(it, "cpufreq/scaling_cur_freq") }
                ?.map { if (it.exists()) (it.readText().trim().toLongOrNull() ?: 0L) / 1000 else 0L }
                ?: emptyList()
        }.getOrNull() ?: emptyList()

        var gpuPct = -1; var gpuMhz = 0L
        runCatching {
            File("/sys/class/devfreq").listFiles { f -> f.name.contains("gpu") }
                ?.firstNotNullOfOrNull { d -> File(d, "load").takeIf { it.exists() }?.readText()?.trim() }
                ?.let { raw ->
                    val at = raw.indexOf('@')
                    gpuPct = (if (at >= 0) raw.substring(0, at) else raw).trim().toIntOrNull() ?: -1
                    if (at >= 0) gpuMhz = raw.substring(at + 1).removeSuffix("Hz").trim().toLongOrNull()?.div(1_000_000) ?: 0L
                }
        }

        val tempC = runCatching {
            File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
                ?.mapNotNull { File(it, "temp").takeIf { t -> t.exists() }?.readText()?.trim()?.toLongOrNull() }
                ?.maxOrNull()?.let { if (it > 1000) it / 1000.0 else it.toDouble() }
        }.getOrNull()

        val mem = runCatching {
            val m = File("/proc/meminfo").readLines().associate {
                val p = it.split(":")
                p[0].trim() to (p.getOrNull(1)?.trim()?.removeSuffix(" kB")?.trim()?.toLongOrNull() ?: 0L)
            }
            val total = m["MemTotal"] ?: 0L
            val avail = m["MemAvailable"] ?: m["MemFree"] ?: 0L
            Pair((total - avail) / 1024, total / 1024)
        }.getOrNull() ?: Pair(0L, 0L)
        val ramPct = if (mem.second > 0) (mem.first * 100 / mem.second).toInt() else 0

        val loadJson = load.joinToString(",") { "\"$it\"" }
        synchronized(lock) {
            push(cpuHist, overall); push(ramHist, ramPct); push(gpuHist, gpuPct.coerceAtLeast(0))
            latestFields = """"cpu":$overall,"cores":$cores,"load":[$loadJson],"freqMhz":$freqMhz,""" +
                """"gpu":${if (gpuPct >= 0) gpuPct else "null"},"gpuMhz":$gpuMhz,""" +
                """"tempC":${tempC ?: "null"},"memUsedMb":${mem.first},"memTotalMb":${mem.second}"""
        }
    }
}
