package io.github.maxlyth.hapaneld.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure `/proc`+`/sys`+PERFDUMP parsers — no device, no I/O (like PanelHealthTest). These are the exact
 * functions PerfReader / Diagnostics used to run inline, so pinning them here pins the extraction.
 */
class MetricParseTest {

    @Test fun cpuStatLinesKeepsOnlyCpuLinesInOrder() {
        val lines = MetricParse.cpuStatLines("cpu  1 2 3\ncpu0 4 5 6\nintr 99 0\ncpu1 7 8 9\nctxt 5\n")
        assertEquals(3, lines.size)
        assertEquals(listOf(1L, 2L, 3L), lines[0].toList())
        assertEquals(listOf(4L, 5L, 6L), lines[1].toList())
        assertEquals(listOf(7L, 8L, 9L), lines[2].toList())
    }

    @Test fun cpuBusyPercentsComputesTheDeltaBusyFraction() {
        val prev = listOf(longArrayOf(0, 0, 0, 10))   // total 10, idle 10 → fully idle
        val cur = listOf(longArrayOf(5, 0, 5, 10))    // total 20, idle 10 → busy 10 of 10 delta = 100%
        assertEquals(listOf(100), MetricParse.cpuBusyPercents(prev, cur))
    }

    @Test fun cpuBusyPercentsHandlesOverallPlusCores() {
        // idle is field index 3 (+ iowait 4). Overall: total 20→40 (Δ20), idle 20→30 (Δ10) → busy 50%.
        // Each core: total 10→20 (Δ10), idle 10→15 (Δ5) → busy 50%.
        val prev = listOf(longArrayOf(0, 0, 0, 20), longArrayOf(0, 0, 0, 10), longArrayOf(0, 0, 0, 10))
        val cur = listOf(longArrayOf(10, 0, 0, 30), longArrayOf(5, 0, 0, 15), longArrayOf(5, 0, 0, 15))
        assertEquals(listOf(50, 50, 50), MetricParse.cpuBusyPercents(prev, cur))
    }

    @Test fun cpuBusyPercentsEmptyOnFirstSampleOrShapeMismatch() {
        assertEquals(emptyList<Int>(), MetricParse.cpuBusyPercents(null, listOf(longArrayOf(1, 2, 3, 4))))
        assertEquals(emptyList<Int>(), MetricParse.cpuBusyPercents(listOf(longArrayOf(1)), listOf(longArrayOf(1), longArrayOf(2))))
    }

    @Test fun cpuBusyPercentsZeroWhenNoTicksElapsed() {
        val same = listOf(longArrayOf(1, 2, 3, 4))
        assertEquals(listOf(0), MetricParse.cpuBusyPercents(same, same))
    }

    @Test fun memKbUsedAndTotal() {
        val (used, total) = MetricParse.memKb("MemTotal: 1000 kB\nMemFree: 100 kB\nMemAvailable: 400 kB\n")!!
        assertEquals(600L, used)   // 1000 − MemAvailable(400)
        assertEquals(1000L, total)
    }

    @Test fun memKbFallsBackToMemFreeAndNullsWhenNoTotal() {
        val (used, _) = MetricParse.memKb("MemTotal: 1000 kB\nMemFree: 250 kB\n")!!
        assertEquals(750L, used)   // no MemAvailable → uses MemFree(250)
        assertNull(MetricParse.memKb("MemFree: 100 kB\n"))
    }

    @Test fun maxThermalCScalesMillidegreesAndHandlesRawAndEmpty() {
        assertEquals(50.0, MetricParse.maxThermalC(listOf(42000L, 50000L))!!, 0.0001)
        assertEquals(45.0, MetricParse.maxThermalC(listOf(45L))!!, 0.0001)  // ≤1000 → already °C
        assertNull(MetricParse.maxThermalC(emptyList()))
    }

    @Test fun gpuLoadFreqParsesLoadAndFrequency() {
        assertEquals(45 to 800L, MetricParse.gpuLoadFreq("45@800000000Hz"))
        assertEquals(30 to 0L, MetricParse.gpuLoadFreq("30"))
        assertEquals(-1 to 0L, MetricParse.gpuLoadFreq(null))
        assertEquals(-1 to 0L, MetricParse.gpuLoadFreq("junk"))
    }

    @Test fun loadavg3TakesTheFirstThree() {
        assertEquals(listOf("0.5", "0.4", "0.3"), MetricParse.loadavg3("0.5 0.4 0.3 1/234 5678"))
    }

    @Test fun parsePerfDumpParsesEverySection() {
        val raw = "@STAT\ncpu 100 0 100 800 0 0 0 0\ncpu0 50 0 50 400 0 0 0 0\n" +
            "@LOAD 0.5 0.4 0.3 1/2 3\n@TEMP 42000\n@GPU 45@800000000Hz\n" +
            "@PROC\n1234\t500\tmyproc\n9\tbad\n" +
            "@REND\n1234\t250\n@END\ntrailing-ignored\n"
        val d = MetricParse.parsePerfDump(raw)
        assertEquals(2, d.stat.size)
        assertEquals(listOf(100L, 0L, 100L, 800L, 0L, 0L, 0L, 0L), d.stat[0].toList())
        assertEquals(listOf("0.5", "0.4", "0.3"), d.loadavg)
        assertEquals(42000L, d.tempMilli)
        assertEquals("45@800000000Hz", d.gpuRaw)
        assertEquals(listOf(Triple(1234, 500L, "myproc")), d.proc)  // the malformed "9\tbad" row is dropped
        assertEquals(mapOf(1234 to 250L), d.rend)
        assertEquals(42.0, MetricParse.dumpTempC(d.tempMilli)!!, 0.0001)
    }

    @Test fun parsePerfDumpTreatsDashGpuAsAbsent() {
        val d = MetricParse.parsePerfDump("@STAT\ncpu 1 2 3\n@LOAD -\n@TEMP -1\n@GPU -\n@PROC\n@REND\n@END\n")
        assertNull(d.gpuRaw)
        assertEquals(-1L, d.tempMilli)
        assertNull(MetricParse.dumpTempC(d.tempMilli))
    }

    @Test fun parseCht8305ScalesCentiUnitsToDegreesAndPercent() {
        val r = MetricParse.parseCht8305("T=3006 H=3409")!!
        assertEquals(30.06, r.tempC, 0.0001)
        assertEquals(34.09, r.humidityPct, 0.0001)
    }

    @Test fun parseCht8305IsWhitespaceAndTrailingNewlineTolerant() {
        val r = MetricParse.parseCht8305("  T=2500   H=5000\n")!!
        assertEquals(25.0, r.tempC, 0.0001)
        assertEquals(50.0, r.humidityPct, 0.0001)
    }

    @Test fun parseCht8305RejectsErrorMissingAndNonPositive() {
        assertNull(MetricParse.parseCht8305(null))
        assertNull(MetricParse.parseCht8305(""))
        assertNull(MetricParse.parseCht8305("ERR"))
        assertNull(MetricParse.parseCht8305("T=3006"))          // humidity missing
        assertNull(MetricParse.parseCht8305("H=3409"))          // temperature missing
        assertNull(MetricParse.parseCht8305("T=0 H=3409"))      // non-positive sentinel
        assertNull(MetricParse.parseCht8305("T=xx H=3409"))     // unparseable
    }
}
