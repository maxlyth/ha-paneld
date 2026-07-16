package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.MqttCommandAdmission
import io.github.maxlyth.hapaneld.sensors.SensorRunCallbacks
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRuntimeOwnerTest {
    private class FakeNetwork(
        val name: String,
        private val events: MutableList<String>,
    ) {
        val commands = MqttCommandAdmission()
        @Volatile private var stopped = false

        fun start() {
            check(!stopped)
            events += "$name-start"
        }

        fun stop() {
            events += "$name-stop-enter"
            commands.closeAndDrain()
            stopped = true
            events += "$name-stop-exit"
        }

        fun reconnect(entered: CountDownLatch, release: CountDownLatch) {
            events += "$name-reconnect-enter"
            entered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
            if (stopped) events += "$name-reconnect-terminal" else events += "$name-reconnect-applied"
        }
    }

    @Test fun serviceCompositionOwnsDrainReplacementLateCallbacksReconnectAndShutdown() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val samples = Collections.synchronizedList(mutableListOf<Int>())
        val initial = FakeNetwork("initial", events)
        val owner = ServiceRuntimeOwner(initial, "service-composed-test")
        val commandEntered = CountDownLatch(1)
        val releaseCommand = CountDownLatch(1)
        val reconnectEntered = CountDownLatch(1)
        val releaseReconnect = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        lateinit var sensorRun: SensorRunCallbacks

        try {
            assertTrue(owner.start { network ->
                events += "service-start"
                sensorRun = SensorRunCallbacks(
                    onLux = samples::add,
                    onLuxRaw = {},
                    onProximity = {},
                    onTemperature = {},
                    onHumidity = {},
                )
                network.start()
            }.get(2, TimeUnit.SECONDS))
            val initialObservation = owner.observe()!!
            assertSame(initial, initialObservation.value)
            sensorRun.light(12f, now = 100L)

            val command = workers.submit<Boolean> {
                initial.commands.run {
                    events += "command-enter"
                    commandEntered.countDown()
                    assertTrue(releaseCommand.await(2, TimeUnit.SECONDS))
                    events += "command-exit"
                }
            }
            assertTrue(commandEntered.await(2, TimeUnit.SECONDS))

            val reconfigured = owner.reconfigure(
                stop = FakeNetwork::stop,
                build = {
                    events += "replacement-build"
                    FakeNetwork("replacement", events)
                },
                start = FakeNetwork::start,
                complete = { events += "replacement-complete" },
            )
            awaitEvent(events, "initial-stop-enter")
            assertSame("replacement must not publish before command drain", initial, owner.current())
            assertFalse(events.contains("replacement-build"))

            releaseCommand.countDown()
            assertTrue(command.get(2, TimeUnit.SECONDS))
            assertTrue(reconfigured.get(2, TimeUnit.SECONDS))
            val replacement = owner.current()
            assertNotSame(initial, replacement)
            assertEquals(2L, owner.observe()!!.generation)

            assertFalse(owner.reconnect(initialObservation) { events += "stale-reconnect-ran" }.get(2, TimeUnit.SECONDS))
            val replacementObservation = owner.observe()!!
            val reconnect = owner.reconnect(replacementObservation) { network ->
                network.reconnect(reconnectEntered, releaseReconnect)
            }
            assertTrue(reconnectEntered.await(2, TimeUnit.SECONDS))

            assertTrue(owner.shutdown(2_000) { network ->
                events += "service-stop-enter"
                sensorRun.close()
                network.stop()
                events += "service-stop-exit"
            })
            assertEquals(RuntimeLifecycleCoordinator.State.STOPPED, owner.snapshot().state)
            sensorRun.light(99f, now = 100_000L)
            assertEquals("a late sensor result must not cross the terminal callback generation", listOf(12), samples)
            assertFalse(replacement.commands.run { events += "late-command-ran" })
            assertFalse(owner.reconnect(replacementObservation) { events += "post-stop-reconnect-ran" }.get(2, TimeUnit.SECONDS))

            releaseReconnect.countDown()
            assertFalse("terminal shutdown cancels an accepted recovery worker", reconnect.get(2, TimeUnit.SECONDS))
            assertEquals(
                listOf(
                    "service-start",
                    "initial-start",
                    "command-enter",
                    "initial-stop-enter",
                    "command-exit",
                    "initial-stop-exit",
                    "replacement-build",
                    "replacement-start",
                    "replacement-complete",
                    "replacement-reconnect-enter",
                    "service-stop-enter",
                    "replacement-stop-enter",
                    "replacement-stop-exit",
                    "service-stop-exit",
                ),
                events.toList(),
            )
        } finally {
            releaseCommand.countDown()
            releaseReconnect.countDown()
            if (owner.snapshot().state != RuntimeLifecycleCoordinator.State.STOPPED) {
                owner.shutdown(2_000) { it.stop() }
            }
            workers.shutdownNow()
        }
    }

    @Test fun failedReplacementRemainsOwnedForRecoveryAndTerminalCleanup() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val initial = FakeNetwork("initial", events)
        val owner = ServiceRuntimeOwner(initial, "service-failure-test")

        try {
            assertTrue(owner.start(FakeNetwork::start).get(2, TimeUnit.SECONDS))
            lateinit var failed: FakeNetwork
            assertFalse(owner.reconfigure(
                stop = FakeNetwork::stop,
                build = {
                    failed = FakeNetwork("failed", events)
                    failed
                },
                start = {
                    it.start()
                    error("start failed after acquiring resources")
                },
            ).get(2, TimeUnit.SECONDS))
            assertEquals(RuntimeLifecycleCoordinator.State.FAILED, owner.snapshot().state)
            assertSame("the partially started replacement must remain reachable for cleanup", failed, owner.current())

            assertTrue(owner.reconfigure(
                stop = FakeNetwork::stop,
                build = { FakeNetwork("recovered", events) },
                start = FakeNetwork::start,
            ).get(2, TimeUnit.SECONDS))
            assertEquals(RuntimeLifecycleCoordinator.State.RUNNING, owner.snapshot().state)
            assertTrue(owner.shutdown(2_000, FakeNetwork::stop))
            assertEquals(
                listOf(
                    "initial-start",
                    "initial-stop-enter",
                    "initial-stop-exit",
                    "failed-start",
                    "failed-stop-enter",
                    "failed-stop-exit",
                    "recovered-start",
                    "recovered-stop-enter",
                    "recovered-stop-exit",
                ),
                events.toList(),
            )
        } finally {
            if (owner.snapshot().state != RuntimeLifecycleCoordinator.State.STOPPED) {
                owner.shutdown(2_000) { it.stop() }
            }
        }
    }

    @Test fun shutdownWaitsForInFlightStartupThenClosesResourcesStartupOpened() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = ServiceRuntimeOwner("network", "startup-shutdown-test")
        val worker = Executors.newSingleThreadExecutor()

        try {
            val startup = owner.start {
                events += "startup-enter"
                entered.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
                events += "http-start"
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            val shutdown = worker.submit<Boolean> {
                owner.shutdown(2_000) {
                    events += "http-stop"
                    events += "runtime-stop"
                }
            }
            Thread.yield()
            assertFalse("cleanup must remain queued behind startup", events.contains("http-stop"))

            release.countDown()
            assertTrue(startup.get(2, TimeUnit.SECONDS))
            assertTrue(shutdown.get(2, TimeUnit.SECONDS))
            assertEquals(
                listOf("startup-enter", "http-start", "http-stop", "runtime-stop"),
                events.toList(),
            )
        } finally {
            release.countDown()
            if (owner.snapshot().state != RuntimeLifecycleCoordinator.State.STOPPED) {
                owner.shutdown(2_000) {}
            }
            worker.shutdownNow()
        }
    }

    private fun awaitEvent(events: List<String>, expected: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!events.contains(expected) && System.nanoTime() < deadline) Thread.yield()
        assertTrue("timed out waiting for $expected; events=$events", events.contains(expected))
    }
}
