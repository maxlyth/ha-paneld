package io.github.maxlyth.hapaneld.util

import android.net.LocalSocket
import android.util.Log
import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.DaemonLongResult
import io.github.maxlyth.hapaneld.platform.DaemonStreamResult
import java.io.File

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

internal fun probeHelperIdentity(request: (String) -> String?): HelperIdentityStatus {
    val reply = runCatching { request("VERSION") }.getOrNull()
        ?: return HelperIdentityStatus.Missing
    if (reply == "ERR") {
        return if (runCatching { request("PING") }.getOrNull() == "OK") {
            HelperIdentityStatus.ReachableUnverified
        } else {
            HelperIdentityStatus.Missing
        }
    }

    val identity = parseHelperIdentity(reply)
        ?: return HelperIdentityStatus.Incompatible(null, HelperIdentityIssue.MALFORMED_IDENTITY)
    return if (identity.protocolMajor == SUPPORTED_HELPER_PROTOCOL_MAJOR) {
        HelperIdentityStatus.Compatible(identity)
    } else {
        HelperIdentityStatus.Incompatible(identity, HelperIdentityIssue.UNSUPPORTED_PROTOCOL)
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
object HelperClient : Daemon {
    private const val SOCK = "hapaneld-helper"   // abstract socket name; matches SOCK_NAME in main.c
    private const val TIMEOUT_MS = 500
    private const val TAG = "ha-paneld/helper"

    // Abstract local sockets have no connect timeout (no network round-trip): connect returns at once
    // if the daemon is listening, else throws — caught by the callers below.
    private fun open(): LocalSocket = openRootAbstractSocket(SOCK)

    /** True when the daemon answers `PING`. */
    override fun available(): Boolean = send("PING") == "OK"

    /**
     * Read-only bootstrap probe for provisioning and diagnostics. A deployed pre-VERSION helper
     * remains reachable but unverified; ordinary [Daemon] calls retain their existing behavior.
     */
    fun identityStatus(): HelperIdentityStatus = probeHelperIdentity(::requestRaw)

    /** Exact Companion-data protocol admission; a generic PING is not sufficient for older helpers. */
    internal fun supportsCompanionData(): Boolean = companionCapabilitySupported(send("COMPANIONCAPS"))

    /** Exact source identity of the helper carried by this APK. */
    internal fun matchesBundledHelper(): Boolean = helperBuildIdentitySupported(send("BUILDID"), BuildConfig.HELPER_BUILD_ID)

    /** Non-blocking transaction state used to retain app-side launch suppression after a client timeout. */
    internal fun companionOperationStatus(): CompanionOperationStatus =
        parseCompanionOperationStatus(send("COMPANIONSTATUS"))

    /** Send one command; return the daemon's reply line (trimmed), or null if unreachable. */
    override fun send(cmd: String): String? = requestRaw(cmd)

    private fun requestRaw(cmd: String): String? = try {
        open().use { s ->
            s.soTimeout = TIMEOUT_MS
            HelperSocketProtocol.sendLine(cmd, s.inputStream, s.outputStream)
        }
    } catch (e: Exception) {
        Log.d(TAG, "daemon not reachable (${e.message})")
        null
    }

    /**
     * Long textual call for operations such as `INSTALL`. Once writing starts, any missing terminal
     * reply is indeterminate: closing our socket does not cancel the daemon's per-connection worker.
     */
    override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult {
        val socket = try {
            open()
        } catch (e: Exception) {
            Log.d(TAG, "daemon long call not submitted (${e.message})")
            return DaemonLongResult.NotSubmitted
        }
        return socket.use { s ->
            var submissionBegan = false
            try {
                s.soTimeout = timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                submissionBegan = true
                HelperSocketProtocol.sendLine(cmd, s.inputStream, s.outputStream)
                    ?.let(DaemonLongResult::Reply)
                    ?: DaemonLongResult.Indeterminate
            } catch (e: Exception) {
                Log.d(TAG, "daemon long call ${if (submissionBegan) "indeterminate" else "not submitted"} (${e.message})")
                if (submissionBegan) DaemonLongResult.Indeterminate else DaemonLongResult.NotSubmitted
            }
        }
    }

    /**
     * Stream a caller-openable file without asking the helper's SELinux domain to read app-private
     * storage. The helper must acknowledge `READY` before bytes are written, preventing its line
     * accumulator from reading payload bytes ahead of the command handler.
     */
    override fun sendFile(cmd: String, source: File, timeoutMs: Long): DaemonStreamResult {
        val socket = try {
            open()
        } catch (e: Exception) {
            Log.d(TAG, "daemon stream not submitted (${e.message})")
            return DaemonStreamResult.NotSubmitted
        }
        val deadline = StreamDeadline(timeoutMs) { runCatching { socket.close() } }
        return try {
            socket.use { s ->
                var submissionBegan = false
                try {
                    s.soTimeout = timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                    submissionBegan = true
                    HelperSocketProtocol.sendFile(
                        command = cmd,
                        openSource = source::inputStream,
                        expectedBytes = source.length(),
                        input = s.inputStream,
                        output = s.outputStream,
                        shutdownOutput = s::shutdownOutput,
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "daemon stream ${if (submissionBegan) "indeterminate" else "not submitted"} (${e.message})")
                    if (submissionBegan) DaemonStreamResult.Indeterminate else DaemonStreamResult.NotSubmitted
                }
            }
        } finally {
            deadline.close()
        }
    }

    /** Send one command and read the full **binary** reply (e.g. `SCREENCAP` PNG bytes). Half-closes the
     *  write side so the daemon's serve loop sees EOF, processes the command, and closes — giving us EOF
     *  after all the bytes. Longer timeout (screencap takes ~1-2s). Null if unreachable/empty. */
    override fun sendBytes(cmd: String): ByteArray? = try {
        open().use { s ->
            s.soTimeout = 5000
            HelperSocketProtocol.sendBytes(cmd, s.inputStream, s.outputStream, s::shutdownOutput)
        }
    } catch (e: Exception) {
        Log.d(TAG, "daemon bytes failed (${e.message})")
        null
    }

    override fun sendBytesBounded(cmd: String, maxBytes: Long): ByteArray? = try {
        open().use { s ->
            s.soTimeout = 5000
            HelperSocketProtocol.sendBytes(cmd, s.inputStream, s.outputStream, s::shutdownOutput, maxBytes)
        }
    } catch (e: Exception) {
        Log.d(TAG, "daemon bounded bytes failed (${e.message})")
        null
    }

    /** Descriptor-confined raw Companion capture. The helper owns stop/open/relaunch as one operation. */
    internal fun backupCompanion(
        packageName: String,
        cacheDir: File,
        timeoutMs: Long = COMPANION_OPERATION_TIMEOUT_MS,
    ): CompanionHelperProtocol.BackupResult {
        val socket = try {
            open()
        } catch (e: Exception) {
            Log.d(TAG, "Companion backup not submitted (${e.message})")
            return CompanionHelperProtocol.BackupResult.NotSubmitted
        }
        val deadline = StreamDeadline(timeoutMs) { runCatching { socket.close() } }
        return try {
            socket.use { s ->
                s.soTimeout = timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                CompanionHelperProtocol.backup(packageName, cacheDir, s.inputStream, s.outputStream)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Companion backup exchange failed", e)
            CompanionHelperProtocol.BackupResult.Indeterminate
        } finally {
            deadline.close()
        }
    }

    /** Atomic descriptor-confined Companion restore. No shell or pathname fallback is permitted. */
    internal fun restoreCompanion(
        packageName: String,
        files: Map<String, File>,
        timeoutMs: Long = COMPANION_OPERATION_TIMEOUT_MS,
    ): CompanionHelperProtocol.RestoreResult {
        val socket = try {
            open()
        } catch (e: Exception) {
            Log.d(TAG, "Companion restore not submitted (${e.message})")
            return CompanionHelperProtocol.RestoreResult.NOT_SUBMITTED
        }
        val deadline = StreamDeadline(timeoutMs) { runCatching { socket.close() } }
        return try {
            socket.use { s ->
                s.soTimeout = timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                CompanionHelperProtocol.restore(
                    packageName,
                    files,
                    s.inputStream,
                    s.outputStream,
                    s::shutdownOutput,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Companion restore exchange failed", e)
            CompanionHelperProtocol.RestoreResult.INDETERMINATE
        } finally {
            deadline.close()
        }
    }

    private const val COMPANION_OPERATION_TIMEOUT_MS = 120_000L
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
