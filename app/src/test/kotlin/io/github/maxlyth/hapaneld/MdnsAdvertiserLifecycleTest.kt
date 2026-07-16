package io.github.maxlyth.hapaneld

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MdnsAdvertiserLifecycleTest {
    @Test fun retirementCleansAnInFlightStartAndRejectsLateRecovery() {
        val gate = RestartableOwnerGate()
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        var activeAdvertisements = 0

        val start = executor.submit<Boolean> {
            gate.start {
                startEntered.countDown()
                assertTrue(releaseStart.await(2, TimeUnit.SECONDS))
                activeAdvertisements++
            }
        }
        assertTrue(startEntered.await(2, TimeUnit.SECONDS))
        val retire = executor.submit {
            gate.retire { activeAdvertisements = 0 }
        }

        releaseStart.countDown()
        assertTrue(start.get(2, TimeUnit.SECONDS))
        retire.get(2, TimeUnit.SECONDS)
        assertEquals(0, activeAdvertisements)

        assertFalse(gate.start { activeAdvertisements++ })
        assertEquals(0, activeAdvertisements)
        executor.shutdownNow()
    }

    @Test fun ordinaryStopRemainsRestartableForCurrentRuntimeRecovery() {
        val gate = RestartableOwnerGate()
        var activeAdvertisements = 0

        assertTrue(gate.start { activeAdvertisements++ })
        gate.stop { activeAdvertisements = 0 }
        assertTrue(gate.start { activeAdvertisements++ })

        assertEquals(1, activeAdvertisements)
    }
}
