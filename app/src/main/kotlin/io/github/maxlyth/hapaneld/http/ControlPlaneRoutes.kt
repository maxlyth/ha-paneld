package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.util.BoundedStreams
import io.github.maxlyth.hapaneld.util.ByteLimitExceeded
import io.github.maxlyth.hapaneld.util.InstallProgress
import io.github.maxlyth.hapaneld.util.Json
import io.github.maxlyth.hapaneld.util.ReleaseCatalog
import io.github.maxlyth.hapaneld.util.StreamDeadline
import io.github.maxlyth.hapaneld.util.UpdateChecker
import io.github.maxlyth.hapaneld.backup.PanelBackup
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal class CompanionBackupUnavailable(val reason: String) : Exception(reason)

internal data class ControlPlaneRouteDependencies(
    val playAudio: (String) -> Boolean,
    val installComponent: (String, String, String) -> Boolean,
    val installedComponentVersion: (String) -> String?,
    val buildBackup: suspend (includeCompanion: Boolean, passphrase: String) -> PanelBackup.Artifact,
    val backupFileStem: () -> String,
    val apkUpload: ApkUploadRouteDependencies,
)

internal data class ApkUploadRouteDependencies(
    val enabled: () -> Boolean,
    val rootAvailable: () -> Boolean,
    val pending: PendingUploadStore,
    val createStagingFile: () -> File,
    val inspect: suspend (File) -> UploadedApkIdentity?,
    val startInstall: (PendingUploadStore.Entry, InstallProgress.Ticket) -> Unit,
    val maxBytes: Long = PaneldServer.MAX_APK_UPLOAD_BYTES,
    val usableSpace: (File) -> Long = { staged -> staged.parentFile?.usableSpace ?: 0L },
    val receiveBody: (InputStream, OutputStream, Long) -> Long = { input, output, maxBytes ->
        DeadlineBoundedBody.copy(input, output, maxBytes, APK_UPLOAD_RECEIPT_DEADLINE_MS)
    },
)

internal data class UploadedApkIdentity(val pkg: String, val version: String, val signerSha256: String?)

internal class BodyReceiptTimeout : IOException("request body receipt deadline exceeded")

internal sealed interface BoundedBodyReceipt {
    data class Received(val bytes: ByteArray) : BoundedBodyReceipt
    data object TooLarge : BoundedBodyReceipt
    data object TimedOut : BoundedBodyReceipt
}

/** Applies a whole-body deadline as well as a byte limit. The timer closes a transport that is blocked
 * inside read(), while the wrapper checks elapsed time before and after each successful transport read. */
internal object DeadlineBoundedBody {
    fun copy(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
        timeoutMs: Long,
        nanoTime: () -> Long = System::nanoTime,
        deadlineFactory: (Long, () -> Unit) -> AutoCloseable = { timeout, onTimeout ->
            StreamDeadline(timeout, onTimeout)
        },
    ): Long {
        require(timeoutMs in 1..(Long.MAX_VALUE / NANOS_PER_MILLISECOND))
        val timedOut = AtomicBoolean(false)
        val deadlineNanos = nanoTime() + timeoutMs * NANOS_PER_MILLISECOND
        val bounded = DeadlineInputStream(input, deadlineNanos, nanoTime)
        val deadline = deadlineFactory(timeoutMs) {
            timedOut.set(true)
            runCatching { input.close() }
        }
        return try {
            val copied = BoundedStreams.copy(bounded, output, maxBytes)
            if (timedOut.get() || nanoTime() - deadlineNanos >= 0L) throw BodyReceiptTimeout()
            copied
        } catch (error: ByteLimitExceeded) {
            throw error
        } catch (error: BodyReceiptTimeout) {
            throw error
        } catch (error: Exception) {
            if (timedOut.get() || nanoTime() - deadlineNanos >= 0L) throw BodyReceiptTimeout()
            throw error
        } finally {
            deadline.close()
        }
    }

    fun readBytes(
        input: InputStream,
        maxBytes: Long,
        timeoutMs: Long,
        nanoTime: () -> Long = System::nanoTime,
        deadlineFactory: (Long, () -> Unit) -> AutoCloseable = { timeout, onTimeout ->
            StreamDeadline(timeout, onTimeout)
        },
    ): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE.toLong()).toInt())
        copy(input, output, maxBytes, timeoutMs, nanoTime, deadlineFactory)
        return output.toByteArray()
    }

    private class DeadlineInputStream(
        source: InputStream,
        private val deadlineNanos: Long,
        private val nanoTime: () -> Long,
    ) : FilterInputStream(source) {
        override fun read(): Int {
            checkDeadline()
            return super.read().also { checkDeadline() }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkDeadline()
            return super.read(buffer, offset, length).also { checkDeadline() }
        }

        private fun checkDeadline() {
            if (nanoTime() - deadlineNanos >= 0L) throw BodyReceiptTimeout()
        }
    }

    private const val NANOS_PER_MILLISECOND = 1_000_000L
}

/** Shared bounded body admission for every materialized request body on the LAN control plane. */
internal suspend fun receiveBoundedBody(
    call: ApplicationCall,
    maxBytes: Long,
    timeoutMs: Long = STANDARD_BODY_RECEIPT_DEADLINE_MS,
    reader: (InputStream, Long, Long) -> ByteArray = DeadlineBoundedBody::readBytes,
): BoundedBodyReceipt {
    val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > maxBytes) return BoundedBodyReceipt.TooLarge
    return try {
        val bytes = withContext(Dispatchers.IO) {
            call.receiveStream().use { reader(it, maxBytes, timeoutMs) }
        }
        BoundedBodyReceipt.Received(bytes)
    } catch (_: ByteLimitExceeded) {
        BoundedBodyReceipt.TooLarge
    } catch (_: BodyReceiptTimeout) {
        BoundedBodyReceipt.TimedOut
    }
}

internal fun uploadStagingLimit(
    usableBytes: Long,
    maxPayloadBytes: Long,
    safetyMarginBytes: Long = UPLOAD_STORAGE_SAFETY_MARGIN_BYTES,
): Long {
    if (usableBytes <= safetyMarginBytes || maxPayloadBytes <= 0L) return 0L
    return minOf(maxPayloadBytes, usableBytes - safetyMarginBytes)
}

/** Registers the control routes whose request parsing and admission decisions are independent of Android. */
internal fun Route.controlPlaneRoutes(dependencies: ControlPlaneRouteDependencies) {
    post("/play") { handlePlay(call, dependencies.playAudio) }
    route("/api/v1") {
        post("/play") { handlePlay(call, dependencies.playAudio) }
        post("/install/component") { handleComponentInstall(call, dependencies) }
        post("/install/apk") { handleApkUpload(call, dependencies.apkUpload) }
        post("/install/apk/commit") { handleApkCommit(call, dependencies.apkUpload) }
        post("/backup") { handleBackup(call, dependencies) }
    }
}

private suspend fun handleApkCommit(call: ApplicationCall, dependencies: ApkUploadRouteDependencies) {
    if (!dependencies.enabled()) {
        call.respondText(
            """{"status":"disabled"}""",
            ContentType.Application.Json,
            HttpStatusCode.Forbidden,
        )
        return
    }
    val token = (receiveBoundedFormParameters(call) ?: return)["token"].orEmpty()
    val claimed = dependencies.pending.claim(token)
    if (claimed == null) {
        call.respondText(
            """{"status":"stale-or-missing"}""",
            ContentType.Application.Json,
            HttpStatusCode.Conflict,
        )
        return
    }
    val progress = InstallProgress.start("APK")
    if (progress == null) {
        dependencies.pending.restore(claimed)
        call.respondText("""{"status":"busy"}""", ContentType.Application.Json)
        return
    }
    dependencies.startInstall(claimed, progress)
    call.respondText("""{"status":"started"}""", ContentType.Application.Json)
}

/** Stages an arbitrary user-supplied APK on the unauthenticated LAN-trust surface, so the production source, origin, host, explicit-enable and root-capability gates must remain in force around this route. */
private suspend fun handleApkUpload(call: ApplicationCall, dependencies: ApkUploadRouteDependencies) {
    if (!dependencies.enabled()) {
        call.respondText(
            """{"ok":false,"error":"disabled"}""",
            ContentType.Application.Json,
            HttpStatusCode.Forbidden,
        )
        return
    }
    if (!dependencies.rootAvailable()) {
        call.respondText(
            """{"ok":false,"error":"no-root"}""",
            ContentType.Application.Json,
            HttpStatusCode.ServiceUnavailable,
        )
        return
    }
    val declaredHeader = call.request.headers[HttpHeaders.ContentLength]
    val declaredBytes = declaredHeader?.toLongOrNull()
    if (declaredHeader != null && (declaredBytes == null || declaredBytes < 0L)) {
        call.respondText(
            """{"ok":false,"error":"invalid-content-length"}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        return
    }
    if (declaredBytes != null && declaredBytes > dependencies.maxBytes) {
        call.respondText(
            """{"ok":false,"error":"upload-too-large"}""",
            ContentType.Application.Json,
            HttpStatusCode.PayloadTooLarge,
        )
        return
    }
    val lease = when (val admission = dependencies.pending.begin()) {
        is PendingUploadStore.BeginResult.Granted -> admission.lease
        PendingUploadStore.BeginResult.Busy -> {
            call.respondText(
                """{"ok":false,"error":"upload-busy"}""",
                ContentType.Application.Json,
                HttpStatusCode.Conflict,
            )
            return
        }
        PendingUploadStore.BeginResult.Closed -> {
            call.respondText(
                """{"ok":false,"error":"stopping"}""",
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return
        }
    }
    var responseTransferred = false
    var stagedFile: File? = null
    try {
        val staged = runCatching { dependencies.createStagingFile() }.getOrElse {
            call.respondText(
                """{"ok":false,"error":"upload-staging-failed"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
            return
        }
        stagedFile = staged
        val stagingLimit = uploadStagingLimit(dependencies.usableSpace(staged), dependencies.maxBytes)
        if (stagingLimit == 0L || (declaredBytes != null && declaredBytes > stagingLimit)) {
            staged.delete()
            call.respondText(
                """{"ok":false,"error":"insufficient-storage"}""",
                ContentType.Application.Json,
                HttpStatusCode.InsufficientStorage,
            )
            return
        }
        val received = try {
            withContext(Dispatchers.IO) {
                call.receiveStream().use { input ->
                    staged.outputStream().use { output ->
                        dependencies.receiveBody(input, output, stagingLimit)
                    }
                } > 0L
            }
        } catch (_: ByteLimitExceeded) {
            staged.delete()
            if (stagingLimit < dependencies.maxBytes) {
                call.respondText(
                    """{"ok":false,"error":"insufficient-storage"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InsufficientStorage,
                )
            } else {
                call.respondText(
                    """{"ok":false,"error":"upload-too-large"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.PayloadTooLarge,
                )
            }
            return
        } catch (_: BodyReceiptTimeout) {
            staged.delete()
            call.respondText(
                """{"ok":false,"error":"upload-timeout"}""",
                ContentType.Application.Json,
                HttpStatusCode.RequestTimeout,
            )
            return
        } catch (_: Exception) {
            false
        }
        if (!received) {
            staged.delete()
            call.respondText(
                """{"ok":false,"error":"upload-failed"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }
        val identity = dependencies.inspect(staged)
        if (identity == null) {
            staged.delete()
            call.respondText(
                """{"ok":false,"error":"not-an-apk"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }
        val entry = dependencies.pending.stage(lease, staged)
        if (entry == null) {
            call.respondText(
                """{"ok":false,"error":"stopping"}""",
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return
        }
        call.respondText(
            """{"ok":true,"token":${Json.str(entry.token)},"package":${Json.str(identity.pkg)},"version":${Json.str(identity.version)},"signer":${Json.str(identity.signerSha256 ?: "unsigned")}}""",
            ContentType.Application.Json,
        )
        responseTransferred = true
    } finally {
        if (!responseTransferred) {
            dependencies.pending.abort(lease)
            stagedFile?.delete()
        }
    }
}

private suspend fun handlePlay(call: ApplicationCall, playAudio: (String) -> Boolean) {
    val body = when (val receipt = receiveBoundedBody(call, PaneldServer.MAX_PLAY_BODY_BYTES)) {
        is BoundedBodyReceipt.Received -> String(receipt.bytes, Charsets.UTF_8)
        BoundedBodyReceipt.TooLarge -> {
            call.respondText("body-too-large\n", status = HttpStatusCode.PayloadTooLarge)
            return
        }
        BoundedBodyReceipt.TimedOut -> {
            call.respondText("body-timeout\n", status = HttpStatusCode.RequestTimeout)
            return
        }
    }
    val url = PLAY_URL.find(body)?.value
    if (url == null) {
        call.respondText("no-url\n", status = HttpStatusCode.BadRequest)
        return
    }
    if (!playAudio(url)) {
        call.respondText("stopping\n", status = HttpStatusCode.ServiceUnavailable)
        return
    }
    call.respondText("playing\n")
}

private suspend fun handleComponentInstall(
    call: ApplicationCall,
    dependencies: ControlPlaneRouteDependencies,
) {
    val parameters = receiveBoundedFormParameters(call) ?: return
    val name = parameters["name"]?.trim().orEmpty()
    val action = parameters["action"]?.trim()?.ifEmpty { "update" } ?: "update"
    val version = parameters["version"]?.trim().orEmpty()
    val allowDowngrade = parameters["allow_downgrade"]?.let { it == "true" || it == "1" } ?: false
    val installedVersion = if (version.isEmpty() || name !in COMPONENT_NAMES || !ReleaseCatalog.validTag(version)) {
        null
    } else {
        dependencies.installedComponentVersion(name)?.takeIf { it.isNotBlank() }
    }
    val versionComparison = installedVersion?.let { current ->
        val requested = if (name == "companion") UpdateChecker.stripVariant(version) else version
        val installed = if (name == "companion") UpdateChecker.stripVariant(current) else current
        UpdateChecker.compareVersions(requested, installed)
    }
    when {
        name !in COMPONENT_NAMES -> call.respondText(
            """{"status":"bad-component"}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        action !in COMPONENT_ACTIONS -> call.respondText(
            """{"status":"bad-action"}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        version.isNotEmpty() && !ReleaseCatalog.validTag(version) -> call.respondText(
            """{"status":"bad-version"}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        installedVersion != null && versionComparison == null -> call.respondText(
            """{"status":"uncomparable-version"}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        installedVersion != null && !allowDowngrade && versionComparison!! < 0 -> call.respondText(
            """{"status":"downgrade-refused"}""",
            ContentType.Application.Json,
            HttpStatusCode.Conflict,
        )
        else -> {
            val status = if (dependencies.installComponent(name, action, version)) "started" else "busy"
            call.respondText("""{"status":"$status"}""", ContentType.Application.Json)
        }
    }
}

private suspend fun handleBackup(call: ApplicationCall, dependencies: ControlPlaneRouteDependencies) {
    val parameters = receiveBoundedFormParameters(call) ?: return
    val passphrase = parameters["passphrase"].orEmpty()
    val allowPlaintext = parameters["allow_plaintext"]?.let { it == "true" || it == "1" } ?: false
    val includeCompanion = parameters["include_companion"]?.let { it == "true" || it == "1" } ?: true
    if (passphrase.isEmpty() && !allowPlaintext) {
        call.respondText(
            """{"ok":false,"error":"passphrase-required","message":"A backup contains credentials. Supply a passphrase, or explicitly acknowledge plaintext export with allow_plaintext=1."}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        return
    }
    val delivery = BackupDeliveryGate.acquire()
    if (delivery == null) {
        call.respondText(
            """{"ok":false,"error":"busy"}""",
            ContentType.Application.Json,
            HttpStatusCode.Conflict,
        )
        return
    }
    // Every backup owns the destructive/expensive operation lane. Even config-only encrypted bundles
    // run PBKDF2 + AES and must not be multiplied by concurrent unauthenticated LAN requests.
    val progress = InstallProgress.start("Backup")
    if (progress == null) {
        delivery.close()
        call.respondText(
            """{"ok":false,"error":"busy"}""",
            ContentType.Application.Json,
            HttpStatusCode.Conflict,
        )
        return
    }
    var progressResult = "backup cancelled"
    var progressFinished = false
    var artifact: PanelBackup.Artifact? = null
    try {
        val built = dependencies.buildBackup(includeCompanion, passphrase)
        artifact = built
        progressResult = "backup ready"
        InstallProgress.finish(progress, progressResult)
        progressFinished = true
        call.response.headers.append(
            "Content-Disposition",
            "attachment; filename=\"${dependencies.backupFileStem()}-backup.${built.extension}\"",
        )
        call.respondOutputStream(ContentType.Application.OctetStream) {
            built.file.inputStream().use { input -> input.copyTo(this) }
        }
    } catch (_: ByteLimitExceeded) {
        progressResult = "Companion backup is too large"
        call.respondText(
            """{"ok":false,"error":"companion-backup-too-large"}""",
            ContentType.Application.Json,
            HttpStatusCode.PayloadTooLarge,
        )
        return
    } catch (error: CompanionBackupUnavailable) {
        progressResult = error.reason
        call.respondText(
            """{"ok":false,"error":${Json.str(error.reason)}}""",
            ContentType.Application.Json,
            HttpStatusCode.UnprocessableEntity,
        )
        return
    } finally {
        artifact?.close()
        if (!progressFinished) InstallProgress.finish(progress, progressResult)
        delivery.close()
    }
}

private val PLAY_URL = Regex("""https?://[^\s"']+""")
private val COMPONENT_NAMES = setOf("paneld", "companion", "webview")
private val COMPONENT_ACTIONS = setOf("update", "reinstall")
internal const val RESTORE_BODY_RECEIPT_DEADLINE_MS = 120_000L
internal const val STANDARD_BODY_RECEIPT_DEADLINE_MS = 30_000L
private const val APK_UPLOAD_RECEIPT_DEADLINE_MS = 600_000L
private const val UPLOAD_STORAGE_SAFETY_MARGIN_BYTES = 64L * 1024L * 1024L
