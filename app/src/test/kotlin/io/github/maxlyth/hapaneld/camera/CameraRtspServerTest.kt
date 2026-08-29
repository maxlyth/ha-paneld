package io.github.maxlyth.hapaneld.camera

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transport over real sockets against a fake camera: the first client takes the lease and the
 * last gives it back, refusals reach the client as 503 with the classified reason, a client that
 * cannot keep up is shed without the encoder's thread blocking, a silent client is dropped by the
 * read timeout, and a stop tears every thread down.
 */
class CameraRtspServerTest {

    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1F, 0xDA.toByte(), 0x01, 0x40)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x06, 0xE2.toByte())
    private val sets = ParameterSets(sps, pps)
    private val idr = byteArrayOf(0x65) + ByteArray(40) { it.toByte() }

    private inner class FakeSource(private var refusal: CameraRefusal? = null) : CameraStreamSource {
        val acquired = AtomicInteger()
        val released = AtomicInteger()
        val keyFrames = AtomicInteger()
        val requests = ArrayList<StreamRequest>()

        override fun acquireStream(request: StreamRequest): StreamAdmission {
            synchronized(requests) { requests += request }
            refusal?.let { return StreamAdmission.Refused(it) }
            acquired.incrementAndGet()
            var open = true
            val lease = AutoCloseable {
                if (open) {
                    open = false
                    released.incrementAndGet()
                }
            }
            return StreamAdmission.Granted(lease, StreamParams(640, 480, 15, 1_000, "fake.encoder", sets))
        }

        override fun requestKeyFrame() {
            keyFrames.incrementAndGet()
        }
    }

    private val servers = ArrayList<CameraRtspServer>()

    private fun server(source: CameraStreamSource, maxClients: Int = 4, queuePackets: Int = 512, readTimeoutMs: Int = 10_000): CameraRtspServer {
        val s = CameraRtspServer(port = 0, source = { source }, maxClients = maxClients, queuePackets = queuePackets, readTimeoutMs = readTimeoutMs)
        servers += s
        s.setListening(true)
        assertNotNull("bound", s.boundPort)
        return s
    }

    @After fun tearDown() {
        servers.forEach { it.stop() }
    }

    private class Client(port: Int) : AutoCloseable {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }
        val input: InputStream = BufferedInputStream(socket.getInputStream())
        private var cseq = 0

        fun request(method: String, url: String, vararg headers: String): Response {
            val text = buildString {
                append(method).append(' ').append(url).append(" RTSP/1.0\r\n")
                append("CSeq: ").append(++cseq).append("\r\n")
                headers.forEach { append(it).append("\r\n") }
                append("\r\n")
            }
            socket.getOutputStream().write(text.toByteArray(Charsets.US_ASCII))
            return readResponse()
        }

        fun readResponse(): Response {
            val head = ByteArrayOutputStream()
            var matched = 0
            while (matched < 4) {
                val b = input.read()
                check(b >= 0) { "eof" }
                head.write(b)
                matched = when {
                    b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                    b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                    else -> 0
                }
            }
            val lines = head.toString("US-ASCII").split("\r\n").filter { it.isNotEmpty() }
            val status = lines[0].split(' ')[1].toInt()
            val headers = lines.drop(1).associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
            val length = headers["Content-Length"]?.toInt() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                check(n >= 0)
                read += n
            }
            return Response(status, headers, String(body, Charsets.US_ASCII))
        }

        /** The next interleaved frame: channel and RTP packet. */
        fun readFrame(): Pair<Int, ByteArray> {
            val magic = input.read()
            check(magic == '$'.code) { "expected an interleaved frame, got $magic" }
            val channel = input.read()
            val length = (input.read() shl 8) or input.read()
            val packet = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(packet, read, length - read)
                check(n >= 0)
                read += n
            }
            return channel to packet
        }

        fun sendInterleaved(channel: Int, payload: ByteArray) {
            socket.getOutputStream().write(RtspInterleaved.frame(channel, payload))
        }

        /** The next byte, -1 at end of stream, or -2 when the server said nothing within the socket timeout. */
        fun readOrTimeout(): Int = try {
            input.read()
        } catch (_: java.net.SocketTimeoutException) {
            -2
        }

        fun play(url: String): String {
            request("OPTIONS", url)
            val describe = request("DESCRIBE", url, "Accept: application/sdp")
            check(describe.status == 200) { "describe ${describe.status}" }
            val setup = request("SETUP", "$url/trackID=0", "Transport: RTP/AVP/TCP;unicast;interleaved=0-1")
            check(setup.status == 200) { "setup ${setup.status}" }
            val session = requireNotNull(setup.headers["Session"]).substringBefore(';')
            val play = request("PLAY", url, "Session: $session")
            check(play.status == 200) { "play ${play.status}" }
            return session
        }

        override fun close() {
            socket.close()
        }
    }

    private data class Response(val status: Int, val headers: Map<String, String>, val body: String)

    private fun await(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
    }

    @Test fun theFirstClientTakesTheLeaseReceivesRtpAfterPlayAndItsTeardownGivesTheLeaseBack() {
        val source = FakeSource()
        val server = server(source)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live?res=480p&fps=5"
        Client(server.boundPort!!).use { client ->
            val session = client.play(url)
            assertEquals(1, source.acquired.get())
            assertEquals(listOf(StreamRequest(resolution = CameraResolution.P480, fps = 5)), source.requests)
            assertEquals("PLAY asks for a sync frame", 1, source.keyFrames.get())
            assertEquals(StreamTransportFacts(port = server.boundPort, clients = 1), server.facts())

            server.onParameterSets(sets)
            server.onAccessUnit(listOf(idr), keyFrame = true, ptsUs = 1_000_000L)
            val first = client.readFrame()
            assertEquals(0, first.first)
            assertArrayEqualsPayload(sps, first.second)
            assertArrayEqualsPayload(pps, client.readFrame().second)
            val idrPacket = client.readFrame().second
            assertArrayEqualsPayload(idr, idrPacket)
            assertTrue("marker on the last packet", idrPacket[1].toInt() and 0x80 != 0)
            assertEquals(90_000L, ((idrPacket[4].toLong() and 0xFF) shl 24) or ((idrPacket[5].toLong() and 0xFF) shl 16) or ((idrPacket[6].toLong() and 0xFF) shl 8) or (idrPacket[7].toLong() and 0xFF))

            val teardown = client.request("TEARDOWN", url, "Session: $session")
            assertEquals(200, teardown.status)
            await("lease release") { source.released.get() == 1 }
            await("client gone") { server.facts().clients == 0 }
        }
    }

    private fun assertArrayEqualsPayload(expected: ByteArray, packet: ByteArray) {
        assertEquals(expected.toList(), packet.copyOfRange(12, packet.size).toList())
    }

    @Test fun aClientThatDisconnectsWithoutTeardownStillGivesTheLeaseBack() {
        val source = FakeSource()
        val server = server(source)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { it.play(url) }
        await("lease release after a bare disconnect") { source.released.get() == 1 }
        assertEquals(0, server.facts().clients)
    }

    @Test fun aRefusedCameraReachesTheClientAsServiceUnavailableWithTheReasonAndTakesNoLease() {
        val source = FakeSource(refusal = CameraRefusal.PERMISSION)
        val server = server(source)
        Client(server.boundPort!!).use { client ->
            val describe = client.request("DESCRIBE", "rtsp://127.0.0.1:${server.boundPort}/live", "Accept: application/sdp")
            assertEquals(503, describe.status)
            assertEquals("camera-permission-needed", describe.headers["X-Camera"])
            assertEquals(0, source.acquired.get())
            assertEquals(0, server.facts().clients)
        }
    }

    @Test fun clientsBeyondTheCapacityAreRefusedAsBusy() {
        val source = FakeSource()
        val server = server(source, maxClients = 1)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { first ->
            first.play(url)
            Client(server.boundPort!!).use { second ->
                val describe = second.request("DESCRIBE", url)
                assertEquals(503, describe.status)
                assertEquals("camera-busy", describe.headers["X-Camera"])
            }
            assertEquals("only the first is attached", 1, source.acquired.get())
        }
    }

    @Test fun theSessionEndingDropsEveryClientAndReleasesEachLeaseExactlyOnce() {
        val source = FakeSource()
        val server = server(source)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { a ->
            Client(server.boundPort!!).use { b ->
                a.play(url)
                b.play(url)
                assertEquals(2, server.facts().clients)
                server.onStreamEnded()
                assertEquals("the client sees end of stream", -1, a.readOrTimeout())
                assertEquals(-1, b.readOrTimeout())
                await("both leases released") { source.released.get() == 2 }
                assertEquals(0, server.facts().clients)
            }
        }
        Thread.sleep(50)
        assertEquals("a client's own close after the drop releases nothing twice", 2, source.released.get())
    }

    @Test fun aClientThatCannotKeepUpIsShedWithoutBlockingTheEncoderThread() {
        val source = FakeSource()
        val server = server(source, queuePackets = 8)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { client ->
            client.play(url)
            server.onParameterSets(sets)
            val big = byteArrayOf(0x65) + ByteArray(200_000)
            var slowestCallMs = 0L
            var frames = 0
            await("the slow client is dropped", timeoutMs = 20_000) {
                val started = System.nanoTime()
                server.onAccessUnit(listOf(big), keyFrame = true, ptsUs = frames * 66_000L)
                frames++
                slowestCallMs = maxOf(slowestCallMs, (System.nanoTime() - started) / 1_000_000)
                server.facts().clients == 0
            }
            assertTrue("the encoder's thread never waited on the socket (slowest call ${slowestCallMs}ms)", slowestCallMs < 500)
            await("lease released") { source.released.get() == 1 }
        }
    }

    @Test fun aSilentClientIsDroppedByTheReadTimeoutSoADeadPeerCannotHoldTheCameraOpen() {
        val source = FakeSource()
        val server = server(source, readTimeoutMs = 300)
        Client(server.boundPort!!).use { client ->
            client.play("rtsp://127.0.0.1:${server.boundPort}/live")
            assertEquals(1, server.facts().clients)
            await("timeout drop", timeoutMs = 3_000) { source.released.get() == 1 }
            assertEquals(0, server.facts().clients)
        }
    }

    @Test fun interleavedReceiverReportsFromTheClientAreConsumedAndRequestsStillAnswered() {
        val source = FakeSource()
        val server = server(source)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { client ->
            val session = client.play(url)
            client.sendInterleaved(1, ByteArray(24) { 0x7F })
            val keepalive = client.request("GET_PARAMETER", url, "Session: $session")
            assertEquals(200, keepalive.status)
            assertEquals(1, server.facts().clients)
        }
    }

    @Test fun listeningFollowsTheSwitchAndStopTearsEveryThreadDown() {
        val source = FakeSource()
        val server = server(source)
        val port = requireNotNull(server.boundPort)
        Client(port).use { it.play("rtsp://127.0.0.1:$port/live") }
        server.setListening(false)
        assertNull(server.boundPort)
        assertEquals(StreamTransportFacts(port = null, clients = 0), server.facts())
        // The port is free again within a bounded time: a fresh listener can take it. The JDK defers
        // the real close of a ServerSocket until the thread blocked in accept() has unwound, so an
        // immediate probe races that by microseconds (one false red each way was seen); the property
        // is that nothing listens shortly after the switch, so the bind is polled. It binds with
        // SO_REUSEADDR so a TIME_WAIT remnant cannot fail it — Linux still refuses a bind while another
        // socket is LISTENING, which is exactly what is being asserted.
        await("the freed port can be bound again", timeoutMs = 2_000) {
            runCatching {
                java.net.ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress("127.0.0.1", port))
                }.close()
            }.isSuccess
        }
        server.setListening(true)
        assertNotNull("switching back on binds again", server.boundPort)
        server.stop()
        assertNull(server.boundPort)
        server.setListening(true)
        assertNull("a stopped transport never listens again", server.boundPort)
        await("threads gone") {
            Thread.getAllStackTraces().keys.none { it.isAlive && it.name.startsWith("camera-rtsp-") }
        }
        assertFalse(source.released.get() > source.acquired.get())
    }
}
