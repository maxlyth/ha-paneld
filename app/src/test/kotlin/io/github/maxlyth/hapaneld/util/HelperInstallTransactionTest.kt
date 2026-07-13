package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.DaemonLongResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HelperInstallTransactionTest {
    @get:Rule val temporary = TemporaryFolder()

    private class LongDaemon(
        var result: DaemonLongResult,
        private val onCall: (String, Long) -> Unit = { _, _ -> },
    ) : Daemon {
        val calls = mutableListOf<Pair<String, Long>>()

        override fun available() = true
        override fun send(cmd: String): String? = null
        override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult {
            calls += cmd to timeoutMs
            onCall(cmd, timeoutMs)
            return result
        }
        override fun sendBytes(cmd: String): ByteArray? = null
    }

    private fun apk(name: String, bytes: ByteArray): File =
        temporary.newFile(name).apply { writeBytes(bytes) }

    @Test fun terminalSuccessKeepsClaimedInputUntilReplyThenDeletesIt() {
        val source = apk("source.apk", byteArrayOf(1, 2, 3))
        val staging = temporary.newFolder("staging")
        val daemon = LongDaemon(DaemonLongResult.Reply("OK")) { cmd, timeout ->
            val owned = File(cmd.substringAfter("INSTALL "))
            assertFalse(source.exists())
            assertEquals(staging.canonicalFile, owned.parentFile?.canonicalFile)
            assertArrayEquals(byteArrayOf(1, 2, 3), owned.readBytes())
            assertEquals(180_000L, timeout)
        }

        assertEquals("OK", HelperInstallTransaction(daemon).install(source, staging))
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test fun terminalFailureAndDefiniteNonSubmissionReleaseStaging() {
        listOf(
            DaemonLongResult.Reply("ERR") to "install failed: daemon install failed",
            DaemonLongResult.NotSubmitted to "install failed: daemon unreachable",
        ).forEachIndexed { index, (daemonResult, expected) ->
            val source = apk("failed-$index.apk", byteArrayOf(index.toByte(), 7))
            val staging = temporary.newFolder("failed-staging-$index")
            val daemon = LongDaemon(daemonResult)

            assertEquals(expected, HelperInstallTransaction(daemon).install(source, staging))
            assertFalse(source.exists())
            assertTrue(staging.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun indeterminateSubmissionRetainsUniqueImmutableInput() {
        val staging = temporary.newFolder("retained-staging")
        val firstSource = apk("first.apk", byteArrayOf(1, 1, 1))
        val daemon = LongDaemon(DaemonLongResult.Indeterminate)
        val transaction = HelperInstallTransaction(daemon)

        assertEquals(
            "install outcome unknown: helper staging retained for safety",
            transaction.install(firstSource, staging),
        )
        val retained = staging.listFiles().orEmpty().single()
        val retainedBytes = retained.readBytes()

        daemon.result = DaemonLongResult.NotSubmitted
        val secondSource = apk("second.apk", byteArrayOf(2, 2, 2))
        assertEquals("install failed: daemon unreachable", transaction.install(secondSource, staging))
        val secondPath = daemon.calls.last().first.substringAfter("INSTALL ")

        assertNotEquals(retained.absolutePath, secondPath)
        assertTrue(retained.exists())
        assertArrayEquals(retainedBytes, retained.readBytes())
        assertEquals(listOf(retained), staging.listFiles().orEmpty().toList())
    }

    @Test fun customTimeoutIsPassedToLongCall() {
        val source = apk("timeout.apk", byteArrayOf(9))
        val staging = temporary.newFolder("timeout-staging")
        val daemon = LongDaemon(DaemonLongResult.NotSubmitted)

        HelperInstallTransaction(daemon, timeoutMs = 42_000L).install(source, staging)

        assertEquals(42_000L, daemon.calls.single().second)
    }

    @Test fun stagingFailureDeletesUnclaimedInputWithoutSubmitting() {
        val source = apk("unstageable.apk", byteArrayOf(4, 5))
        val notDirectory = temporary.newFile("not-a-directory")
        val daemon = LongDaemon(DaemonLongResult.Reply("OK"))

        assertEquals(
            "install failed: could not claim helper staging",
            HelperInstallTransaction(daemon).install(source, notDirectory),
        )
        assertFalse(source.exists())
        assertTrue(daemon.calls.isEmpty())
    }
}
