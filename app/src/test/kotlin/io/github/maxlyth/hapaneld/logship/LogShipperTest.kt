package io.github.maxlyth.hapaneld.logship

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LogShipperTest {
    private class FakeCapture {
        val subscriptions = AtomicInteger()
        val closes = AtomicInteger()
        private val listener = AtomicReference<((String) -> Unit)?>(null)

        fun subscribe(candidate: (String) -> Unit): AutoCloseable {
            subscriptions.incrementAndGet()
            listener.set(candidate)
            return AutoCloseable {
                if (listener.compareAndSet(candidate, null)) closes.incrementAndGet()
            }
        }

        fun emit(line: String) = listener.get()?.invoke(line)
    }

    private class RecordingSink : LogSink {
        val connected = CountDownLatch(1)
        val closed = AtomicBoolean()
        val lines = CopyOnWriteArrayList<String>()

        override fun connect() {
            connected.countDown()
        }

        override fun send(lines: List<String>) {
            this.lines.addAll(lines)
        }

        override fun close() {
            closed.set(true)
        }
    }

    private fun await(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            assertTrue("timed out waiting for condition", System.nanoTime() < deadline)
            Thread.sleep(10)
        }
    }

    private fun snapshot(host: String = "first", panelId: String = "panel-a") = LogShipConfigSnapshot(
        enabled = true,
        host = host,
        port = 514,
        protocol = "syslog",
        panelId = panelId,
    )

    @Test fun runOwnsItsQueueCountersAndTerminalBoundary() {
        val run = LogShipRun(snapshot().targetOrNull()!!, queueCapacity = 2)
        run.offer("one")
        run.offer("two")
        run.offer("three")

        assertEquals(listOf("two", "three"), run.takeBatch(2, 0))
        assertEquals(1L, run.status().dropped)

        run.close()
        run.offer("after-close")
        assertTrue(run.takeBatch(2, 0).isEmpty())
        assertFalse(run.isOpen())
    }

    @Test fun reconfigureClosesOldGenerationBeforeReplacementReceivesLines() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val config = AtomicReference(snapshot())
        val capture = FakeCapture()
        val sinks = CopyOnWriteArrayList<Pair<LogShipTarget, RecordingSink>>()
        val shipper = LogShipper(
            configSnapshot = config::get,
            scope = scope,
            subscribeCapture = capture::subscribe,
            sinkFactory = LogSinkFactory { target, _ -> RecordingSink().also { sinks += target to it } },
        )
        try {
            shipper.start()
            shipper.start()
            await { sinks.size == 1 }
            assertEquals(1, capture.subscriptions.get())
            capture.emit("first-line")
            await { sinks[0].second.lines == listOf("first-line") }

            config.set(snapshot(host = "second", panelId = "panel-b"))
            shipper.reconfigure()
            await { sinks.size == 2 && sinks[0].second.closed.get() }
            assertEquals(2, capture.subscriptions.get())
            assertEquals(1, capture.closes.get())

            capture.emit("second-line")
            await { sinks[1].second.lines == listOf("second-line") }
            assertEquals(listOf("first-line"), sinks[0].second.lines)
            assertEquals("second", sinks[1].first.host)
            assertEquals("panel-b", sinks[1].first.panelId)

            shipper.stop()
            assertTrue(sinks[1].second.closed.get())
            assertEquals(2, capture.closes.get())
        } finally {
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test fun failedInFlightLineIsReportedAsDropped() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val capture = FakeCapture()
        val sendAttempted = CountDownLatch(1)
        val shipper = LogShipper(
            configSnapshot = { snapshot() },
            scope = scope,
            subscribeCapture = capture::subscribe,
            sinkFactory = LogSinkFactory { _, _ ->
                object : LogSink {
                    override fun connect() = Unit
                    override fun send(lines: List<String>) {
                        sendAttempted.countDown()
                        throw IOException("collector rejected line")
                    }
                    override fun close() = Unit
                }
            },
        )
        try {
            shipper.start()
            capture.emit("lost")
            assertTrue(sendAttempted.await(2, TimeUnit.SECONDS))
            await { "dropped=1" in shipper.statusText() }
        } finally {
            shipper.stop()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test fun stopClosesTransportWhileConnectIsBlocked() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val capture = FakeCapture()
        val connectStarted = CountDownLatch(1)
        val closeCalled = CountDownLatch(1)
        val shipper = LogShipper(
            configSnapshot = { snapshot() },
            scope = scope,
            subscribeCapture = capture::subscribe,
            sinkFactory = LogSinkFactory { _, _ ->
                object : LogSink {
                    override fun connect() {
                        connectStarted.countDown()
                        closeCalled.await(10, TimeUnit.SECONDS)
                        throw IOException("closed")
                    }
                    override fun send(lines: List<String>) = Unit
                    override fun close() {
                        closeCalled.countDown()
                    }
                }
            },
        )
        try {
            shipper.start()
            assertTrue(connectStarted.await(2, TimeUnit.SECONDS))
            shipper.stop()
            assertTrue("stop must own and close a blocked transport", closeCalled.await(1, TimeUnit.SECONDS))
            assertEquals(1, capture.closes.get())
        } finally {
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
