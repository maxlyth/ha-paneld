package io.github.maxlyth.hapaneld.http

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

/**
 * Covers GET /api/v1/config/probe-log-sink without constructing the Android-backed server graph,
 * the same way [BrokerProbeAdversarialTest] covers the broker probe.
 *
 * The behaviour worth pinning is the honesty of the UDP verdict: a datagram that leaves the panel
 * proves nothing about arrival, so the probe must report `delivered:false` even when it succeeds.
 */
class LogSinkProbeTest {

    @Test(timeout = 5_000)
    fun `a blank host is rejected before any network work`() {
        val result = probe(host = "")

        assertFalse(result.getBoolean("ok"))
        assertEquals("no-host", result.getString("error"))
        assertEquals(setOf("ok", "error"), result.keys().asSequence().toSet())
    }

    @Test(timeout = 5_000)
    fun `an unresolvable host names the resolution failure`() {
        val result = probe(host = "no-such-host.invalid")

        assertFalse(result.getBoolean("ok"))
        assertEquals("unresolvable", result.getString("error"))
        assertEquals("no-such-host.invalid", result.getString("host"))
    }

    @Test(timeout = 5_000)
    fun `a reachable tcp collector reports a real connection`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val result = probe(host = "127.0.0.1", port = listener.localPort, protocol = "syslog-tcp")

            assertTrue(result.getBoolean("ok"))
            assertTrue("a stream transport does confirm arrival", result.getBoolean("delivered"))
            assertEquals("syslog-tcp", result.getString("protocol"))
            assertEquals("127.0.0.1", result.getString("resolved"))
            assertFalse(result.has("error"))
        }
    }

    @Test(timeout = 5_000)
    fun `a closed tcp port returns unreachable within the connect bound`() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val started = System.nanoTime()

        val result = probe(host = "127.0.0.1", port = port, protocol = "syslog-tcp")

        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertFalse(result.getBoolean("ok"))
        assertEquals("unreachable", result.getString("error"))
        assertTrue("probe exceeded its 2 second connect bound: ${elapsedMs}ms", elapsedMs < 2_500)
    }

    @Test(timeout = 5_000)
    fun `udp sends one marked datagram and refuses to claim it arrived`() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 3_000
            val result = probe(host = "127.0.0.1", port = receiver.localPort, protocol = "syslog-udp")

            assertTrue(result.getBoolean("ok"))
            // The whole point: UDP cannot confirm delivery, so the probe must not pretend otherwise.
            assertFalse("UDP must never report delivery", result.getBoolean("delivered"))
            val marker = result.getString("marker")
            assertTrue(marker, marker.startsWith("ha-paneld-sink-probe-"))

            val packet = DatagramPacket(ByteArray(4096), 4096)
            receiver.receive(packet)
            val frame = String(packet.data, 0, packet.length, Charsets.UTF_8)
            // A well-formed RFC5424 frame, so a real collector accepts the probe rather than dropping
            // it — a probe its own collector discards would be worse than no probe at all.
            assertTrue(frame, frame.startsWith("<14>1 "))
            assertTrue(frame, " test-panel ha-paneld - - - " in frame)
            assertTrue(frame, frame.endsWith(marker))
        }
    }

    @Test(timeout = 5_000)
    fun `a scheme typed into the host field selects the transport`() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            // Stored protocol says TCP; the typed scheme says UDP and must win.
            val result = probe(
                host = "udp://127.0.0.1:${receiver.localPort}",
                port = 514,
                protocol = "syslog-tcp",
            )

            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("syslog-udp", result.getString("protocol"))
            assertEquals(receiver.localPort, result.getInt("port"))
            assertEquals("127.0.0.1", result.getString("host"))
        }
    }

    private fun probe(
        host: String,
        port: Int = 514,
        protocol: String = "syslog-udp",
        panelId: String = "test-panel",
    ): JSONObject {
        val method = PaneldServer::class.java.getDeclaredMethod(
            "probeLogSinkJson",
            String::class.java,
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        val server = unsafe.allocateInstance(PaneldServer::class.java) as PaneldServer
        return JSONObject(method.invoke(server, host, port, protocol, panelId) as String)
    }

    private companion object {
        val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null) as Unsafe
    }
}
