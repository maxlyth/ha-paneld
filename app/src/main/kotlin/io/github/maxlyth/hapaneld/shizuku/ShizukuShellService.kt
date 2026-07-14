package io.github.maxlyth.hapaneld.shizuku

import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Code loaded by Shizuku into a separate UID-2000 process. Every operation has a fixed executable and
 * typed arguments; this class intentionally cannot evaluate a caller-provided command line.
 */
class ShizukuShellService : IShizukuShellService.Stub() {
    override fun protocolVersion(): Int = ShizukuPolicy.PROTOCOL_VERSION

    override fun identityUid(): Int = Process.myUid()

    override fun captureScreenshot(): ParcelFileDescriptor? {
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return null
        thread(name = "hapaneld-shizuku-screencap", isDaemon = true) {
            pipe[1].use { writeFd ->
                val process = runCatching { ProcessBuilder("screencap", "-p").start() }.getOrNull()
                    ?: return@thread
                ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { output ->
                    val pump = thread(name = "hapaneld-shizuku-screencap-output", isDaemon = true) {
                        runCatching { copyBounded(process.inputStream, output, ShizukuPolicy.MAX_SCREENSHOT_BYTES.toLong()) }
                    }
                    if (!process.waitFor(SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                    pump.join(1_000)
                }
            }
        }
        return pipe[0]
    }

    override fun inputKey(keyCode: Int): Boolean =
        ShizukuPolicy.validKeyCode(keyCode) && runEffect("input", "keyevent", keyCode.toString())

    override fun inputTap(x: Int, y: Int): Boolean =
        ShizukuPolicy.validCoordinate(x) && ShizukuPolicy.validCoordinate(y) &&
            runEffect("input", "tap", x.toString(), y.toString())

    override fun readDensity(): String? = runText("wm", "density")

    override fun setDensity(dpi: Int): Boolean =
        ShizukuPolicy.validDensity(dpi) && runEffect("wm", "density", dpi.toString())

    override fun resetDensity(): Boolean = runEffect("wm", "density", "reset")

    override fun readFontScale(): String? = runText("settings", "get", "system", "font_scale")

    override fun setFontScale(scale: Float): Boolean =
        ShizukuPolicy.validFontScale(scale) &&
            runEffect("settings", "put", "system", "font_scale", scale.toString())

    override fun resetFontScale(): Boolean =
        runEffect("settings", "delete", "system", "font_scale")

    override fun installApk(
        source: ParcelFileDescriptor?,
        length: Long,
        allowDowngrade: Boolean,
    ): String? {
        if (source == null || !ShizukuPolicy.validApkLength(length)) return null
        val args = mutableListOf("pm", "install", "-S", length.toString(), "-r")
        if (allowDowngrade) args += "-d"
        val process = runCatching { ProcessBuilder(args).redirectErrorStream(true).start() }.getOrNull()
            ?: return null
        return try {
            ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
                process.outputStream.use { output -> copyExact(input, output, length) }
            }
            val reply = ByteArrayOutputStream()
            val reader = thread(name = "hapaneld-shizuku-install-output", isDaemon = true) {
                runCatching { copyBounded(process.inputStream, reply, MAX_REPLY_BYTES.toLong()) }
            }
            if (!process.waitFor(INSTALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                reader.join(1_000)
                null
            } else {
                reader.join(1_000)
                reply.toString(Charsets.UTF_8.name()).trim().take(MAX_REPLY_BYTES)
            }
        } catch (_: Exception) {
            process.destroyForcibly()
            null
        }
    }

    override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
        if (code == DESTROY_TRANSACTION) {
            thread(name = "hapaneld-shizuku-destroy", isDaemon = true) { exitProcess(0) }
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    private fun runEffect(vararg args: String): Boolean =
        runProcess(args.toList())?.first == 0

    private fun runText(vararg args: String): String? =
        runProcess(args.toList())?.takeIf { it.first == 0 }?.second?.trim()

    private fun runProcess(args: List<String>): Pair<Int, String>? {
        val process = runCatching { ProcessBuilder(args).redirectErrorStream(true).start() }.getOrNull()
            ?: return null
        return try {
            val output = ByteArrayOutputStream()
            val reader = thread(name = "hapaneld-shizuku-output", isDaemon = true) {
                runCatching { copyBounded(process.inputStream, output, MAX_REPLY_BYTES.toLong()) }
            }
            if (!process.waitFor(SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                reader.join(1_000)
                null
            } else {
                reader.join(1_000)
                process.exitValue() to output.toString(Charsets.UTF_8.name())
            }
        } catch (_: Exception) {
            process.destroyForcibly()
            null
        }
    }

    private fun copyExact(input: java.io.InputStream, output: java.io.OutputStream, expected: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (copied < expected) {
            val wanted = minOf(buffer.size.toLong(), expected - copied).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read < 0) throw java.io.EOFException("short APK stream")
            output.write(buffer, 0, read)
            copied += read
        }
        if (input.read() != -1) throw java.io.IOException("extra APK bytes")
    }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, max: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            copied += read
            if (copied > max) throw java.io.IOException("stream too large")
            output.write(buffer, 0, read)
        }
    }

    companion object {
        private const val SHORT_TIMEOUT_MS = 10_000L
        private const val INSTALL_TIMEOUT_MS = 180_000L
        private const val MAX_REPLY_BYTES = 16 * 1024
        private const val DESTROY_TRANSACTION = 16_777_115
    }
}
