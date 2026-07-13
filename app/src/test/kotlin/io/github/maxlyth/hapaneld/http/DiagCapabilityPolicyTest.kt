package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagCapabilityPolicyTest {
    @Test fun daemonInstrumentedButtonsAreReportedWithoutAccessibility() {
        val cap = DiagReader.hardwareButtonsCapability(
            accessibility = false,
            daemon = true,
            evdevButtonCount = 1,
        )
        assertEquals("ok", cap.status)
        assertTrue(cap.note.contains("helper daemon"))
    }

    @Test fun missingDaemonDegradesAnOtherwiseWorkingButtonPath() {
        val cap = DiagReader.hardwareButtonsCapability(
            accessibility = true,
            daemon = false,
            evdevButtonCount = 1,
        )
        assertEquals("degraded", cap.status)
        assertTrue(cap.note.contains("need the helper daemon"))
    }

    @Test fun noDeclaredOrLiveButtonSourceReportsNone() {
        val cap = DiagReader.hardwareButtonsCapability(
            accessibility = false,
            daemon = true,
            evdevButtonCount = 0,
        )
        assertEquals("none", cap.status)
        assertTrue(cap.note.contains("accessibility"))
    }
}
