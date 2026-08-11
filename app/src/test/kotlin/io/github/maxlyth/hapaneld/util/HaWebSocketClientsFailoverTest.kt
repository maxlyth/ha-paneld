package io.github.maxlyth.hapaneld.util

import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Behavioral cover for the a field failure: the HA host's AAAA was black-holed from the
 * panel's segment while its A record worked, and every previous WebSocket client dialed exactly one
 * resolved address, so the panel could never render despite a fully working IPv4 path. These tests
 * drive the shared factory against real sockets: a dead route (refused, or a saturated backlog that
 * swallows SYNs like the real black-hole) listed ahead of a live sibling must still connect within
 * the callers' 15 s outer deadline.
 *
 * The in-file [WsAcceptor] is a minimal RFC 6455 responder (101 upgrade + unmasked server frames),
 * deliberately dependency-free so this file exercises the production client stack alone.
 */
class HaWebSocketClientsFailoverTest {

    // ---- minimal WebSocket server harness ------------------------------------------------------

    private class WsAcceptor(
        bindAddress: InetAddress,
        private val payloadBytes: Int = 0,
        port: Int = 0,
    ) : AutoCloseable {
        val server = ServerSocket().apply { bind(InetSocketAddress(bindAddress, port), 16) }
        val port: Int get() = server.localPort
        val upgrades = AtomicInteger()
        private val thread = Thread {
            runCatching {
                while (!server.isClosed) {
                    val socket = server.accept()
                    Thread { runCatching { serve(socket) } }.apply { isDaemon = true }.start()
                }
            }
        }.apply { isDaemon = true; start() }

        private fun serve(socket: Socket): Unit = socket.use { s ->
            val key = readUpgradeKey(s.getInputStream()) ?: return
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
            )
            val out = s.getOutputStream()
            out.write(
                ("HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(),
            )
            out.flush()
            upgrades.incrementAndGet()
            if (payloadBytes > 0) writeTextFrame(out, ByteArray(payloadBytes) { 'a'.code.toByte() })
            // Hold the connection open until the client closes it.
            runCatching { while (s.getInputStream().read() >= 0) Unit }
        }

        private fun readUpgradeKey(input: InputStream): String? {
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) {
                val byte = input.read()
                if (byte < 0) return null
                header.append(byte.toChar())
                if (header.length > 16_384) return null
            }
            return Regex("Sec-WebSocket-Key: (\\S+)", RegexOption.IGNORE_CASE)
                .find(header)?.groupValues?.get(1)
        }

        private fun writeTextFrame(out: OutputStream, payload: ByteArray) {
            out.write(0x81)
            when {
                payload.size < 126 -> out.write(payload.size)
                payload.size <= 0xFFFF -> {
                    out.write(126); out.write(payload.size ushr 8); out.write(payload.size and 0xFF)
                }
                else -> {
                    out.write(127)
                    for (shift in 56 downTo 0 step 8) out.write(((payload.size.toLong()) ushr shift).toInt() and 0xFF)
                }
            }
            out.write(payload)
            out.flush()
        }

        override fun close() { server.close() }
    }

    /** Loopback aliases: distinct IPv4 loopback addresses let one port carry a dead and a live route. */
    private val liveLoopback: InetAddress = InetAddress.getByName("127.0.0.2")
    private val deadLoopback: InetAddress = InetAddress.getByName("127.0.0.1")

    private fun loopbackAliasAvailable(): Boolean = runCatching {
        ServerSocket().use { it.bind(InetSocketAddress(liveLoopback, 0)); true }
    }.getOrDefault(false)

    /** Fill a backlog-1 listener until new SYNs are swallowed (Linux drops them once the accept
     *  queue is full) — the deterministic stand-in for a black-holed route. Returns false when the
     *  platform will not saturate, so callers can skip rather than flake. */
    private fun saturate(server: ServerSocket): Boolean {
        val holders = mutableListOf<Socket>()
        repeat(24) {
            val probe = Socket()
            val connected = runCatching {
                probe.connect(InetSocketAddress(server.inetAddress, server.localPort), 300)
            }.isSuccess
            if (!connected) { probe.close(); return true }
            holders.add(probe) // keep the queue occupied for the test's lifetime
        }
        return false
    }

    // ---- route iteration -----------------------------------------------------------------------

    @Test fun refusedRouteFallsBackToTheNextAddress() {
        assumeTrue("no loopback alias in this environment", loopbackAliasAvailable())
        WsAcceptor(liveLoopback).use { live ->
            // Nothing listens on 127.0.0.1 at the same port: the first route is REFUSED, which is
            // the fast-failing sibling of the black-hole. CIO died here; the factory must walk on.
            val client = HaWebSocketClients.client(resolver = { listOf(deadLoopback, liveLoopback) })
            try {
                runBlocking {
                    val session = withTimeout(15_000) { client.webSocketSession("ws://ha.test:${live.port}/api/websocket") }
                    session.close()
                }
            } finally {
                client.close()
            }
            assertEquals("exactly one upgrade reached the live route", 1, live.upgrades.get())
        }
    }

    @Test fun blackHoledRouteStillReachesTheLiveSiblingWithinTheCallerDeadline() {
        assumeTrue("no loopback alias in this environment", loopbackAliasAvailable())
        val blackHole = ServerSocket().apply { bind(InetSocketAddress(deadLoopback, 0), 1) }
        try {
            assumeTrue("platform would not saturate the backlog", saturate(blackHole))
            // The dead and live routes share ONE port on different loopback addresses, exactly like
            // one hostname resolving to a black-holed and a working address: the first route's SYNs
            // are swallowed by the saturated queue, and only address iteration can reach the sibling.
            WsAcceptor(liveLoopback, port = blackHole.localPort).use { live ->
                val elapsedStart = System.nanoTime()
                val client = HaWebSocketClients.client(
                    routeConnectTimeoutMs = 2_000,
                    resolver = { listOf(deadLoopback, liveLoopback) },
                )
                try {
                    runBlocking {
                        val session = withTimeout(15_000) {
                            client.webSocketSession("ws://ha.test:${live.port}/api/websocket")
                        }
                        session.close()
                    }
                } finally {
                    client.close()
                }
                val elapsedMs = (System.nanoTime() - elapsedStart) / 1_000_000
                // Faster than one sequential route timeout (2 s) proves the concurrent family race
                // (fast fallback) is live, not just eventual iteration - expected ~250 ms.
                assertTrue("connected in ${elapsedMs}ms, beating the 2s sequential route timeout", elapsedMs < 2_000)
                assertEquals(1, live.upgrades.get())
            }
        } finally {
            blackHole.close()
        }
    }

    @Test fun aDeadOnlyRouteFailsWithinTheConfiguredConnectTimeout() {
        // Force IPv4 against a dead IPv4 is a single-route walk: only the configured per-route
        // connect timeout bounds it. The captured device log.s `connect_timeout=unknown ms` is what this pins
        // against returning.
        val blackHole = ServerSocket().apply { bind(InetSocketAddress(deadLoopback, 0), 1) }
        try {
            assumeTrue("platform would not saturate the backlog", saturate(blackHole))
            val client = HaWebSocketClients.client(
                routeConnectTimeoutMs = 1_000,
                resolver = { listOf(deadLoopback) },
            )
            val start = System.nanoTime()
            try {
                runBlocking {
                    try {
                        withTimeout(15_000) {
                            client.webSocketSession("ws://ha.test:${blackHole.localPort}/api/websocket")
                        }
                        fail("a black-holed only route must not connect")
                    } catch (expected: Exception) {
                        // any failure shape is fine; the bound under test is WHEN it fails
                    }
                }
            } finally {
                client.close()
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue(
                "failed in ${elapsedMs}ms - the 1s configured timeout applied (default would be 10s+)",
                elapsedMs < 8_000,
            )
        } finally {
            blackHole.close()
        }
    }

    @Test fun cancellationDuringADeadConnectReturnsPromptly() {
        assumeTrue("no loopback alias in this environment", loopbackAliasAvailable())
        val blackHole = ServerSocket().apply { bind(InetSocketAddress(deadLoopback, 0), 1) }
        try {
            assumeTrue("platform would not saturate the backlog", saturate(blackHole))
            val client = HaWebSocketClients.client(
                routeConnectTimeoutMs = 10_000,
                resolver = { listOf(deadLoopback) },
            )
            val released = CountDownLatch(1)
            try {
                runBlocking {
                    val attempt = launch(Dispatchers.IO) {
                        try {
                            client.webSocketSession("ws://ha.test:${blackHole.localPort}/api/websocket")
                            fail("black-holed connect must not succeed")
                        } catch (expected: CancellationException) {
                            throw expected
                        } catch (expected: Exception) {
                            // A raced abort may surface as an I/O failure instead — also prompt.
                        } finally {
                            released.countDown()
                        }
                    }
                    delay(300)
                    attempt.cancel()
                    attempt.join()
                }
            } finally {
                client.close()
            }
            assertTrue(
                "cancelled connect released its caller promptly",
                released.await(3, TimeUnit.SECONDS),
            )
        } finally {
            blackHole.close()
        }
    }

    // ---- payload bounds ------------------------------------------------------------------------

    @Test fun multiMegabytePayloadSurvivesTheEngineSwap() {
        // Large entity catalogs can produce multi-megabyte payloads. The swap
        // must not shrink what a fleet currently relies on.
        val payload = 5 * 1024 * 1024
        WsAcceptor(InetAddress.getByName("127.0.0.1"), payloadBytes = payload).use { server ->
            val client = HaWebSocketClients.client()
            try {
                runBlocking {
                    val session = withTimeout(30_000) {
                        client.webSocketSession("ws://127.0.0.1:${server.port}/api/websocket")
                    }
                    val frame = withTimeout(30_000) { session.incoming.receive() }
                    val text = (frame as Frame.Text).readText()
                    assertEquals(payload, text.length)
                    session.close()
                }
            } finally {
                client.close()
            }
        }
    }

    @Test fun aCustomFrameBoundIsStructurallyUnavailableOnThisEngine() {
        // Documents WHY the factory exposes no maxFrameSize: Ktor's OkHttp engine throws on any
        // custom value at session start, so the previous per-site 2-32 MB bounds cannot ride
        // through this engine. If a Ktor upgrade ever makes this construction succeed, the bound
        // can be reintroduced - this pin is the reminder.
        WsAcceptor(InetAddress.getByName("127.0.0.1")).use { server ->
            val client = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
                install(io.ktor.client.plugins.websocket.WebSockets) { maxFrameSize = 1L shl 20 }
            }
            try {
                runBlocking {
                    try {
                        withTimeout(15_000) {
                            client.webSocketSession("ws://127.0.0.1:${server.port}/api/websocket")
                        }
                        fail("expected the engine to refuse a custom maxFrameSize")
                    } catch (expected: io.ktor.client.plugins.websocket.WebSocketException) {
                        assertTrue(
                            "engine names the unsupported switch: ${expected.message}",
                            expected.message.orEmpty().contains("not supported"),
                        )
                    }
                }
            } finally {
                client.close()
            }
        }
    }
}
