package io.github.maxlyth.hapaneld.util

import android.net.LocalSocket
import android.util.Log
import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.DaemonLongResult
import io.github.maxlyth.hapaneld.platform.DaemonStreamResult
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

const val SUPPORTED_HELPER_PROTOCOL_MAJOR = 1

data class HelperIdentity(
    val version: String,
    val protocolMajor: Int,
    val protocolMinor: Int,
)

enum class HelperIdentityIssue {
    MALFORMED_IDENTITY,
    UNSUPPORTED_PROTOCOL,
}

sealed interface HelperIdentityStatus {
    data class Compatible(val identity: HelperIdentity) : HelperIdentityStatus
    data object ReachableUnverified : HelperIdentityStatus
    data object Missing : HelperIdentityStatus
    data class Incompatible(
        val identity: HelperIdentity?,
        val issue: HelperIdentityIssue,
    ) : HelperIdentityStatus
}

private const val MAX_IDENTITY_REPLY_CHARS = 128
private val HELPER_SEMVER = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""")
private val HELPER_PROTOCOL = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)""")
private val IDENTITY_KEY = Regex("""[a-z][a-z0-9_]*""")

internal fun parseHelperIdentity(reply: String): HelperIdentity? {
    if (reply.isEmpty() || reply.length > MAX_IDENTITY_REPLY_CHARS) return null
    if (reply.any { it.code !in 0x20..0x7e }) return null
    val tokens = reply.split(' ')
    if (tokens.firstOrNull() != "HELPER" || tokens.any(String::isEmpty)) return null

    val fields = linkedMapOf<String, String>()
    for (token in tokens.drop(1)) {
        val separator = token.indexOf('=')
        if (separator <= 0 || separator == token.lastIndex) return null
        val key = token.substring(0, separator)
        val value = token.substring(separator + 1)
        if (!IDENTITY_KEY.matches(key) || fields.put(key, value) != null) return null
    }

    val version = fields["version"] ?: return null
    if (!HELPER_SEMVER.matches(version)) return null
    val protocol = HELPER_PROTOCOL.matchEntire(fields["proto"] ?: return null) ?: return null
    val major = protocol.groupValues[1].toIntOrNull() ?: return null
    val minor = protocol.groupValues[2].toIntOrNull() ?: return null
    return HelperIdentity(version, major, minor)
}

private fun classifyHelperIdentity(reply: String): HelperIdentityStatus {
    val identity = parseHelperIdentity(reply)
        ?: return HelperIdentityStatus.Incompatible(null, HelperIdentityIssue.MALFORMED_IDENTITY)
    return if (identity.protocolMajor == SUPPORTED_HELPER_PROTOCOL_MAJOR) {
        HelperIdentityStatus.Compatible(identity)
    } else {
        HelperIdentityStatus.Incompatible(identity, HelperIdentityIssue.UNSUPPORTED_PROTOCOL)
    }
}

private const val HELPER_BOOTSTRAP_TIMEOUT_MS = 1_000L

internal sealed interface HelperBootstrapReply {
    data class Line(val value: String) : HelperBootstrapReply
    data object Missing : HelperBootstrapReply
    data object Malformed : HelperBootstrapReply
}

/** One absolute monotonic budget shared by VERSION and the optional legacy PING. */
internal class HelperBootstrapDeadline(
    timeoutMs: Long = HELPER_BOOTSTRAP_TIMEOUT_MS,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private val budgetNs: Long
    private val startedNs: Long

    init {
        require(timeoutMs in 1..Int.MAX_VALUE.toLong())
        budgetNs = timeoutMs * 1_000_000L
        startedNs = nowNs()
    }

    fun nextReadTimeoutMs(): Int? {
        val elapsedNs = nowNs() - startedNs
        if (elapsedNs < 0L || elapsedNs >= budgetNs) return null
        val remainingNs = budgetNs - elapsedNs
        return (((remainingNs - 1L) / 1_000_000L) + 1L).toInt()
    }
}

/**
 * Read one exact ASCII bootstrap line into fixed storage. Socket timeouts are reset to the remaining
 * absolute budget before every read, so a sender cannot extend the deadline by trickling bytes.
 */
internal fun readHelperBootstrapLine(
    input: InputStream,
    setReadTimeoutMs: (Int) -> Unit,
    deadline: HelperBootstrapDeadline,
): HelperBootstrapReply {
    val bytes = ByteArray(MAX_IDENTITY_REPLY_CHARS + 1)
    var used = 0
    while (true) {
        val timeoutMs = deadline.nextReadTimeoutMs()
            ?: return if (used == 0) HelperBootstrapReply.Missing else HelperBootstrapReply.Malformed
        val next = try {
            setReadTimeoutMs(timeoutMs)
            input.read()
        } catch (_: Exception) {
            return if (used == 0) HelperBootstrapReply.Missing else HelperBootstrapReply.Malformed
        }
        if (next < 0) {
            return if (used == 0) HelperBootstrapReply.Missing else HelperBootstrapReply.Malformed
        }
        if (next == '\n'.code) {
            return HelperBootstrapReply.Line(String(bytes, 0, used, StandardCharsets.US_ASCII))
        }
        if (next !in 0x20..0x7e) return HelperBootstrapReply.Malformed
        bytes[used++] = next.toByte()
        if (used > MAX_IDENTITY_REPLY_CHARS) return HelperBootstrapReply.Malformed
    }
}

internal interface HelperCommandSession : AutoCloseable {
    fun bootstrap(command: String, deadline: HelperBootstrapDeadline): HelperBootstrapReply
    fun send(cmd: String): String?
    fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult
    fun sendFile(cmd: String, source: File, timeoutMs: Long): DaemonStreamResult
    fun sendBytes(cmd: String, maxBytes: Long): ByteArray?
    fun backupCompanion(packageName: String, cacheDir: File, timeoutMs: Long): CompanionHelperProtocol.BackupResult
    fun restoreCompanion(
        packageName: String,
        files: Map<String, File>,
        timeoutMs: Long,
    ): CompanionHelperProtocol.RestoreResult
}

internal interface HelperCommandTransport {
    fun open(): HelperCommandSession?
}

private data class HelperIdentityProbe(
    val status: HelperIdentityStatus,
    val rawFallbackAllowed: Boolean = false,
)

/** Applies a bootstrap decision on the same connection that will receive the privileged operation. */
internal class IdentityAdmittingHelperClient(
    private val transport: HelperCommandTransport,
    private val nowNs: () -> Long = System::nanoTime,
    private val bootstrapTimeoutMs: Long = HELPER_BOOTSTRAP_TIMEOUT_MS,
) : Daemon {
    init {
        require(bootstrapTimeoutMs in 1..Int.MAX_VALUE.toLong())
    }

    fun identityStatus(): HelperIdentityStatus {
        val session = transport.open() ?: return HelperIdentityStatus.Missing
        return session.use { probeIdentity(it, newDeadline()).status }
    }

    override fun available(): Boolean = execute(null) { it.send("PING") } == "OK"

    override fun send(cmd: String): String? = execute(null) { it.send(cmd) }

    override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult =
        execute(DaemonLongResult.NotSubmitted) { it.sendLong(cmd, timeoutMs) }

    override fun sendFile(cmd: String, source: File, timeoutMs: Long): DaemonStreamResult =
        execute(DaemonStreamResult.NotSubmitted) { it.sendFile(cmd, source, timeoutMs) }

    override fun sendBytes(cmd: String): ByteArray? =
        execute(null) { it.sendBytes(cmd, Long.MAX_VALUE - 1L) }

    override fun sendBytesBounded(cmd: String, maxBytes: Long): ByteArray? =
        execute(null) { it.sendBytes(cmd, maxBytes) }

    fun backupCompanion(
        packageName: String,
        cacheDir: File,
        timeoutMs: Long,
    ): CompanionHelperProtocol.BackupResult = execute(
        CompanionHelperProtocol.BackupResult.NotSubmitted,
        requireCompanionCapability = true,
    ) { it.backupCompanion(packageName, cacheDir, timeoutMs) }

    fun restoreCompanion(
        packageName: String,
        files: Map<String, File>,
        timeoutMs: Long,
    ): CompanionHelperProtocol.RestoreResult = execute(
        CompanionHelperProtocol.RestoreResult.NOT_SUBMITTED,
        requireCompanionCapability = true,
    ) { it.restoreCompanion(packageName, files, timeoutMs) }

    private fun newDeadline(): HelperBootstrapDeadline =
        HelperBootstrapDeadline(bootstrapTimeoutMs, nowNs)

    private fun probeIdentity(
        session: HelperCommandSession,
        deadline: HelperBootstrapDeadline,
    ): HelperIdentityProbe = when (val version = session.bootstrap("VERSION", deadline)) {
        HelperBootstrapReply.Missing -> HelperIdentityProbe(HelperIdentityStatus.Missing, rawFallbackAllowed = true)
        HelperBootstrapReply.Malformed -> HelperIdentityProbe(
            HelperIdentityStatus.Incompatible(null, HelperIdentityIssue.MALFORMED_IDENTITY),
        )
        is HelperBootstrapReply.Line -> {
            val status = if (version.value == "ERR") {
                if (session.bootstrap("PING", deadline) == HelperBootstrapReply.Line("OK")) {
                    HelperIdentityStatus.ReachableUnverified
                } else {
                    HelperIdentityStatus.Missing
                }
            } else {
                classifyHelperIdentity(version.value)
            }
            HelperIdentityProbe(status)
        }
    }

    private fun openAdmittedSession(requireCompanionCapability: Boolean): HelperCommandSession? {
        var session = transport.open() ?: return null
        var deadline = newDeadline()
        val identity = probeIdentity(session, deadline)
        when (identity.status) {
            is HelperIdentityStatus.Incompatible -> {
                session.close()
                return null
            }
            HelperIdentityStatus.Missing -> {
                if (!identity.rawFallbackAllowed) {
                    session.close()
                    return null
                }
                // A VERSION-unaware helper may close or ignore the bootstrap connection. Preserve the
                // historical command attempt on one fresh connection. An explicit VERSION reply,
                // including ERR followed by any non-OK PING outcome, is never eligible for this fallback.
                session.close()
                session = transport.open() ?: return null
                deadline = newDeadline()
            }
            is HelperIdentityStatus.Compatible,
            HelperIdentityStatus.ReachableUnverified -> Unit
        }
        if (requireCompanionCapability &&
            session.bootstrap("COMPANIONCAPS", deadline) != HelperBootstrapReply.Line(COMPANION_CAPABILITY_VERSION)
        ) {
            session.close()
            return null
        }
        return session
    }

    private inline fun <T> execute(
        blocked: T,
        requireCompanionCapability: Boolean = false,
        operation: (HelperCommandSession) -> T,
    ): T {
        val session = openAdmittedSession(requireCompanionCapability) ?: return blocked
        return session.use(operation)
    }
}

/**
 * Client for the root helper daemon (`helper/hapaneld-helper`) over an **abstract-namespace UNIX
 * socket** (`@hapaneld-helper`). The app (`untrusted_app`) cannot write the root-only sysfs nodes the
 * daemon owns (LED + backlight power), so it asks the daemon. The daemon authenticates us by uid
 * (`SO_PEERCRED`) and rejects any other app — which is why this is a UNIX socket, not the old
 * unauthenticated `127.0.0.1:8889` TCP. Used by the sysfs LED + screen controllers (and others).
 * Calls are blocking socket I/O with verb-appropriate bounds — invoke off the main thread.
 */
object HelperClient : Daemon by admittedHelperClient {

    /**
     * Read-only bootstrap probe for provisioning and diagnostics. Ordinary operations repeat this
     * probe on their own connection; a deployed pre-VERSION helper remains reachable but unverified.
     */
    fun identityStatus(): HelperIdentityStatus = admittedHelperClient.identityStatus()

    /** Exact Companion-data protocol admission; a generic PING is not sufficient for older helpers. */
    internal fun supportsCompanionData(): Boolean = companionCapabilitySupported(send("COMPANIONCAPS"))

    /** Exact source identity of the helper carried by this APK. */
    internal fun matchesBundledHelper(): Boolean = helperBuildIdentitySupported(send("BUILDID"), BuildConfig.HELPER_BUILD_ID)

    /** Non-blocking transaction state used to retain app-side launch suppression after a client timeout. */
    internal fun companionOperationStatus(): CompanionOperationStatus =
        parseCompanionOperationStatus(send("COMPANIONSTATUS"))

    /** Descriptor-confined raw Companion capture. The helper owns stop/open/relaunch as one operation. */
    internal fun backupCompanion(
        packageName: String,
        cacheDir: File,
        timeoutMs: Long = COMPANION_OPERATION_TIMEOUT_MS,
    ): CompanionHelperProtocol.BackupResult =
        admittedHelperClient.backupCompanion(packageName, cacheDir, timeoutMs)

    /** Atomic descriptor-confined Companion restore. No shell or pathname fallback is permitted. */
    internal fun restoreCompanion(
        packageName: String,
        files: Map<String, File>,
        timeoutMs: Long = COMPANION_OPERATION_TIMEOUT_MS,
    ): CompanionHelperProtocol.RestoreResult =
        admittedHelperClient.restoreCompanion(packageName, files, timeoutMs)

    private const val COMPANION_OPERATION_TIMEOUT_MS = 120_000L
}

private const val SOCK = "hapaneld-helper" // matches helper/src/main.c

private val admittedHelperClient = IdentityAdmittingHelperClient(
    LocalSocketHelperTransport(
        connect = { openRootAbstractSocket(SOCK) },
        tag = "ha-paneld/helper",
    ),
)

private class LocalSocketHelperTransport(
    private val connect: () -> LocalSocket,
    private val tag: String,
) : HelperCommandTransport {
    override fun open(): HelperCommandSession? = try {
        LocalSocketHelperSession(connect(), tag)
    } catch (e: Exception) {
        Log.d(tag, "daemon not reachable (${e.message})")
        null
    }
}

private class LocalSocketHelperSession(
    private val socket: LocalSocket,
    private val tag: String,
) : HelperCommandSession {
    private val input = socket.inputStream
    private val output = socket.outputStream

    override fun bootstrap(command: String, deadline: HelperBootstrapDeadline): HelperBootstrapReply = try {
        require(command == "VERSION" || command == "PING" || command == "COMPANIONCAPS")
        output.apply { write((command + "\n").toByteArray(StandardCharsets.US_ASCII)); flush() }
        readHelperBootstrapLine(input, { socket.soTimeout = it }, deadline)
    } catch (_: Exception) {
        HelperBootstrapReply.Missing
    }

    override fun send(cmd: String): String? = try {
        socket.soTimeout = SHORT_TIMEOUT_MS
        HelperSocketProtocol.sendLine(cmd, input, output)
    } catch (e: Exception) {
        Log.d(tag, "daemon not reachable (${e.message})")
        null
    }

    override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult {
        var submissionBegan = false
        return try {
            socket.soTimeout = timeoutMs.asSocketTimeout()
            submissionBegan = true
            HelperSocketProtocol.sendLine(cmd, input, output)
                ?.let(DaemonLongResult::Reply)
                ?: DaemonLongResult.Indeterminate
        } catch (e: Exception) {
            Log.d(tag, "daemon long call ${if (submissionBegan) "indeterminate" else "not submitted"} (${e.message})")
            if (submissionBegan) DaemonLongResult.Indeterminate else DaemonLongResult.NotSubmitted
        }
    }

    override fun sendFile(cmd: String, source: File, timeoutMs: Long): DaemonStreamResult {
        val deadline = StreamDeadline(timeoutMs) { runCatching { socket.close() } }
        return try {
            var submissionBegan = false
            try {
                socket.soTimeout = timeoutMs.asSocketTimeout()
                submissionBegan = true
                HelperSocketProtocol.sendFile(
                    command = cmd,
                    openSource = source::inputStream,
                    expectedBytes = source.length(),
                    input = input,
                    output = output,
                    shutdownOutput = socket::shutdownOutput,
                )
            } catch (e: Exception) {
                Log.d(tag, "daemon stream ${if (submissionBegan) "indeterminate" else "not submitted"} (${e.message})")
                if (submissionBegan) DaemonStreamResult.Indeterminate else DaemonStreamResult.NotSubmitted
            }
        } finally {
            deadline.close()
        }
    }

    override fun sendBytes(cmd: String, maxBytes: Long): ByteArray? = try {
        socket.soTimeout = BYTE_TIMEOUT_MS
        HelperSocketProtocol.sendBytes(
            cmd,
            input,
            output,
            socket::shutdownOutput,
            maxBytes,
        )
    } catch (e: Exception) {
        Log.d(tag, "daemon bytes failed (${e.message})")
        null
    }

    override fun backupCompanion(
        packageName: String,
        cacheDir: File,
        timeoutMs: Long,
    ): CompanionHelperProtocol.BackupResult {
        val deadline = StreamDeadline(timeoutMs) { runCatching { socket.close() } }
        return try {
            socket.soTimeout = timeoutMs.asSocketTimeout()
            CompanionHelperProtocol.backup(packageName, cacheDir, input, output)
        } catch (e: Exception) {
            Log.w(tag, "Companion backup exchange failed", e)
            CompanionHelperProtocol.BackupResult.Indeterminate
        } finally {
            deadline.close()
        }
    }

    override fun restoreCompanion(
        packageName: String,
        files: Map<String, File>,
        timeoutMs: Long,
    ): CompanionHelperProtocol.RestoreResult {
        val deadline = StreamDeadline(timeoutMs) { runCatching { socket.close() } }
        return try {
            socket.soTimeout = timeoutMs.asSocketTimeout()
            CompanionHelperProtocol.restore(
                packageName,
                files,
                input,
                output,
                socket::shutdownOutput,
            )
        } catch (e: Exception) {
            Log.w(tag, "Companion restore exchange failed", e)
            CompanionHelperProtocol.RestoreResult.INDETERMINATE
        } finally {
            deadline.close()
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun Long.asSocketTimeout(): Int = coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        const val SHORT_TIMEOUT_MS = 500
        const val BYTE_TIMEOUT_MS = 5_000
    }
}

private const val COMPANION_CAPABILITY_VERSION = "COMPANIONCAPS 1 BACKUP RESTORE STATUS JOURNAL"

internal fun companionCapabilitySupported(reply: String?): Boolean = reply == COMPANION_CAPABILITY_VERSION

internal fun helperBuildIdentitySupported(reply: String?, expectedBuildId: String): Boolean =
    expectedBuildId.matches(Regex("[0-9a-f]{64}")) && reply == "BUILDID $expectedBuildId"

internal fun parseCompanionOperationStatus(reply: String?): CompanionOperationStatus = when (reply) {
    "IDLE" -> CompanionOperationStatus.IDLE
    "BUSY" -> CompanionOperationStatus.BUSY
    "ERR" -> CompanionOperationStatus.UNSUPPORTED
    else -> CompanionOperationStatus.UNAVAILABLE
}

internal enum class CompanionOperationStatus {
    IDLE,
    BUSY,
    UNSUPPORTED,
    UNAVAILABLE,
}
