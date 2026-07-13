package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrightnessHardwareWriterTest {
    @Test fun successfulSuWriteShortCircuitsHelper() {
        val root = FakeRootShell(runResult = true)
        val daemon = FakeDaemon()

        val route = BrightnessHardwareWriter(root, daemon).write(128, BacklightNode("/sys/backlight/", 100))

        assertEquals(BrightnessWriteRoute.SU, route)
        assertEquals(listOf("echo 50 > /sys/backlight/brightness"), root.ran)
        assertTrue(daemon.sent.isEmpty())
    }

    @Test fun failedSuWriteFallsThroughToHelperResult() {
        val root = FakeRootShell(runResult = false)
        val daemon = FakeDaemon(mapOf("BLREAD" to "25 200", "BLSET 100" to "OK"))

        val route = BrightnessHardwareWriter(root, daemon).write(128, BacklightNode("/sys/backlight/", 100))

        assertEquals(BrightnessWriteRoute.HELPER, route)
        assertEquals(listOf("echo 50 > /sys/backlight/brightness"), root.ran)
        assertEquals(listOf("BLREAD", "BLSET 100"), daemon.sent)
    }

    @Test fun absentSuNodeUsesHelperAndClampsInput() {
        val daemon = FakeDaemon(mapOf("BLREAD" to "0 80", "BLSET 80" to "OK"))

        val route = BrightnessHardwareWriter(FakeRootShell(), daemon).write(999, null)

        assertEquals(BrightnessWriteRoute.HELPER, route)
        assertEquals(listOf("BLREAD", "BLSET 80"), daemon.sent)
    }

    @Test fun malformedReadOrFailedSetHasNoSuccessfulRoute() {
        listOf(
            FakeDaemon(mapOf("BLREAD" to "ERR")) to listOf("BLREAD"),
            FakeDaemon(mapOf("BLREAD" to "10 0")) to listOf("BLREAD"),
            FakeDaemon(mapOf("BLREAD" to "bad 100")) to listOf("BLREAD"),
            FakeDaemon(mapOf("BLREAD" to "101 100")) to listOf("BLREAD"),
            FakeDaemon(mapOf("BLREAD" to "10 100 extra")) to listOf("BLREAD"),
            FakeDaemon(mapOf("BLREAD" to "10 100", "BLSET 50" to "ERR")) to listOf("BLREAD", "BLSET 50"),
        ).forEach { (daemon, expectedCalls) ->
            assertEquals(BrightnessWriteRoute.NONE, BrightnessHardwareWriter(FakeRootShell(), daemon).write(128, null))
            assertEquals(expectedCalls, daemon.sent)
        }
    }

    @Test fun helperReadParserAcceptsOnlyCoherentTwoFieldReplies() {
        assertEquals(BacklightReading(10, 100), parseBacklightReading(" 10   100\n"))
        assertEquals(null, parseBacklightReading(null))
        assertEquals(null, parseBacklightReading("-1 100"))
        assertEquals(null, parseBacklightReading("100 99"))
        assertEquals(null, parseBacklightReading("10 100 trailing"))
    }
}
