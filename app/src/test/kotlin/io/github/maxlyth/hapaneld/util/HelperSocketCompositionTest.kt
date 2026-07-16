package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.DaemonLongResult
import io.github.maxlyth.hapaneld.platform.DaemonStreamResult
import java.io.File
import java.net.SocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test

class HelperSocketCompositionTest {
    @Test(timeout = 10_000)
    fun textFramingCrossesNativeServerAndExactDispatch() {
        val daemon = SocketDaemon(socketPath)

        assertTrue(daemon.available())
        assertEquals("HELPER version=1.0.0 proto=1.0", daemon.send("VERSION"))
        assertEquals("ERR", daemon.send("PINGEXTRA"))
        assertEquals("OK", daemon.sendLong("RELOAD io.example.dashboard", 5_000).replyValue())
    }

    @Test(timeout = 10_000)
    fun binaryReplyUsesHalfCloseAndReadsThroughNativeEof() {
        assertContentEquals("PNG\nfixture\n".toByteArray(), SocketDaemon(socketPath).sendBytes("SCREENCAP"))
    }

    @Test(timeout = 10_000)
    fun installTransactionCompletesTwoPhaseNativeStreamAndReleasesInput() {
        val directory = Files.createTempDirectory("helper-socket-install-").toFile()
        val source = File(directory, "fixture.apk").apply { writeBytes(byteArrayOf(0x50, 0x4b, 0, 4, 0x7f)) }
        try {
            assertEquals("OK", HelperInstallTransaction(SocketDaemon(socketPath)).install(source, File(directory, "staging")))
            assertFalse(source.exists())
            assertFalse(File("/tmp/hapaneld-helper-install-stream-test.apk").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(timeout = 10_000)
    fun nativeStreamPreflightRejectsInvalidLengthBeforeOpeningPayload() {
        var opened = false
        val result = SocketDaemon(socketPath).withConnection { channel ->
            HelperSocketProtocol.sendFile(
                command = "INSTALLSTREAM 0",
                openSource = { opened = true; error("native preflight must reject before payload") },
                expectedBytes = 0,
                input = Channels.newInputStream(channel),
                output = Channels.newOutputStream(channel),
                shutdownOutput = channel::shutdownOutput,
            )
        }

        assertEquals(DaemonStreamResult.Reply("STREAMERR"), result)
        assertFalse(opened)
    }

    private class SocketDaemon(private val path: Path) : Daemon {
        override fun available(): Boolean = send("PING") == "OK"

        override fun send(cmd: String): String? = withConnection { channel ->
            HelperSocketProtocol.sendLine(cmd, Channels.newInputStream(channel), Channels.newOutputStream(channel))
        }

        override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult = send(cmd)
            ?.let(DaemonLongResult::Reply)
            ?: DaemonLongResult.Indeterminate

        override fun sendFile(cmd: String, source: File, timeoutMs: Long): DaemonStreamResult = withConnection { channel ->
            HelperSocketProtocol.sendFile(
                command = cmd,
                openSource = source::inputStream,
                expectedBytes = source.length(),
                input = Channels.newInputStream(channel),
                output = Channels.newOutputStream(channel),
                shutdownOutput = channel::shutdownOutput,
            )
        }

        override fun sendBytes(cmd: String): ByteArray? = withConnection { channel ->
            HelperSocketProtocol.sendBytes(
                command = cmd,
                input = Channels.newInputStream(channel),
                output = Channels.newOutputStream(channel),
                shutdownOutput = channel::shutdownOutput,
            )
        }

        fun <T> withConnection(block: (SocketChannel) -> T): T = SocketChannel.open(unixAddress(path)).use { channel ->
            block(channel)
        }

        private fun unixAddress(path: Path): SocketAddress = Class.forName("java.net.UnixDomainSocketAddress")
            .getMethod("of", Path::class.java)
            .invoke(null, path) as SocketAddress
    }

    private companion object {
        lateinit var socketPath: Path
        lateinit var server: Process

        @JvmStatic
        @BeforeClass
        fun startServer() {
            val executablePath = System.getProperty("hapaneld.helper.socketTestServer")
            assumeTrue("native UNIX-socket composition requires a Linux host", executablePath != null)
            val executable = File(requireNotNull(executablePath))
            assertTrue(executable.isFile, "native socket test server was not built")
            socketPath = Path.of(System.getProperty("java.io.tmpdir"), "hapaneld-helper-${UUID.randomUUID()}.sock")
            server = ProcessBuilder(executable.absolutePath, socketPath.toString())
                .redirectErrorStream(true)
                .start()
            val ready = server.inputStream.bufferedReader().readLine()
            assertEquals("READY", ready, "native socket test server did not start")
            assertTrue(server.isAlive)
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            if (::server.isInitialized) {
                server.destroy()
                if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroyForcibly()
            }
            if (::socketPath.isInitialized) Files.deleteIfExists(socketPath)
            Files.deleteIfExists(Path.of("/tmp/hapaneld-helper-install-stream-test.apk"))
        }

        fun DaemonLongResult.replyValue(): String? = (this as? DaemonLongResult.Reply)?.value
    }
}
