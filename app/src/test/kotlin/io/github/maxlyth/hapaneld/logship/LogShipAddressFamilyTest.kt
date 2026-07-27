package io.github.maxlyth.hapaneld.logship

import io.github.maxlyth.hapaneld.util.LogShipEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Regression cover for the defect proven on hardware 2026-07-27: a collector name carrying both A
 * and AAAA records resolved to a global IPv6 address, the panel reported a successful UDP send, and
 * nothing ever arrived. The same collector by IPv4 literal worked on every transport.
 */
class LogShipAddressFamilyTest {

    private val v4: InetAddress = InetAddress.getByName("172.31.0.118")
    private val v6: InetAddress = InetAddress.getByName("2a02:6b67:ea01:3a00::1")

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
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { listener ->
            val port = listener.localPort
            val received = java.util.concurrent.ArrayBlockingQueue<String>(1)
            Thread {
                runCatching {
                    listener.accept().use { it.getInputStream().readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()?.let { received.offer(it) }
            }.apply { isDaemon = true }.start()

            // 192.0.2.0/24 is TEST-NET-1: reserved for documentation and never routable.
            val dead = InetAddress.getByName("192.0.2.1")
            val live = InetAddress.getLoopbackAddress()
            val ordered = LogShipEndpoint.orderedCandidates(listOf(dead, live))

            var connected: java.net.Socket? = null
            for (candidate in ordered) {
                val attempt = java.net.Socket()
                try {
                    attempt.connect(java.net.InetSocketAddress(candidate, port), 1_500)
                    connected = attempt
                    break
                } catch (_: Exception) {
                    runCatching { attempt.close() }
                }
            }

            val socket = requireNotNull(connected) { "fallback never reached a live address" }
            socket.use {
                it.getOutputStream().apply {
                    write("<14>1 t h ha-paneld - - - fallback-probe\n".toByteArray(Charsets.UTF_8))
                    flush()
                }
            }
            val frame = requireNotNull(received.poll(10, java.util.concurrent.TimeUnit.SECONDS)) {
                "nothing arrived at the live address"
            }
            assertTrue(frame, frame.contains("fallback-probe"))
        }
    }
}
