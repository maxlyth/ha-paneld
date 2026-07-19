package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.util.RetirableMutationGate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MdnsAdvertiserLifecycleTest {
    @Test fun retirementCleansAnInFlightStartAndRejectsLateRecovery() {
        val gate = RetirableMutationGate()
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        var activeAdvertisements = 0

        val start = executor.submit<Boolean> {
            gate.runIfOpen(false) {
                startEntered.countDown()
                assertTrue(releaseStart.await(2, TimeUnit.SECONDS))
                activeAdvertisements++
                true
            }
        }
        assertTrue(startEntered.await(2, TimeUnit.SECONDS))
        gate.closeAdmission()
        val retire = executor.submit {
            gate.runExclusive { activeAdvertisements = 0 }
        }

        assertFalse(gate.runIfOpen(false) { activeAdvertisements++; true })

        releaseStart.countDown()
        assertTrue(start.get(2, TimeUnit.SECONDS))
        retire.get(2, TimeUnit.SECONDS)
        assertEquals(0, activeAdvertisements)

        assertFalse(gate.runIfOpen(false) { activeAdvertisements++; true })
        assertEquals(0, activeAdvertisements)
        executor.shutdownNow()
    }

    @Test fun ordinaryStopRemainsRestartableForCurrentRuntimeRecovery() {
        val gate = RetirableMutationGate()
        var activeAdvertisements = 0

        assertTrue(gate.runIfOpen(false) { activeAdvertisements++; true })
        gate.runExclusive { activeAdvertisements = 0 }
        assertTrue(gate.runIfOpen(false) { activeAdvertisements++; true })

        assertEquals(1, activeAdvertisements)
    }
}
