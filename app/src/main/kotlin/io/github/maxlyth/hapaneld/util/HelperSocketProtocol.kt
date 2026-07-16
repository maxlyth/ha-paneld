package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.platform.DaemonStreamResult
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

/** Helper command framing shared by the Android local-socket client and host composition tests. */
internal object HelperSocketProtocol {
    fun sendLine(command: String, input: InputStream, output: OutputStream): String? {
        output.apply { write((command + "\n").toByteArray()); flush() }
        return BufferedReader(InputStreamReader(input)).readLine()?.trim()
    }

    fun sendBytes(
        command: String,
        input: InputStream,
        output: OutputStream,
        shutdownOutput: () -> Unit,
        maxBytes: Long = Long.MAX_VALUE - 1L,
    ): ByteArray? {
        output.apply { write((command + "\n").toByteArray()); flush() }
        shutdownOutput()
        return BoundedStreams.readBytes(input, maxBytes).takeIf { it.isNotEmpty() }
    }

    fun sendFile(
        command: String,
        openSource: () -> InputStream,
        expectedBytes: Long,
        input: InputStream,
        output: OutputStream,
        shutdownOutput: () -> Unit,
    ): DaemonStreamResult = DaemonStreamProtocol.exchange(
        command = command,
        openSource = openSource,
        expectedBytes = expectedBytes,
        replies = BufferedReader(InputStreamReader(input)),
        output = output,
        shutdownOutput = shutdownOutput,
    )
}
