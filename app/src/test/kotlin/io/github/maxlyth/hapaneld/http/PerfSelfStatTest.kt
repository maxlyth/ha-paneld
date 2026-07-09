package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The /proc/self/stat parse behind the perf card's "ha-paneld + sampling" row: utime+stime PLUS
 * cutime+cstime, because the su/dumpsys/cat probes the sampler spawns are reaped children — counting
 * them is what makes the row the honest measurement-overhead total.
 */
class PerfSelfStatTest {

    // pid (comm) state ppid pgrp sid tty tpgid flags minflt cminflt majflt cmajflt utime stime cutime cstime ...
    private fun stat(comm: String, utime: Long, stime: Long, cutime: Long, cstime: Long) =
        "1234 ($comm) S 1 1234 0 0 -1 1077936448 2500 800 3 0 $utime $stime $cutime $cstime 20 0 30 0 100 900000 500"

    @Test
    fun `sums own and reaped-child jiffies`() {
        assertEquals(100L + 40 + 300 + 60, PerfReader.selfJiffiesOf(stat("hapaneld", 100, 40, 300, 60)))
    }

    @Test
    fun `comm with spaces and parens does not break field positions`() {
        // comm is arbitrary (kernel truncates to 16 chars but allows ') ' inside) — parse from the LAST ')'.
        assertEquals(10L + 2 + 0 + 0, PerfReader.selfJiffiesOf(stat("evil) 9 9 9 (x", 10, 2, 0, 0)))
    }

    @Test
    fun `malformed line yields null`() {
        assertNull(PerfReader.selfJiffiesOf("garbage"))
        assertNull(PerfReader.selfJiffiesOf("1234 (x) S 1"))
    }
}
