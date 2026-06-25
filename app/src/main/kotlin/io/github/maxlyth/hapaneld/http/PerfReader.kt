package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.Su
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.util.HelperClient
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
    private const val ACTIVE_MS = 30_000L // sample only within this window of the last page view

    private val lock = Any()
    private var prevStat: List<LongArray>? = null
    private val cpuHist = ArrayDeque<Int>()
    private val ramHist = ArrayDeque<Int>()
    private val gpuHist = ArrayDeque<Int>()
    @Volatile private var latestFields = """"cpu":0,"cores":[],"load":[],"freqMhz":[],"freqMaxMhz":0,"gpu":null,"gpuMhz":0,"tempC":null,"memUsedMb":0,"memTotalMb":0"""

    // Top-5 processes by CPU (from `dumpsys cpuinfo`) — needs root, so probed once and sampled on a
    // slower cadence than the 2s chart. Lets a user confirm the dashboard app dominates and spot
    // parasite processes (e.g. a leftover vendor gateway). "null" when no root / unavailable.
    @Volatile private var topJson = "null"
    // Dashboard rendering jank from `dumpsys gfxinfo <pkg>` (root). Quick "is there a problem" signal;
    // the deeper "why" is the 1-click DevTools relay. Target reuses the dashboard_package config.
    @Volatile var dashboardPkg: String = ""
    @Volatile private var renderJson = "null"
    private val stutterHist = ArrayDeque<Int>()   // CrRendererMain %-of-one-core per render window
    private var prevRenderJiffies = HashMap<Int, Long>() // renderer pid -> CrRendererMain utime+stime
    private var prevRenderAt = 0L
    private var rootOk = false
    // Sandbox panels (appCanSu=false) are SELinux-denied proc_stat/proc_loadavg/thermal/other-pid stat,
    // so the whole sampler reads come from the root daemon's PERFDUMP instead of direct /proc.
    private var daemonMode = false
    private var tickCount = 0
    private var prevTopTotal = 0L                 // /proc/stat aggregate jiffies at last top sample
    private var prevProc = HashMap<Int, Long>()   // pid -> utime+stime jiffies at last top sample

    // Master switch (from config) + page-view gate. Instrumentation is the tool, not a tax: it must
    // not be the panel's biggest CPU consumer 24/7. So sampling runs only when [enabled] AND the info
    // page has been fetched within [ACTIVE_MS]; otherwise the loop just compares a timestamp and sleeps.
    @Volatile var enabled = true
    @Volatile private var lastAccessAt = 0L

    /** Mark the perf page as being viewed; sampling stays live for [ACTIVE_MS] after the last call. */
    fun touch() { lastAccessAt = System.currentTimeMillis() }

    /** Start the sampling loop on [scope] (idempotent enough for a single service lifetime). */
    fun start(scope: CoroutineScope) {
        scope.launch {
            rootOk = runCatching { Su.available() }.getOrDefault(false)
            daemonMode = runCatching { !DeviceProfile.detect().appCanSu }.getOrDefault(false)
            while (isActive) {
                if (enabled && System.currentTimeMillis() - lastAccessAt < ACTIVE_MS) {
                    runCatching { tick() }
                } else {
                    resetBaselines() // re-baseline so the first sample after waking isn't a bogus delta
                }
                delay(INTERVAL_MS)
            }
        }
    }

    /** Clear delta baselines while idle so the next active tick measures a fresh interval, not a huge gap. */
    private fun resetBaselines() {
        prevStat = null; prevTopTotal = 0L
        prevProc = HashMap(); prevRenderJiffies = HashMap(); prevRenderAt = 0L
    }

    /** Latest sample + history FIFO + top-5 procs + render jank, as JSON, for `GET /perf`. */
    fun json(): String = synchronized(lock) {
        """{"enabled":$enabled,$latestFields,"top":$topJson,"render":$renderJson,"hist":{"cpu":${cpuHist.toList()},"ram":${ramHist.toList()},"gpu":${gpuHist.toList()}}}"""
    }

    /** Process names (full cmdlines) of the latest top-by-CPU sample, most-active first — for the tame
     *  picker's "using the most CPU" group. Empty until the sampler has produced a ranking. */
    fun topNames(): List<String> = synchronized(lock) {
        Regex(""""name":"(.*?)"""").findAll(topJson).map { it.groupValues[1] }.toList()
    }

    /**
     * Dashboard responsiveness. PRIMARY = the WebView renderer's main-thread CPU (`CrRendererMain`,
     * via /proc/<renderer>/task/<tid>/stat) as %-of-one-core: this is the thread the HA frontend
     * processes the WebSocket state firehose on, so it saturates (~100%) when event handling falls
     * behind *even with zero rendering* — the common no-video overload that `dumpsys gfxinfo` jank
     * misses entirely. gfxinfo jank is kept as a SECONDARY "rendering load" field (only meaningful with
     * video/animation, e.g. a camera card). Needs root. HZ assumed 100 (Android default).
     */
    private fun sampleRender() {
        // Primary: busiest CrRendererMain %-of-one-core across WebView renderer processes (covers a
        // Companion *or* a browser dashboard). One su call emits "<pid> <stat>" per renderer main thread.
        val cmd = "ps -A -o PID,NAME 2>/dev/null | grep -i sandboxe | while read pid name; do " +
            "for t in /proc/\$pid/task/*; do c=\$(cat \$t/comm 2>/dev/null); " +
            "[ \"\$c\" = CrRendererMain ] && echo \"\$pid \$(cat \$t/stat 2>/dev/null)\"; done; done; true"
        val out = Su.runOutput(cmd)
        val now = System.currentTimeMillis()
        val cur = HashMap<Int, Long>()
        var mainPct = -1.0
        if (out != null) for (line in out.lineSequence()) {
            val sp = line.indexOf(' ')
            if (sp <= 0) continue
            val pid = line.substring(0, sp).trim().toIntOrNull() ?: continue
            val rest = line.substring(sp + 1).substringAfter(") ", "").split(' ') // after comm: state(0)..utime(11),stime(12)
            val ut = rest.getOrNull(11)?.toLongOrNull() ?: continue
            val st = rest.getOrNull(12)?.toLongOrNull() ?: 0
            val j = ut + st
            cur[pid] = j
            val prev = prevRenderJiffies[pid]
            if (prev != null && prevRenderAt > 0L) {
                val dt = (now - prevRenderAt) / 1000.0
                if (dt > 0) { val p = (j - prev) / dt; if (p > mainPct) mainPct = p } // HZ=100 -> jiffies/s == %-of-core
            }
        }
        prevRenderJiffies = cur
        prevRenderAt = now
        if (mainPct < 0) { // first sample, or no Chromium renderer running
            synchronized(lock) { renderJson = "{\"status\":\"no-renderer\",\"hist\":${stutterHist.toList()}}" }
            return
        }
        val pct1 = Math.round(mainPct.coerceIn(0.0, 100.0) * 10) / 10.0
        val verdict = if (pct1 < 50) "smooth" else if (pct1 < 85) "occasional" else "janky"

        // Secondary: gfxinfo jank (rendering load — only meaningful with video/animation). Optional.
        var jankFields = ""
        val pkg = dashboardPkg
        if (pkg.isNotBlank()) {
            val g = Su.runOutput("dumpsys gfxinfo $pkg; dumpsys gfxinfo $pkg reset >/dev/null 2>&1; true")
            if (g != null) {
                val tot = Regex("""Total frames rendered:\s*(\d+)""").find(g)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                if (tot > 0) {
                    val janky = Regex("""Janky frames:\s*(\d+)""").find(g)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                    val p99 = Regex("""99th percentile:\s*(\d+)ms""").find(g)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    jankFields = ",\"jankPct\":${Math.round(janky * 1000.0 / tot) / 10.0},\"p99\":$p99"
                }
            }
        }
        synchronized(lock) {
            push(stutterHist, Math.round(pct1).toInt())
            renderJson = "{\"pkg\":\"${pkg.ifBlank { "dashboard" }}\",\"mainPct\":$pct1,\"verdict\":\"$verdict\"" +
                jankFields + ",\"hist\":${stutterHist.toList()}}"
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

    // --- daemon-sourced sampling (sandbox panels) -------------------------------------------------
    private class Dump(
        val stat: List<LongArray>,
        val load: List<String>,
        val tempMilli: Long,
        val gpuRaw: String?,
        val proc: List<Triple<Int, Long, String>>,  // pid, utime+stime jiffies, comm
        val rend: Map<Int, Long>                     // renderer pid -> CrRendererMain jiffies
    )

    /** Fetch + parse one PERFDUMP from the root daemon. Null if the daemon is unreachable. */
    private fun fetchDump(): Dump? {
        val raw = HelperClient.sendBytes("PERFDUMP")?.toString(Charsets.UTF_8) ?: return null
        val stat = ArrayList<LongArray>()
        val proc = ArrayList<Triple<Int, Long, String>>()
        val rend = HashMap<Int, Long>()
        var load: List<String> = emptyList()
        var tempMilli = -1L
        var gpuRaw: String? = null
        var section = ""
        for (line in raw.lineSequence()) {
            when {
                line == "@STAT" -> { section = "stat"; continue }
                line == "@PROC" -> { section = "proc"; continue }
                line == "@REND" -> { section = "rend"; continue }
                line == "@END" -> break
                line.startsWith("@LOAD ") -> { load = line.removePrefix("@LOAD ").trim().split(" ").take(3); continue }
                line.startsWith("@TEMP ") -> { tempMilli = line.removePrefix("@TEMP ").trim().toLongOrNull() ?: -1L; continue }
                line.startsWith("@GPU ") -> { gpuRaw = line.removePrefix("@GPU ").trim().takeIf { it != "-" }; continue }
            }
            when (section) {
                "stat" -> if (line.startsWith("cpu"))
                    stat.add(line.trim().split(Regex("\\s+")).drop(1).map { it.toLongOrNull() ?: 0L }.toLongArray())
                "proc" -> line.split('\t').let { t ->
                    val pid = t.getOrNull(0)?.toIntOrNull(); val j = t.getOrNull(1)?.toLongOrNull()
                    if (pid != null && j != null) proc.add(Triple(pid, j, t.getOrElse(2) { "" }))
                }
                "rend" -> line.split('\t').let { t ->
                    val pid = t.getOrNull(0)?.toIntOrNull(); val j = t.getOrNull(1)?.toLongOrNull()
                    if (pid != null && j != null) rend[pid] = j
                }
            }
        }
        return Dump(stat, load, tempMilli, gpuRaw, proc, rend)
    }

    /** Top-5 by CPU from the daemon's process table (comm only — truncated to 16 chars, no cmdline). */
    private fun sampleTopDump(dump: Dump) {
        val total = dump.stat.firstOrNull()?.sum() ?: return       // "cpu" aggregate line
        val cur = HashMap<Int, Long>()
        val comm = HashMap<Int, String>()
        for ((pid, j, c) in dump.proc) { cur[pid] = j; comm[pid] = c }
        val dTotal = total - prevTopTotal
        val ranked = if (prevTopTotal != 0L && dTotal > 0) {
            cur.entries.mapNotNull { (pid, j) -> prevProc[pid]?.let { pid to (j - it) } }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(5)
        } else emptyList()
        prevTopTotal = total
        prevProc = cur
        if (ranked.isNotEmpty()) {
            val json = ranked.joinToString(",", "[", "]") { (pid, dj) ->
                val pctv = Math.round(dj * 1000.0 / dTotal) / 10.0
                val nm = (comm[pid] ?: pid.toString()).replace("\\", "").replace("\"", "")
                """{"name":"$nm","cpu":$pctv}"""
            }
            synchronized(lock) { topJson = json }
        }
    }

    /** Dashboard responsiveness from the daemon's CrRendererMain thread jiffies (primary metric; the
     *  gfxinfo secondary needs dumpsys/su, so it's omitted on sandbox panels). */
    private fun sampleRenderDump(dump: Dump) {
        val now = System.currentTimeMillis()
        var mainPct = -1.0
        for ((pid, j) in dump.rend) {
            val prev = prevRenderJiffies[pid]
            if (prev != null && prevRenderAt > 0L) {
                val dt = (now - prevRenderAt) / 1000.0
                if (dt > 0) { val p = (j - prev) / dt; if (p > mainPct) mainPct = p } // HZ=100 -> jiffies/s == %-of-core
            }
        }
        prevRenderJiffies = HashMap(dump.rend)
        prevRenderAt = now
        if (mainPct < 0) {
            synchronized(lock) { renderJson = "{\"status\":\"no-renderer\",\"hist\":${stutterHist.toList()}}" }
            return
        }
        val pct1 = Math.round(mainPct.coerceIn(0.0, 100.0) * 10) / 10.0
        val verdict = if (pct1 < 50) "smooth" else if (pct1 < 85) "occasional" else "janky"
        synchronized(lock) {
            push(stutterHist, Math.round(pct1).toInt())
            renderJson = "{\"pkg\":\"dashboard\",\"mainPct\":$pct1,\"verdict\":\"$verdict\",\"hist\":${stutterHist.toList()}}"
        }
    }

    private fun readStat(): List<LongArray> =
        File("/proc/stat").readLines()
            .filter { it.startsWith("cpu") }
            .map { line -> line.trim().split(Regex("\\s+")).drop(1).map { it.toLongOrNull() ?: 0L }.toLongArray() }

    private fun tick() {
        // Sandbox panels: one PERFDUMP from the root daemon supplies everything the app can't read.
        val dump = if (daemonMode) runCatching { fetchDump() }.getOrNull() else null
        if (daemonMode && dump == null) { resetBaselines(); return } // daemon unreachable — no bogus deltas
        when {
            daemonMode -> when (tickCount % 3) {   // dump is non-null here
                0 -> runCatching { sampleTopDump(dump!!) }
                1 -> runCatching { sampleRenderDump(dump!!) }
            }
            rootOk -> when (tickCount % 3) {        // spread the su calls across ticks (~6s each)
                0 -> runCatching { sampleTop() }
                1 -> runCatching { sampleRender() }
            }
        }
        tickCount++
        val stat = if (daemonMode) dump!!.stat else runCatching { readStat() }.getOrNull()
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

        val load = if (daemonMode) dump!!.load
            else runCatching { File("/proc/loadavg").readText().trim().split(" ").take(3) }.getOrNull() ?: emptyList()
        val freqMhz = runCatching {
            File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu[0-9]+")) }
                ?.sortedBy { it.name }
                ?.map { File(it, "cpufreq/scaling_cur_freq") }
                ?.map { if (it.exists()) (it.readText().trim().toLongOrNull() ?: 0L) / 1000 else 0L }
                ?: emptyList()
        }.getOrNull() ?: emptyList()
        // Hardware max clock (static) — current vs this shows DVFS headroom / thermal throttling.
        val freqMaxMhz = runCatching {
            File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu[0-9]+")) }
                ?.mapNotNull { File(it, "cpufreq/cpuinfo_max_freq").takeIf { x -> x.exists() }?.readText()?.trim()?.toLongOrNull() }
                ?.maxOrNull()?.div(1000) ?: 0L
        }.getOrNull() ?: 0L

        val gpuRaw = if (daemonMode) dump!!.gpuRaw
            else runCatching {
                File("/sys/class/devfreq").listFiles { f -> f.name.contains("gpu") }
                    ?.firstNotNullOfOrNull { d -> File(d, "load").takeIf { it.exists() }?.readText()?.trim() }
            }.getOrNull()
        var gpuPct = -1; var gpuMhz = 0L
        gpuRaw?.let { raw ->
            val at = raw.indexOf('@')
            gpuPct = (if (at >= 0) raw.substring(0, at) else raw).trim().toIntOrNull() ?: -1
            if (at >= 0) gpuMhz = raw.substring(at + 1).removeSuffix("Hz").trim().toLongOrNull()?.div(1_000_000) ?: 0L
        }

        val tempC = if (daemonMode) dump!!.tempMilli.takeIf { it >= 0 }?.let { if (it > 1000) it / 1000.0 else it.toDouble() }
            else runCatching {
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
            latestFields = """"cpu":$overall,"cores":$cores,"load":[$loadJson],"freqMhz":$freqMhz,"freqMaxMhz":$freqMaxMhz,""" +
                """"gpu":${if (gpuPct >= 0) gpuPct else "null"},"gpuMhz":$gpuMhz,""" +
                """"tempC":${tempC ?: "null"},"memUsedMb":${mem.first},"memTotalMb":${mem.second}"""
        }
    }
}
