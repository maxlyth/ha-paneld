package io.github.maxlyth.hapaneld.shizuku

import io.github.maxlyth.hapaneld.util.StreamDeadline
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Minimal process surface for deterministic whole-install deadline tests. */
internal interface ShizukuInstallProcess {
    val input: OutputStream
    val output: InputStream
    fun waitFor(timeoutMs: Long): Boolean
    fun destroyForcibly()
}

/**
 * Streams one exact APK into one fixed `pm install` child. A single monotonic budget covers both the
 * copy and process completion; the independent watchdog closes blocked streams and kills the child.
 */
internal class ShizukuInstallRunner(
    private val timeoutMs: Long,
    private val nanoTime: () -> Long = System::nanoTime,
    private val deadlineFactory: (Long, () -> Unit) -> AutoCloseable = { timeout, onTimeout ->
        StreamDeadline(timeout, onTimeout)
    },
) {
    fun run(source: InputStream, expectedBytes: Long, process: ShizukuInstallProcess): String? {
        require(timeoutMs > 0L)
        val budget = InstallTimeBudget(timeoutMs, nanoTime)
        val timedOut = AtomicBoolean(false)
        val reply = ByteArrayOutputStream()
        val reader = thread(name = "hapaneld-shizuku-install-output", isDaemon = true) {
            runCatching { copyBounded(process.output, reply, MAX_REPLY_BYTES.toLong()) }
        }
        val deadline = deadlineFactory(timeoutMs) {
            timedOut.set(true)
            closeQuietly(source)
            closeQuietly(process.input)
            closeQuietly(process.output)
            runCatching { process.destroyForcibly() }
        }
        var exited = false
        return try {
            copyExact(source, process.input, expectedBytes)
            process.input.flush()
            closeQuietly(process.input)

            val waitMs = budget.remainingMs()
            if (timedOut.get() || waitMs <= 0L || !process.waitFor(waitMs)) {
                null
            } else {
                exited = true
                val readerWaitMs = budget.remainingMs()
                if (timedOut.get() || readerWaitMs <= 0L) {
                    null
                } else {
                    reader.join(readerWaitMs)
                    if (timedOut.get() || reader.isAlive) null
                    else reply.toString(Charsets.UTF_8.name()).trim().take(MAX_REPLY_BYTES)
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            deadline.close()
            closeQuietly(source)
            closeQuietly(process.input)
            closeQuietly(process.output)
            if (!exited || timedOut.get()) {
                runCatching { process.destroyForcibly() }
                runCatching { process.waitFor(CLEANUP_TIMEOUT_MS) }
            }
            if (reader.isAlive) reader.join(CLEANUP_TIMEOUT_MS)
        }
    }

    private fun copyExact(input: InputStream, output: OutputStream, expected: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (copied < expected) {
            val wanted = minOf(buffer.size.toLong(), expected - copied).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read < 0) throw EOFException("short APK stream")
            if (read == 0) continue
            output.write(buffer, 0, read)
            copied += read
        }
        if (input.read() != -1) throw java.io.IOException("extra APK bytes")
    }

    private fun copyBounded(input: InputStream, output: OutputStream, max: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            if (read == 0) continue
            copied += read
            if (copied > max) throw java.io.IOException("stream too large")
            output.write(buffer, 0, read)
        }
    }

    private fun closeQuietly(closeable: AutoCloseable) {
        runCatching { closeable.close() }
    }

    private class InstallTimeBudget(timeoutMs: Long, private val nanoTime: () -> Long) {
        private val deadlineNanos = saturatingAdd(nanoTime(), millisToNanos(timeoutMs))

        fun remainingMs(): Long {
            val remainingNanos = deadlineNanos - nanoTime()
            if (remainingNanos <= 0L) return 0L
            return ((remainingNanos - 1L) / NANOS_PER_MILLISECOND) + 1L
        }

        private fun millisToNanos(value: Long): Long =
            if (value > Long.MAX_VALUE / NANOS_PER_MILLISECOND) Long.MAX_VALUE
            else value * NANOS_PER_MILLISECOND

        private fun saturatingAdd(left: Long, right: Long): Long =
            if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }

    private companion object {
        const val MAX_REPLY_BYTES = 16 * 1024
        const val CLEANUP_TIMEOUT_MS = 1_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
