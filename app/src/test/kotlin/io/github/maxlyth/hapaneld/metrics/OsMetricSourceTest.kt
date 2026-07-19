package io.github.maxlyth.hapaneld.metrics

import io.github.maxlyth.hapaneld.control.FakeDaemon
import io.github.maxlyth.hapaneld.control.FakeRootShell
import org.junit.Assert.assertEquals
import org.junit.Test

class OsMetricSourceTest {
    @Test fun establishedHelperWinsWithoutCallingShizuku() {
        var shellCalls = 0
        val source = OsMetricSource(
            daemon = FakeDaemon(mapOf("CHT8305" to "T=2384 H=5895")),
            root = FakeRootShell(),
            shellRoomClimate = { shellCalls++; "T=9999 H=9999" },
        )

        assertEquals("T=2384 H=5895", source.roomClimate())
        assertEquals(0, shellCalls)
    }

    @Test fun unavailableHelperFallsBackToTheFixedShizukuReader() {
        val daemon = FakeDaemon(mapOf("CHT8305" to "ERR"))
        val source = OsMetricSource(
            daemon = daemon,
            root = FakeRootShell(),
            shellRoomClimate = { "T=2384 H=5895" },
        )

        assertEquals("T=2384 H=5895", source.roomClimate())
        assertEquals(listOf("CHT8305"), daemon.sent)
    }

    @Test fun malformedHelperReplyDoesNotMaskAValidShizukuReading() {
        val source = OsMetricSource(
            daemon = FakeDaemon(mapOf("CHT8305" to "T=oops H=5895")),
            root = FakeRootShell(),
            shellRoomClimate = { "T=2384 H=5895" },
        )

        assertEquals("T=2384 H=5895", source.roomClimate())
    }
}
