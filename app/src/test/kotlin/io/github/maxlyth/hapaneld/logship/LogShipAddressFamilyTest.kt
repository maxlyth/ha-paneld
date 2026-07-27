package io.github.maxlyth.hapaneld.logship

import io.github.maxlyth.hapaneld.util.LogShipEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression cover for the defect proven on hardware 2026-07-27: a collector name carrying both A
 * and AAAA records resolved to a global IPv6 address, the panel reported a successful UDP send, and
 * nothing ever arrived. The same collector by IPv4 literal worked on every transport.
 */
class LogShipAddressFamilyTest {

    private val v4: InetAddress = InetAddress.getByName("192.0.2.118")
    private val v6: InetAddress = InetAddress.getByName("2001:db8::1")

    @Test fun ipv4IsTriedBeforeIpv6() {
        // The exact resolution order the panel saw: AAAA records first.
        val ordered = LogShipEndpoint.orderedCandidates(listOf(v6, v4))

        assertTrue("IPv4 must be tried first", ordered.first() is Inet4Address)
        assertEquals(listOf(v4, v6), ordered)
    }

    @Test fun everyAddressIsKeptSoAStreamTransportCanFallBack() {
        val ordered = LogShipEndpoint.orderedCandidates(listOf(v6, v4))

        // Dropping the non-preferred family would turn a recoverable TCP/HTTP failure into a
        // permanent one for a collector that genuinely listens on IPv6 only.
        assertEquals(2, ordered.size)
        assertTrue(ordered.any { it is Inet6Address })
    }

    @Test fun aSingleFamilyNameIsUnaffected() {
        // An IPv6-only collector has nothing else to choose, so ordering must not strand it.
        assertEquals(listOf(v6), LogShipEndpoint.orderedCandidates(listOf(v6)))
        assertEquals(listOf(v4), LogShipEndpoint.orderedCandidates(listOf(v4)))
        assertEquals(emptyList<InetAddress>(), LogShipEndpoint.orderedCandidates(emptyList()))
    }

    @Test fun relativeOrderWithinAFamilyIsPreserved() {
        val a = InetAddress.getByName("10.0.0.1")
        val b = InetAddress.getByName("10.0.0.2")

        // Only the family grouping is imposed; the resolver's own ordering within a family is the
        // platform's business and must survive.
        assertEquals(listOf(a, b, v6), LogShipEndpoint.orderedCandidates(listOf(a, b, v6)))
    }

    /**
     * The end-to-end shape of the hardware defect: a name that resolves to an unreachable address
     * first and a live one second must still ship. Uses loopback for the live half, and a
     * bound-then-released port on a documentation-range address for the dead half.
     */
    @Test(timeout = 30_000)
    fun tcpFallsBackPastAnUnreachableFirstAddress() {
        ServerSocket(0, 4, InetAddress.getByName("::1")).use { listener ->
            val port = listener.localPort
            val received = java.util.concurrent.ArrayBlockingQueue<String>(1)
            Thread {
                runCatching {
                    listener.accept().use { it.getInputStream().readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()?.let { received.offer(it) }
            }.apply { isDaemon = true }.start()

            val dead = InetAddress.getByName("127.0.0.1")
            val live = InetAddress.getByName("::1")
            val sink = NetworkLogSinkFactory.create(
                LogShipTarget("collector.test", port, LogShipEndpoint.SYSLOG_TCP, "panel"),
                { it },
                LogAddressResolver { _, _ -> listOf(dead, live) },
                1_500,
            )
            sink.use { it.connect(); it.send(listOf("<14>1 t h ha-paneld - - - fallback-probe\n")) }
            val frame = requireNotNull(received.poll(10, java.util.concurrent.TimeUnit.SECONDS)) {
                "nothing arrived at the live address"
            }
            assertTrue(frame, frame.contains("fallback-probe"))
        }
    }

    @Test(timeout = 5_000)
    fun httpPostFailureFallsBackAndPreservesOriginalHost() {
        val first = InetAddress.getByName("127.0.0.2")
        val second = InetAddress.getByName("127.0.0.1")
        val firstServer = ServerSocket(0, 4, first)
        val secondServer = ServerSocket(firstServer.localPort, 4, second)
        firstServer.use { rejected -> secondServer.use { accepted ->
            val requests = ArrayBlockingQueue<String>(2)
            serveHttp(rejected, 503, requests)
            serveHttp(accepted, 204, requests)
            val sink = NetworkLogSinkFactory.create(
                LogShipTarget("collector.example", rejected.localPort, LogShipEndpoint.HTTP, "panel"),
                { it },
                LogAddressResolver { _, _ -> listOf(first, second) },
                2_000,
            )
            sink.use { it.connect(); it.send(listOf("{\"message\":\"fallback\"}")) }
            val seen = listOf(requireNotNull(requests.poll(2, TimeUnit.SECONDS)), requireNotNull(requests.poll(2, TimeUnit.SECONDS)))
            assertTrue(seen.all { "Host: collector.example:${rejected.localPort}" in it })
        } }
    }

    @Test(timeout = 5_000)
    fun probeReportsCandidateThatActuallyAcceptedFallback() {
        val first = InetAddress.getByName("127.0.0.2")
        val second = InetAddress.getByName("127.0.0.1")
        val rejected = ServerSocket(0, 4, first)
        val accepted = ServerSocket(rejected.localPort, 4, second)
        rejected.use { a -> accepted.use { b ->
            serveHttp(a, 503, ArrayBlockingQueue(1))
            serveHttp(b, 204, ArrayBlockingQueue(1))
            val result = NetworkLogSinkFactory.probe(
                LogShipTarget("collector.example", a.localPort, LogShipEndpoint.HTTP, "panel"),
                "{}".toByteArray(),
                LogAddressResolver { _, _ -> listOf(first, second) },
                2_000,
            )
            assertTrue(result.toString(), result.ok)
            assertEquals(second, result.candidate)
            assertEquals(204, result.status)
        } }
    }

    @Test(timeout = 5_000)
    fun probeReportsFinalCandidateWhenEveryAttemptFails() {
        val first = InetAddress.getByName("127.0.0.2")
        val second = InetAddress.getByName("127.0.0.3")
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val result = NetworkLogSinkFactory.probe(
            LogShipTarget("collector.example", port, LogShipEndpoint.HTTP, "panel"),
            "{}".toByteArray(),
            LogAddressResolver { _, _ -> listOf(first, second) },
            1_000,
        )
        assertTrue(result.toString(), !result.ok)
        assertEquals(second, result.candidate)
    }

    @Test(timeout = 5_000)
    fun httpStatusDoesNotLeakOntoLaterTransportFailure() {
        val first = InetAddress.getByName("127.0.0.2")
        val second = InetAddress.getByName("127.0.0.3")
        ServerSocket(0, 4, first).use { rejected ->
            serveHttp(rejected, 503, ArrayBlockingQueue(1))
            val result = NetworkLogSinkFactory.probe(
                LogShipTarget("collector.example", rejected.localPort, LogShipEndpoint.HTTP, "panel"),
                "{}".toByteArray(),
                LogAddressResolver { _, _ -> listOf(first, second) },
                1_000,
            )
            assertTrue(result.toString(), !result.ok)
            assertEquals(second, result.candidate)
            assertEquals(null, result.status)
            assertTrue(result.toString(), result.error != "http-503")
        }
    }

    @Test(timeout = 5_000)
    fun httpCloseInterruptsActivePost() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val accepted = ArrayBlockingQueue<Socket>(1)
            Thread { runCatching { listener.accept() }.getOrNull()?.let { accepted.offer(it) } }.apply { isDaemon = true }.start()
            val sink = NetworkLogSinkFactory.create(
                LogShipTarget("collector.test", listener.localPort, LogShipEndpoint.HTTP, "panel"),
                { it },
                LogAddressResolver { _, _ -> listOf(InetAddress.getLoopbackAddress()) },
                4_000,
            )
            sink.connect()
            val failure = AtomicReference<Throwable?>()
            val worker = Thread { runCatching { sink.send(listOf("{}")) }.exceptionOrNull().let(failure::set) }
            worker.start()
            val socket = requireNotNull(accepted.poll(2, TimeUnit.SECONDS))
            sink.close()
            worker.join(1_000)
            socket.close()
            assertTrue("close did not interrupt active POST", !worker.isAlive)
            assertTrue("send unexpectedly succeeded", failure.get() != null)
        }
    }

    @Test(timeout = 5_000)
    fun httpCandidatesShareOneAbsoluteDeadline() {
        val first = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, first).use { listener ->
            Thread { runCatching { listener.accept() }.getOrNull()?.use { Thread.sleep(2_000) } }.apply { isDaemon = true }.start()
            val sink = NetworkLogSinkFactory.create(
                LogShipTarget("collector.test", listener.localPort, LogShipEndpoint.HTTP, "panel"),
                { it },
                LogAddressResolver { _, _ -> listOf(first, InetAddress.getByName("127.0.0.2")) },
                300,
            )
            val started = System.nanoTime()
            sink.connect()
            runCatching { sink.send(listOf("{}")) }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            sink.close()
            assertTrue("candidate timeouts multiplied the deadline: ${elapsed}ms", elapsed < 800)
        }
    }

    private fun serveHttp(server: ServerSocket, status: Int, requests: ArrayBlockingQueue<String>) {
        Thread {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                    val length = head.lines().firstOrNull { it.startsWith("Content-Length:", true) }
                        ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) read += input.read(body, read, length - read)
                    requests.offer(head.toString() + body.toString(Charsets.UTF_8))
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }
}
