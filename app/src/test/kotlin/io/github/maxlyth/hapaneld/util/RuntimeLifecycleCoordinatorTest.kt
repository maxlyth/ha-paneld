package io.github.maxlyth.hapaneld.util

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLifecycleCoordinatorTest {
    @Test fun startupAndReconfigureShareOneLaneAndAdvanceGeneration() {
        val coordinator = RuntimeLifecycleCoordinator("lifecycle-test")
        val events = Collections.synchronizedList(mutableListOf<String>())
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)

        val start = coordinator.start {
            events += "start-enter"
            startEntered.countDown()
            assertTrue(releaseStart.await(2, TimeUnit.SECONDS))
            events += "start-exit"
        }
        assertTrue(startEntered.await(2, TimeUnit.SECONDS))
        val reconfigure = coordinator.reconfigure { events += "reconfigure" }
        assertEquals(listOf("start-enter"), events.toList())

        releaseStart.countDown()
        assertTrue(start.get(2, TimeUnit.SECONDS))
        assertTrue(reconfigure.get(2, TimeUnit.SECONDS))
        assertEquals(listOf("start-enter", "start-exit", "reconfigure"), events.toList())
        assertEquals(RuntimeLifecycleCoordinator.Snapshot(RuntimeLifecycleCoordinator.State.RUNNING, 2L), coordinator.snapshot())
        assertTrue(coordinator.shutdown(2_000) {})
    }

    @Test fun reconnectRunsOnlyForTheObservedGeneration() {
        val coordinator = RuntimeLifecycleCoordinator("lifecycle-test")
        assertTrue(coordinator.start {}.get(2, TimeUnit.SECONDS))
        val oldGeneration = coordinator.currentGeneration()!!
        val reconfigureEntered = CountDownLatch(1)
        val releaseReconfigure = CountDownLatch(1)
        val reconfigure = coordinator.reconfigure {
            reconfigureEntered.countDown()
            assertTrue(releaseReconfigure.await(2, TimeUnit.SECONDS))
        }
        assertTrue(reconfigureEntered.await(2, TimeUnit.SECONDS))
        var staleReconnectRuns = 0
        val staleReconnect = coordinator.reconnect(oldGeneration) { staleReconnectRuns++ }
        releaseReconfigure.countDown()
        assertTrue(reconfigure.get(2, TimeUnit.SECONDS))
        val currentGeneration = coordinator.currentGeneration()!!
        var reconnects = 0

        assertFalse(staleReconnect.get(2, TimeUnit.SECONDS))
        assertEquals(0, staleReconnectRuns)
        assertFalse(coordinator.isCurrent(oldGeneration))
        assertTrue(coordinator.isCurrent(currentGeneration))
        assertTrue(coordinator.reconnect(currentGeneration) { reconnects++ }.get(2, TimeUnit.SECONDS))
        assertEquals(1, reconnects)
        assertTrue(coordinator.shutdown(2_000) {})
    }

    @Test fun acceptedReconnectCannotBlockLaterLifecycleAdmission() {
        val coordinator = RuntimeLifecycleCoordinator("lifecycle-test")
        assertTrue(coordinator.start {}.get(2, TimeUnit.SECONDS))
        val generation = coordinator.currentGeneration()!!
        val reconnectEntered = CountDownLatch(1)
        val releaseReconnect = CountDownLatch(1)
        val reconnect = coordinator.reconnect(generation) {
            reconnectEntered.countDown()
            assertTrue(releaseReconnect.await(2, TimeUnit.SECONDS))
        }
        assertTrue(reconnectEntered.await(2, TimeUnit.SECONDS))

        assertTrue(coordinator.reconfigure {}.get(2, TimeUnit.SECONDS))
        assertEquals(generation + 1L, coordinator.currentGeneration())
        releaseReconnect.countDown()
        assertTrue(reconnect.get(2, TimeUnit.SECONDS))
        assertTrue(coordinator.shutdown(2_000) {})
    }

    @Test fun wedgedReconnectsAreBoundedAndFurtherRecoveryEscalates() {
        var saturated = 0
        val coordinator = RuntimeLifecycleCoordinator(
            "bounded-recovery-test",
            onRecoverySaturated = { saturated++ },
        )
        assertTrue(coordinator.start {}.get(2, TimeUnit.SECONDS))
        val generation = coordinator.currentGeneration()!!
        val entered = CountDownLatch(RuntimeLifecycleCoordinator.MAX_RECOVERY_WORKERS)
        val release = CountDownLatch(1)
        val reconnects = (0 until RuntimeLifecycleCoordinator.MAX_RECOVERY_WORKERS).map {
            coordinator.reconnect(generation) {
                entered.countDown()
                release.await()
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        assertFalse(coordinator.reconnect(generation) { error("bounded pool grew") }.get(2, TimeUnit.SECONDS))
        assertEquals(1, saturated)

        release.countDown()
        reconnects.forEach { assertTrue(it.get(2, TimeUnit.SECONDS)) }
        assertTrue(coordinator.shutdown(2_000) {})
    }

    @Test fun shutdownClosesAdmissionAndDrainsOnlyTheActiveTransition() {
        val coordinator = RuntimeLifecycleCoordinator("lifecycle-test")
        assertTrue(coordinator.start {}.get(2, TimeUnit.SECONDS))
        val events = Collections.synchronizedList(mutableListOf<String>())
        val activeEntered = CountDownLatch(1)
        val releaseActive = CountDownLatch(1)
        val active = coordinator.reconfigure {
            events += "active-enter"
            activeEntered.countDown()
            assertTrue(releaseActive.await(2, TimeUnit.SECONDS))
            events += "active-exit"
        }
        assertTrue(activeEntered.await(2, TimeUnit.SECONDS))
        val queued = coordinator.reconfigure { events += "queued-must-not-run" }
        val shutdownCaller = Executors.newSingleThreadExecutor()
        val stopped = shutdownCaller.submit<Boolean> {
            coordinator.shutdown(2_000) { events += "shutdown" }
        }

        val stoppingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (coordinator.snapshot().state != RuntimeLifecycleCoordinator.State.STOPPING && System.nanoTime() < stoppingDeadline) Thread.yield()
        assertEquals(RuntimeLifecycleCoordinator.State.STOPPING, coordinator.snapshot().state)
        assertFalse(coordinator.reconfigure { events += "late-must-not-run" }.get(2, TimeUnit.SECONDS))
        releaseActive.countDown()
        assertTrue(active.get(2, TimeUnit.SECONDS))
        assertFalse(queued.get(2, TimeUnit.SECONDS))
        assertTrue(stopped.get(2, TimeUnit.SECONDS))
        shutdownCaller.shutdownNow()
        assertEquals(listOf("active-enter", "active-exit", "shutdown"), events.toList())
        assertEquals(RuntimeLifecycleCoordinator.State.STOPPED, coordinator.snapshot().state)
    }
}
