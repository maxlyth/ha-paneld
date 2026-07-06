package io.github.maxlyth.hapaneld.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's coordination: two distinct caches (per-metric source resolution + snapshot freshness), a
 * single shared CPU-delta baseline, and one PERFDUMP per tick shared across daemon-resolved metrics. The
 * fake source counts each read so the tests assert on *which* source was hit, not just the value.
 *
 * `freshMs = 0` in the resolution tests so every `systemSnapshot()` re-enters `readCoherent` — otherwise
 * the value-freshness cache would return a cached snapshot and the resolution logic would never run.
 */
class PanelMetricsTest {

    /** Counting, fully-settable [MetricSource]; every field defaults to "unavailable". */
    private class FakeSource : MetricSource {
        var stat: String? = null
        var meminfo: String? = "MemTotal: 1000 kB\nMemFree: 100 kB\nMemAvailable: 400 kB\n"
        var loadavg: String? = null
        var thermal: List<Long> = emptyList()
        var gpu: String? = null
        var freqCur: List<Long> = emptyList()
        var freqMax: Long = 0
        var ip: String? = null
        var selinux: String? = null
        var dump: String? = null

        var statCalls = 0
        var dumpCalls = 0

        override fun perfDump(): String? { dumpCalls++; return dump }
        override fun statText(): String? { statCalls++; return stat }
        override fun meminfoText(): String? = meminfo
        override fun loadavgText(): String? = loadavg
        override fun thermalMilliValues(): List<Long> = thermal
        override fun gpuLoadRaw(): String? = gpu
        override fun cpuFreqCurMhz(): List<Long> = freqCur
        override fun cpuFreqMaxMhz(): Long = freqMax
        override fun localIp(): String? = ip
        override fun selinuxEnforce(): String? = selinux
        override fun cpuGovernor(): String? = null
        override fun cpuAvailableGovernors(): String? = null
    }

    private var now = 0L
    private fun reader(src: FakeSource, freshMs: Long = 0L, unavailRetryMs: Long = 1000L) =
        PanelMetrics(src, clock = { now }, freshMs = freshMs, unavailRetryMs = unavailRetryMs)

    private fun statStr(user: Long, idle: Long) = "cpu $user 0 0 $idle 0 0 0 0\n"

    // A full PERFDUMP so daemon-resolved metrics have real values.
    private val dumpText = "@STAT\ncpu 100 0 100 800 0 0 0 0\ncpu0 50 0 50 400 0 0 0 0\n" +
        "@LOAD 0.5 0.4 0.3\n@TEMP 42000\n@GPU 45@800000000Hz\n@PROC\n1234\t500\tproc\n@REND\n1234\t250\n@END\n"

    // A fully-healthy rooted panel: every direct read succeeds, so nothing ever touches the daemon.
    private fun healthyDirect() = FakeSource().apply {
        stat = statStr(0, 100); loadavg = "0.5 0.4 0.3"; thermal = listOf(42000L); gpu = "45@800000000Hz"
    }

    @Test fun resolvesDirectOnceAndDoesNotReprobeOnSuccessfulReads() {
        val src = healthyDirect()
        val r = reader(src)
        repeat(3) { now += 2000; r.systemSnapshot() }
        assertEquals("direct /proc/stat read once per tick", 3, src.statCalls)
        assertEquals("daemon never probed once DIRECT wins", 0, src.dumpCalls)
    }

    @Test fun multipleDaemonMetricsShareOnePerfdumpPerTick() {
        // All direct reads fail → cpu/temp/gpu/loadavg all resolve to the daemon; one dump serves them.
        val src = FakeSource().apply { dump = dumpText }
        val r = reader(src)
        val snap = r.systemSnapshot()
        assertEquals("exactly one PERFDUMP for all four daemon metrics", 1, src.dumpCalls)
        assertEquals(42.0, snap.socTempC!!, 0.0001)  // proves the values came from the single dump
        assertEquals(45, snap.gpuPct)
        assertEquals(listOf("0.5", "0.4", "0.3"), snap.loadavg)
        assertNotNull(snap.dump)                      // carried through for PerfReader's top/render
    }

    @Test fun readFailureReresolvesToTheDaemonAndSticksThere() {
        val src = healthyDirect()
        val r = reader(src)
        now += 2000; r.systemSnapshot()               // resolves DIRECT
        assertEquals(0, src.dumpCalls)

        // DIRECT stops working; the daemon is now up.
        src.stat = null; src.dump = dumpText
        now += 2000; r.systemSnapshot()               // winner fails → re-resolve → DAEMON wins
        val statAfterReresolve = src.statCalls
        val dumpAfterReresolve = src.dumpCalls
        assertTrue("re-resolution probed the daemon", dumpAfterReresolve >= 1)

        now += 2000; r.systemSnapshot()               // winner is now DAEMON: no more DIRECT probing
        assertEquals("DIRECT not retried once DAEMON is the sticky winner", statAfterReresolve, src.statCalls)
        assertTrue("keeps reading via the daemon", src.dumpCalls > dumpAfterReresolve)
    }

    @Test fun unavailableMetricBacksOffThenRecoversWhenItsSourceAppears() {
        val src = FakeSource()                         // no direct, no daemon → nothing resolves
        val r = reader(src, unavailRetryMs = 1000L)

        now = 0; val s0 = r.systemSnapshot()
        assertNull(s0.socTempC)
        val dumpsAtResolve = src.dumpCalls             // one probe attempt at resolution (memoized dump)
        assertTrue(dumpsAtResolve >= 1)

        now = 500; r.systemSnapshot()                  // within backoff → no re-probe at all
        assertEquals("unavailable metric does not probe during backoff", dumpsAtResolve, src.dumpCalls)

        src.dump = dumpText                            // daemon appears
        now = 1500; val s2 = r.systemSnapshot()        // backoff elapsed → retry → recovers
        assertTrue("retried after the backoff window", src.dumpCalls > dumpsAtResolve)
        assertEquals(42.0, s2.socTempC!!, 0.0001)
    }

    @Test fun freshnessCacheCoalescesReadsWithinTheWindow() {
        val src = healthyDirect()
        val r = reader(src, freshMs = 1500L)
        now = 0; r.systemSnapshot()
        assertEquals(1, src.statCalls)
        now = 1000; r.systemSnapshot()                 // within 1500 → cache hit, no re-read
        assertEquals("coalesced onto the cached snapshot", 1, src.statCalls)
        now = 2000; r.systemSnapshot()                 // past the window → re-reads
        assertEquals(2, src.statCalls)
    }

    @Test fun cpuDeltaUsesTheSingleSharedBaselineAcrossReads() {
        val src = healthyDirect()
        val r = reader(src)
        now = 0; assertNull("no baseline yet on the first read", r.systemSnapshot().cpuOverall)
        src.stat = statStr(50, 100)                    // +50 busy jiffies, idle unchanged → 100% busy delta
        now = 1000; assertEquals(100, r.systemSnapshot().cpuOverall)
    }

    @Test fun memoryReadsDirectEvenWhenTheCpuSourceIsUnavailable() {
        val src = FakeSource()                         // stat/daemon absent, but meminfo present (world-readable)
        val snap = reader(src).systemSnapshot()
        assertNull(snap.cpuOverall)
        assertEquals(60, snap.memPercent)              // 600/1000 kB
    }

    // --- store-emit hook (a): Snapshot.toSamples() produces registry-keyed normalized records ---------

    @Test fun toSamplesEmitsRegistryKeyedNormalizedRecords() {
        val snap = Snapshot(
            ts = 1000, cpuOverall = 42, cpuCores = listOf(40, 44), memUsedMb = 600, memTotalMb = 1000,
            memPercent = 60, socTempC = 42.5, gpuPct = 45, gpuMhz = 800, loadavg = listOf("0.5", "0.4", "0.3"),
            freqCurMhz = listOf(1416L), freqMaxMhz = 1800, dump = null,
        )
        val byKey = snap.toSamples().associateBy { it.key }
        assertEquals(42.0, byKey.getValue(MetricRegistry.CPU.key).num!!, 0.0)
        assertEquals("%", byKey.getValue(MetricRegistry.CPU.key).unit)
        assertEquals(1000L, byKey.getValue(MetricRegistry.CPU.key).ts)
        assertEquals(60.0, byKey.getValue(MetricRegistry.MEM.key).num!!, 0.0)
        assertEquals(600.0, byKey.getValue(MetricRegistry.MEM_USED.key).num!!, 0.0)
        assertEquals(1000.0, byKey.getValue(MetricRegistry.MEM_TOTAL.key).num!!, 0.0)
        assertEquals(42.5, byKey.getValue(MetricRegistry.SOC_TEMP.key).num!!, 0.0)
        assertEquals(45.0, byKey.getValue(MetricRegistry.GPU_LOAD.key).num!!, 0.0)
        assertEquals(800.0, byKey.getValue(MetricRegistry.GPU_FREQ.key).num!!, 0.0)
        assertEquals(0.5, byKey.getValue(MetricRegistry.LOAD1.key).num!!, 0.0)
        assertEquals(1800.0, byKey.getValue(MetricRegistry.CPU_FREQ_MAX.key).num!!, 0.0)
    }

    @Test fun toSamplesOmitsAbsentMetrics() {
        val snap = Snapshot(
            ts = 1, cpuOverall = null, cpuCores = emptyList(), memUsedMb = 0, memTotalMb = 0,
            memPercent = null, socTempC = null, gpuPct = -1, gpuMhz = 0, loadavg = emptyList(),
            freqCurMhz = emptyList(), freqMaxMhz = 0, dump = null,
        )
        val keys = snap.toSamples().map { it.key }.toSet()
        // Nothing available → no records for cpu / mem / temp / gpu.
        assertTrue(keys.none { it == MetricRegistry.CPU.key || it == MetricRegistry.SOC_TEMP.key || it == MetricRegistry.GPU_LOAD.key })
    }
}
