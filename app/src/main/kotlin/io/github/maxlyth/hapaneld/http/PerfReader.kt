package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.Su
import io.github.maxlyth.hapaneld.metrics.MetricRegistry
import io.github.maxlyth.hapaneld.metrics.MetricSample
import io.github.maxlyth.hapaneld.metrics.PanelMetrics
import io.github.maxlyth.hapaneld.metrics.PerfDump
import io.github.maxlyth.hapaneld.metrics.RamRingSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background performance sampler for the info page. A coroutine ticks every [INTERVAL_MS], reads the
 * system telemetry union from the shared [PanelMetrics] reader (CPU/GPU/RAM/temp/load/freq, with its
 * direct→daemon fallback), and keeps the last [MAX] samples in an in-RAM FIFO ([RamRingSink]) so
 * `GET /perf` returns the latest values **plus** the history — the chart is populated immediately on page
 * load and the series survives reloads (lost only on app restart).
 *
 * The heavy per-process metrics that only this page needs — top-5 by CPU and dashboard render jank — stay
 * PerfReader-local (their deltas + su cadence are not shared with Diagnostics). On sandbox panels those
 * come free from the shared reader's PERFDUMP (exposed as [io.github.maxlyth.hapaneld.metrics.Snapshot.dump]),
 * so no second dump is fetched; on rooted panels they're separate `su` calls spread across ticks.
 *
 * CPU % is a delta the reader computes (so the first tick has no value). GPU is Rockchip Mali devfreq load
 * ("<load>@<freq>Hz"); absent on panels without it.
 */
object PerfReader {
    private const val MAX = 120          // ~4 min at 2s
    private const val INTERVAL_MS = 2000L
    private const val ACTIVE_MS = 30_000L // sample only within this window of the last page view

    // Internal history keys (also the future store's schema — see MetricRegistry). The /perf JSON still
    // exposes them as the stable "cpu"/"ram"/"gpu" fields the chart expects.
    private val CPU_KEY = MetricRegistry.CPU.key
    private val RAM_KEY = MetricRegistry.MEM.key
    private val GPU_KEY = MetricRegistry.GPU_LOAD.key

    private val lock = Any()
    // History retention behind the MetricSink seam (a durable store swaps in without touching this class).
    private val sink = RamRingSink(MAX)
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
    private var tickCount = 0
    private var prevTopTotal = 0L                 // /proc/stat aggregate jiffies at last top sample
    private var prevProc = HashMap<Int, Long>()   // pid -> utime+stime jiffies at last top sample
    private var prevSelf = 0L                     // own jiffies incl. reaped children at last top sample
    private val nameCache = HashMap<Int, String>() // pid -> cmdline (fetched once; pids recycle rarely)

    // Page-view gate. Instrumentation is the tool, not a tax: it must not be the panel's biggest CPU
    // consumer 24/7. So sampling runs only while the info page has been fetched within [ACTIVE_MS];
    // otherwise the loop just compares a timestamp and sleeps. [enabled] is always true (the old master
    // switch was removed — the page-view gate is the sole cost control).
    @Volatile var enabled = true
    @Volatile private var lastAccessAt = 0L

    /** Mark the perf page as being viewed; sampling stays live for [ACTIVE_MS] after the last call. */
    fun touch() { lastAccessAt = System.currentTimeMillis() }

    /** Start the sampling loop on [scope] (idempotent enough for a single service lifetime). */
    fun start(scope: CoroutineScope) {
        scope.launch {
            rootOk = runCatching { Su.available() }.getOrDefault(false)
            while (isActive) {
                if (enabled && System.currentTimeMillis() - lastAccessAt < ACTIVE_MS) {
                    runCatching { tick() }
                } else {
                    resetLocalBaselines() // re-baseline the top/render deltas so the first sample after waking isn't a huge gap
                }
                delay(INTERVAL_MS)
            }
        }
    }

    /** Clear PerfReader's OWN top/render delta baselines while idle. The shared /proc/stat baseline lives
     *  in [PanelMetrics] and is deliberately NOT reset here (a stale prev just yields one valid
     *  longer-window CPU% on the next read, never a bogus delta). */
    private fun resetLocalBaselines() {
        prevTopTotal = 0L; prevSelf = 0L
        prevProc = HashMap(); prevRenderJiffies = HashMap(); prevRenderAt = 0L
        nameCache.clear()
    }

    /** Latest sample + history FIFO + top-5 procs + render jank, as JSON, for `GET /perf`. */
    fun json(): String = synchronized(lock) {
        """{"enabled":$enabled,$latestFields,"top":$topJson,"render":$renderJson,"hist":{"cpu":${histInts(CPU_KEY)},"ram":${histInts(RAM_KEY)},"gpu":${histInts(GPU_KEY)}}}"""
    }

    private fun histInts(key: String): List<Int> = sink.history(key).map { it.num?.toInt() ?: 0 }

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
        // Shell `read` builtins instead of a `cat` per thread: the old form forked ~2 processes per
        // renderer thread every sample — dozens of execs on a slow SoC — for what a redirect does free.
        val cmd = "ps -A -o PID,NAME 2>/dev/null | grep -i sandboxe | while read pid name; do " +
            "for t in /proc/\$pid/task/*; do IFS= read -r c < \$t/comm 2>/dev/null || continue; " +
            "if [ \"\$c\" = CrRendererMain ]; then IFS= read -r s < \$t/stat 2>/dev/null && echo \"\$pid \$s\"; fi; done; done; true"
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
        publishTop(cur, comm, total, resolveFullNames = true)
    }

    /**
     * Rank + publish the top-5. ha-paneld's OWN process is excluded from the ranking and shown as a
     * separate, always-last row ("ha-paneld + sampling") computed from `/proc/self/stat` INCLUDING
     * reaped children (cutime/cstime) — the su/dumpsys/cat probes this page spawns are reaped by us, so
     * that row is the honest total cost of the app *plus its measurement overhead*, kept out of the
     * workload ranking it would otherwise pollute (the observer isn't the panel's workload).
     */
    private fun publishTop(cur: Map<Int, Long>, comm: Map<Int, String>, total: Long, resolveFullNames: Boolean) {
        val myPid = android.os.Process.myPid()
        val dTotal = total - prevTopTotal
        val ranked = if (prevTopTotal != 0L && dTotal > 0) {
            cur.entries.mapNotNull { (pid, j) -> prevProc[pid]?.let { pid to (j - it) } }
                .filter { it.second > 0 && it.first != myPid }.sortedByDescending { it.second }.take(5)
        } else emptyList()
        val selfNow = runCatching { java.io.File("/proc/self/stat").readText() }.getOrNull()?.let { selfJiffiesOf(it) }
        val selfPct = if (selfNow != null && prevSelf > 0L && dTotal > 0) {
            Math.round((selfNow - prevSelf) * 1000.0 / dTotal) / 10.0
        } else null
        prevTopTotal = total
        prevProc = HashMap(cur)
        selfNow?.let { prevSelf = it }

        if (ranked.isEmpty() && selfPct == null) return
        // Full cmdlines only for ranked pids we haven't resolved before — usually zero extra su calls.
        if (resolveFullNames) {
            val missing = ranked.map { it.first }.filter { it !in nameCache }
            if (missing.isNotEmpty()) fullNames(missing).forEach { (p, n) -> nameCache[p] = n }
        }
        val rows = ranked.map { (pid, dj) ->
            val pct = Math.round(dj * 1000.0 / dTotal) / 10.0 // % of total capacity, 1dp, dot-decimal
            val nm = trimProcName((nameCache[pid] ?: comm[pid] ?: pid.toString()).replace("\\", "").replace("\"", ""))
            """{"name":"$nm","cpu":$pct}"""
        }.toMutableList()
        selfPct?.let { rows += """{"name":"ha-paneld + sampling","cpu":$it,"self":true}""" }
        synchronized(lock) { topJson = rows.joinToString(",", "[", "]") }
    }

    /** Own-process jiffies from a `/proc/self/stat` line: utime+stime PLUS cutime+cstime (reaped
     *  children — the measurement probes). Null on a malformed line. */
    internal fun selfJiffiesOf(statLine: String): Long? {
        val rp = statLine.lastIndexOf(')')
        if (rp < 0 || rp + 2 > statLine.length) return null
        val rest = statLine.substring(rp + 2).split(' ')
        val u = rest.getOrNull(11)?.toLongOrNull() ?: return null
        val s = rest.getOrNull(12)?.toLongOrNull() ?: return null
        val cu = rest.getOrNull(13)?.toLongOrNull() ?: 0L
        val cs = rest.getOrNull(14)?.toLongOrNull() ?: 0L
        return u + s + cu + cs
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

    /** Shorten Android "package:process:classname" cmdlines to just the package component — the extra
     *  segments (Chromium sandbox class names, sub-process suffixes) add noise without aiding diagnosis. */
    private fun trimProcName(raw: String): String =
        if (raw.count { it == ':' } >= 2) raw.substringBefore(':') else raw

    private fun push(q: ArrayDeque<Int>, v: Int) {
        q.addLast(v)
        while (q.size > MAX) q.removeFirst()
    }

    // --- daemon-sourced top/render (sandbox panels), from the shared reader's PERFDUMP ---------------

    /** Top-5 by CPU from the daemon's process table (comm only — truncated to 16 chars, no cmdline). */
    private fun sampleTopDump(dump: PerfDump) {
        val total = dump.stat.firstOrNull()?.sum() ?: return       // "cpu" aggregate line
        val cur = HashMap<Int, Long>()
        val comm = HashMap<Int, String>()
        for ((pid, j, c) in dump.proc) { cur[pid] = j; comm[pid] = c }
        publishTop(cur, comm, total, resolveFullNames = false)     // dump comms suffice; no su available
    }

    /** Dashboard responsiveness from the daemon's CrRendererMain thread jiffies (primary metric; the
     *  gfxinfo secondary needs dumpsys/su, so it's omitted on sandbox panels). */
    private fun sampleRenderDump(dump: PerfDump) {
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

    private fun tick() {
        val snap = PanelMetrics.shared.systemSnapshot()
        // Top-5 + render jank (PerfReader-only, spread across ticks). Prefer the shared reader's PERFDUMP
        // tables when it fetched a dump this tick (sandbox panels); else the direct su path on rooted panels.
        val dump = snap.dump
        // ~10s cadence for the heavy per-process samplers (was ~6s): they fork su probes and parse
        // every process's stat, which on a slow SoC was itself a visible slice of panel CPU. The 2s
        // chart above is untouched — only top-5/render slow down.
        when {
            dump != null -> when (tickCount % 5) {
                0 -> runCatching { sampleTopDump(dump) }
                2 -> runCatching { sampleRenderDump(dump) }
            }
            rootOk -> when (tickCount % 5) {           // spread the su calls across ticks (~10s each)
                0 -> runCatching { sampleTop() }
                2 -> runCatching { sampleRender() }
            }
        }
        tickCount++

        val overall = snap.cpuOverall ?: return // first tick (no delta yet) / CPU source unavailable

        val cores = snap.cpuCores
        val loadJson = snap.loadavg.joinToString(",") { "\"$it\"" }
        val ramPct = if (snap.memTotalMb > 0) (snap.memUsedMb * 100 / snap.memTotalMb).toInt() else 0
        val gpuPct = snap.gpuPct
        val now = snap.ts
        synchronized(lock) {
            sink.record(MetricSample.num(CPU_KEY, overall.toDouble(), now, "%"))
            sink.record(MetricSample.num(RAM_KEY, ramPct.toDouble(), now, "%"))
            sink.record(MetricSample.num(GPU_KEY, gpuPct.coerceAtLeast(0).toDouble(), now, "%"))
            latestFields = """"cpu":$overall,"cores":$cores,"load":[$loadJson],"freqMhz":${snap.freqCurMhz},"freqMaxMhz":${snap.freqMaxMhz},""" +
                """"gpu":${if (gpuPct >= 0) gpuPct else "null"},"gpuMhz":${snap.gpuMhz},""" +
                """"tempC":${snap.socTempC ?: "null"},"memUsedMb":${snap.memUsedMb},"memTotalMb":${snap.memTotalMb}"""
        }
    }
}
