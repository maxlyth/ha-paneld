package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.Su
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

    // Top-5 processes by CPU (from `dumpsys cpuinfo`) — needs root, so probed once and sampled on a
    // slower cadence than the 2s chart. Lets a user confirm the dashboard app dominates and spot
    // parasite processes (e.g. a leftover vendor gateway). "null" when no root / unavailable.
    @Volatile private var topJson = "null"
    // Dashboard rendering jank from `dumpsys gfxinfo <pkg>` (root). Quick "is there a problem" signal;
    // the deeper "why" is the 1-click DevTools relay. Target reuses the dashboard_package config.
    @Volatile var dashboardPkg: String = ""
    @Volatile private var renderJson = "null"
    private var rootOk = false
    private var tickCount = 0
    private var prevTopTotal = 0L                 // /proc/stat aggregate jiffies at last top sample
    private var prevProc = HashMap<Int, Long>()   // pid -> utime+stime jiffies at last top sample

    /** Start the sampling loop on [scope] (idempotent enough for a single service lifetime). */
    fun start(scope: CoroutineScope) {
        scope.launch {
            rootOk = runCatching { Su.available() }.getOrDefault(false)
            while (isActive) {
                runCatching { tick() }
                delay(INTERVAL_MS)
            }
        }
    }

    /** Latest sample + history FIFO + top-5 procs + render jank, as JSON, for `GET /perf`. */
    fun json(): String = synchronized(lock) {
        """{$latestFields,"top":$topJson,"render":$renderJson,"hist":{"cpu":${cpuHist.toList()},"ram":${ramHist.toList()},"gpu":${gpuHist.toList()}}}"""
    }

    /**
     * Dashboard rendering jank via `dumpsys gfxinfo <pkg>` (root) — proven to return real per-window
     * data (jank %, frame-time percentiles, stall counts). We read then `reset` so each sample is the
     * jank over the ~interval, not lifetime. Coarse (host-app frame delivery, not the web page's
     * internal long-tasks) — the 1-click DevTools relay is the deep tool. "noconfig"/"null"/idle handled.
     */
    private fun sampleRender() {
        val pkg = dashboardPkg
        if (pkg.isBlank()) { synchronized(lock) { renderJson = "\"noconfig\"" }; return }
        val out = Su.runOutput("dumpsys gfxinfo $pkg; dumpsys gfxinfo $pkg reset >/dev/null 2>&1; true") ?: return
        fun lng(re: String) = Regex(re).find(out)?.groupValues?.get(1)?.toLongOrNull()
        fun pct(p: Int) = Regex("$p" + """th percentile:\s*(\d+)ms""").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = lng("""Total frames rendered:\s*(\d+)""") ?: return
        if (total <= 0) { synchronized(lock) { renderJson = "{\"pkg\":\"$pkg\",\"idle\":true}" }; return }
        val janky = lng("""Janky frames:\s*(\d+)""") ?: 0
        val jankPct = Math.round(janky * 1000.0 / total) / 10.0
        val verdict = if (jankPct < 5) "smooth" else if (jankPct < 15) "occasional" else "janky"
        synchronized(lock) {
            renderJson = "{\"pkg\":\"$pkg\",\"frames\":$total,\"jankPct\":$jankPct,\"verdict\":\"$verdict\"," +
                "\"p50\":${pct(50)},\"p95\":${pct(95)},\"p99\":${pct(99)}," +
                "\"missedVsync\":${lng("""Number Missed Vsync:\s*(\d+)""") ?: 0}," +
                "\"slowUi\":${lng("""Number Slow UI thread:\s*(\d+)""") ?: 0}}"
        }
    }

    /**
     * Top-5 processes by CPU, computed from `/proc/[pid]/stat` deltas over the sample interval (what
     * `top` does) — `dumpsys cpuinfo` was tried first but returns a cached snapshot that only the
     * system refreshes (minutes apart), so it never advances. Needs root to read other pids' stat
     * (`/proc` is `hidepid`). CPU is expressed as % of total capacity (sums to <=100 across procs,
     * consistent with the overall CPU figure). Full names come from `/proc/<pid>/cmdline`.
     */
    private fun sampleTop() {
        // `; true` so a vanished-pid `cat` (non-zero) doesn't null the whole capture.
        val out = Su.runOutput("cat /proc/stat; echo @@; cat /proc/[0-9]*/stat 2>/dev/null; true") ?: return
        val parts = out.split("@@")
        if (parts.size < 2) return
        val cpuLine = parts[0].lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return
        val total = cpuLine.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }.sum()

        val cur = HashMap<Int, Long>()
        val comm = HashMap<Int, String>()
        for (line in parts[1].lineSequence()) {
            val lp = line.indexOf('('); val rp = line.lastIndexOf(')')
            if (lp <= 0 || rp < lp) continue
            val pid = line.substring(0, lp).trim().toIntOrNull() ?: continue
            val rest = line.substring(rp + 2).split(' ') // rest[0]=state(field3); utime=field14=rest[11], stime=rest[12]
            val utime = rest.getOrNull(11)?.toLongOrNull() ?: continue
            val stime = rest.getOrNull(12)?.toLongOrNull() ?: continue
            cur[pid] = utime + stime
            comm[pid] = line.substring(lp + 1, rp)
        }

        val dTotal = total - prevTopTotal
        val ranked = if (prevTopTotal != 0L && dTotal > 0) {
            cur.entries.mapNotNull { (pid, j) -> prevProc[pid]?.let { pid to (j - it) } }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(5)
        } else emptyList()
        prevTopTotal = total
        prevProc = cur

        if (ranked.isNotEmpty()) {
            val names = fullNames(ranked.map { it.first })
            val json = ranked.joinToString(",", "[", "]") { (pid, dj) ->
                val pct = Math.round(dj * 1000.0 / dTotal) / 10.0 // % of total capacity, 1dp, dot-decimal
                val nm = (names[pid] ?: comm[pid] ?: pid.toString()).replace("\\", "").replace("\"", "")
                """{"name":"$nm","cpu":$pct}"""
            }
            synchronized(lock) { topJson = json }
        }
    }

    /** Full process names from `/proc/<pid>/cmdline` (comm is truncated to 16 chars) for the top pids. */
    private fun fullNames(pids: List<Int>): Map<Int, String> {
        if (pids.isEmpty()) return emptyMap()
        val cmd = "for p in ${pids.joinToString(" ")}; do printf '%s\\t' \"\$p\"; " +
            "cat /proc/\$p/cmdline 2>/dev/null; printf '\\n'; done; true"
        val out = Su.runOutput(cmd) ?: return emptyMap()
        val map = HashMap<Int, String>()
        for (line in out.lineSequence()) {
            val tab = line.indexOf('\t'); if (tab <= 0) continue
            val pid = line.substring(0, tab).trim().toIntOrNull() ?: continue
            val name = line.substring(tab + 1).replace('\u0000', ' ').trim().substringBefore(' ')
            if (name.isNotEmpty()) map[pid] = name
        }
        return map
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
        if (rootOk) when (tickCount % 3) {     // spread the su calls across ticks (~6s each)
            0 -> runCatching { sampleTop() }
            1 -> runCatching { sampleRender() }
        }
        tickCount++
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
