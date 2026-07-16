package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.util.SuccessStickyProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

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

    @Test fun completeBrightnessTransactionsAreSerialized() {
        val firstEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val applied = CopyOnWriteArrayList<Int>()
        val sequencer = BrightnessWriteSequencer { level ->
            applied += level
            if (level == 10) {
                firstEntered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
        }
        val first = Thread { sequencer.write(10) }.apply { start() }
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
        val second = Thread { sequencer.write(200) }.apply { start() }
        Thread.sleep(30)
        assertEquals(listOf(10), applied)
        release.countDown()
        first.join(2_000)
        second.join(2_000)
        assertEquals(listOf(10, 200), applied)
    }

    @Test fun unavailableBacklightDiscoveryRetriesWithBoundedBackoffThenSticksOnSuccess() {
        var now = 10_000L
        var calls = 0
        val probe = SuccessStickyProbe(
            probe = {
                calls++
                if (calls < 3) null else BacklightNode("/sys/class/backlight/panel/", 255)
            },
            nowMs = { now },
            initialBackoffMs = 100L,
            maxBackoffMs = 200L,
        )

        assertEquals(null, probe.get())
        now += 99
        assertEquals(null, probe.get())
        assertEquals(1, calls)
        now += 1
        assertEquals(null, probe.get())
        assertEquals(2, calls)
        now += 199
        assertEquals(null, probe.get())
        assertEquals(2, calls)
        now += 1
        assertEquals(BacklightNode("/sys/class/backlight/panel/", 255), probe.get())
        now += 60_000
        assertEquals(BacklightNode("/sys/class/backlight/panel/", 255), probe.get())
        assertEquals(3, calls)
    }
}
