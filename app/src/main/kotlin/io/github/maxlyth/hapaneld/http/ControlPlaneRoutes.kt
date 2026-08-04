package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.util.AppInstaller
import io.github.maxlyth.hapaneld.util.BoundedStreams
import io.github.maxlyth.hapaneld.util.ByteLimitExceeded
import io.github.maxlyth.hapaneld.util.InstallProgress
import io.github.maxlyth.hapaneld.util.Json
import io.github.maxlyth.hapaneld.util.MonotonicDeadline
import io.github.maxlyth.hapaneld.util.ReleaseCatalog
import io.github.maxlyth.hapaneld.util.StreamDeadline
import io.github.maxlyth.hapaneld.util.UpdateChecker
import io.github.maxlyth.hapaneld.backup.PanelBackup
import io.github.maxlyth.hapaneld.security.SensitiveOperation
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
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

internal class CompanionBackupUnavailable(val reason: String) : Exception(reason)

internal data class ControlPlaneRouteDependencies(
    val playAudio: (String) -> Boolean,
    val installComponent: (String, String, String) -> Boolean,
    val installedComponentVersion: (String) -> String?,
    val buildBackup: suspend (includeCompanion: Boolean, passphrase: String) -> PanelBackup.Artifact,
    val backupFileStem: () -> String,
    val apkUpload: ApkUploadRouteDependencies,
    val authorize: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean = { _, _, _, _ -> true },
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
    val fetch: (String, File, Long) -> AppInstaller.DownloadResult = AppInstaller::download,
)

/**
 * Accepts an operator-supplied APK URL, or null to refuse it before any lease or network work happens.
 *
 * HTTPS-only, inherited unchanged from [AppInstaller.download] rather than widened, so there is one
 * scheme rule for every APK the panel fetches. The bound on length keeps an absurd string out of the
 * staging and logging paths. Note deliberately absent: there is **no** outbound host or address
 * allowlist. This surface is the LAN-trust control plane, where the same caller can already upload
 * arbitrary bytes for a privileged install, so fetching bytes is strictly weaker than what the panel
 * already accepts — and an allowlist would break the LAN-hosted mirror this feature exists to serve.
 */
internal fun validApkFetchUrl(raw: String, maxChars: Int = APK_FETCH_URL_MAX_CHARS): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.length > maxChars) return null
    val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return null
    if (!parsed.isAbsolute || !parsed.scheme.equals("https", true)) return null
    if (parsed.host.isNullOrBlank()) return null
    return trimmed
}

internal data class UploadedApkIdentity(val pkg: String, val version: String, val signerSha256: String?)

/** Builds the physical-approval text from untrusted APK metadata. Package and signer identity are
 * individually bounded and shown before the version field, so version metadata cannot hide them. */
internal fun uploadedApkApprovalSummary(identity: UploadedApkIdentity): String {
    val pkg = approvalDisplayField(identity.pkg, APK_APPROVAL_PACKAGE_CHARS)
    val signer = identity.signerSha256
        ?.let { approvalDisplayField(it, APK_APPROVAL_SIGNER_CHARS) }
        ?: "unsigned"
    val version = approvalDisplayField(identity.version, APK_APPROVAL_VERSION_CHARS)
    return "Install $pkg (signer $signer) version $version"
}

/** Removes non-rendering controls and direction-changing format characters before text reaches the
 * local approval dialog. The character bound never splits a supplementary Unicode character. */
private fun approvalDisplayField(value: String, maxChars: Int): String {
    val result = StringBuilder(minOf(value.length, maxChars))
    var offset = 0
    var acceptedChars = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        val width = Character.charCount(codePoint)
        offset += width
        val type = Character.getType(codePoint)
        if (type == Character.CONTROL.toInt() ||
            type == Character.FORMAT.toInt() ||
            type == Character.LINE_SEPARATOR.toInt() ||
            type == Character.PARAGRAPH_SEPARATOR.toInt() ||
            type == Character.SURROGATE.toInt()
        ) continue
        if (acceptedChars + width > maxChars) break
        result.appendCodePoint(codePoint)
        acceptedChars += width
    }
    return result.toString().trim().ifEmpty { "?" }
}

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
        require(timeoutMs in 1..(Long.MAX_VALUE / 1_000_000L))
        val timedOut = AtomicBoolean(false)
        val budget = MonotonicDeadline(timeoutMs, nanoTime)
        val bounded = DeadlineInputStream(input, budget)
        val deadline = deadlineFactory(timeoutMs) {
            timedOut.set(true)
            runCatching { input.close() }
        }
        return try {
            val copied = BoundedStreams.copy(bounded, output, maxBytes)
            if (timedOut.get() || budget.remainingMs() <= 0L) throw BodyReceiptTimeout()
            copied
        } catch (error: ByteLimitExceeded) {
            throw error
        } catch (error: BodyReceiptTimeout) {
            throw error
        } catch (error: Exception) {
            if (timedOut.get() || budget.remainingMs() <= 0L) throw BodyReceiptTimeout()
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
        private val budget: MonotonicDeadline,
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
            if (budget.remainingMs() <= 0L) throw BodyReceiptTimeout()
        }
    }
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

/**
 * Bytes a staged upload may occupy, or 0 to refuse. Admission is the upload's **actual** staging
 * requirement: the body is bounded by whichever of [maxPayloadBytes] and [usableBytes] is smaller, and
 * nothing beyond it is reserved, because a fixed surplus only ever refused uploads that would have
 * installed. The caller refuses a declared length exceeding this ceiling before writing; a body that
 * outgrows it mid-stream is refused by the stream bound; and space exhausted during the write is
 * reported by the write itself rather than pre-judged here.
 */
internal fun uploadStagingLimit(usableBytes: Long, maxPayloadBytes: Long): Long {
    if (usableBytes <= 0L || maxPayloadBytes <= 0L) return 0L
    return minOf(maxPayloadBytes, usableBytes)
}

/** Registers the control routes whose request parsing and admission decisions are independent of Android. */
internal fun Route.controlPlaneRoutes(dependencies: ControlPlaneRouteDependencies) {
    post("/play") { handlePlay(call, dependencies) }
    route("/api/v1") {
        post("/play") { handlePlay(call, dependencies) }
        post("/install/component") { handleComponentInstall(call, dependencies) }
        post("/install/apk") { handleApkUpload(call, dependencies.apkUpload) }
        post("/install/apk/from-url") { handleApkFetchFromUrl(call, dependencies.apkUpload) }
        post("/install/apk/commit") { handleApkCommit(call, dependencies) }
        post("/backup") { handleBackup(call, dependencies) }
    }
}

private suspend fun handleApkCommit(call: ApplicationCall, routes: ControlPlaneRouteDependencies) {
    val dependencies = routes.apkUpload
    if (!dependencies.enabled()) {
        call.respondText(
            """{"status":"disabled"}""",
            ContentType.Application.Json,
            HttpStatusCode.Forbidden,
        )
        return
    }
    val parameters = receiveBoundedFormParameters(call) ?: return
    val token = parameters["token"].orEmpty()
    val inspected = dependencies.pending.peek(token)
    if (inspected == null) {
        call.respondText(
            """{"status":"stale-or-missing"}""",
            ContentType.Application.Json,
            HttpStatusCode.Conflict,
        )
        return
    }
    val identity = inspected.identity
    val identitySummary = identity?.let(::uploadedApkApprovalSummary) ?: "Install the inspected uploaded APK"
    if (!routes.authorize(
            call,
            SensitiveOperation.APK_INSTALL,
            exactHttpApprovalPayload(call, parameters.canonicalDigest()),
            identitySummary,
        )
    ) return
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

/** Whether candidate APK bytes reached the staging file, or the exact wire refusal if they did not.
 *  The refusal is source-specific (an upload that outgrew its ceiling is not worded like a fetch that
 *  did), while everything after the bytes land is identical for both sources. */
private sealed interface StagedBytes {
    data object Written : StagedBytes
    data class Refused(val status: HttpStatusCode, val error: String) : StagedBytes
}

/**
 * The single staging, inspection and hand-off path shared by every APK source.
 *
 * Both the upload route and the URL route end here, so the security-relevant sequence exists once:
 * allocate staging, bound it to what the panel can actually store, obtain the bytes, inspect the exact
 * staged file, and mint a token bound to it. [writeBytes] is the only difference between sources.
 *
 * The caller has already taken [lease]. Every path that does not transfer a response releases that
 * lease and deletes the staging file in [finally], so an abandoned, refused or unreadable candidate
 * can never outlive the request or be reachable by a later token.
 */
private suspend fun stageInspectAndRespond(
    call: ApplicationCall,
    dependencies: ApkUploadRouteDependencies,
    lease: PendingUploadStore.Lease,
    declaredBytes: Long?,
    writeBytes: suspend (File, Long) -> StagedBytes,
) {
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
            call.respondText(
                """{"ok":false,"error":"insufficient-storage"}""",
                ContentType.Application.Json,
                HttpStatusCode.InsufficientStorage,
            )
            return
        }
        when (val written = writeBytes(staged, stagingLimit)) {
            is StagedBytes.Refused -> {
                call.respondText(
                    """{"ok":false,"error":${Json.str(written.error)}}""",
                    ContentType.Application.Json,
                    written.status,
                )
                return
            }
            StagedBytes.Written -> Unit
        }
        val identity = dependencies.inspect(staged)
        if (identity == null) {
            call.respondText(
                """{"ok":false,"error":"not-an-apk"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }
        val entry = dependencies.pending.stage(lease, staged, identity)
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

/** Reserves the single staging slot, or answers the caller and returns null when it is unavailable. */
private suspend fun beginApkStaging(
    call: ApplicationCall,
    dependencies: ApkUploadRouteDependencies,
): PendingUploadStore.Lease? = when (val admission = dependencies.pending.begin()) {
    is PendingUploadStore.BeginResult.Granted -> admission.lease
    PendingUploadStore.BeginResult.Busy -> {
        call.respondText(
            """{"ok":false,"error":"upload-busy"}""",
            ContentType.Application.Json,
            HttpStatusCode.Conflict,
        )
        null
    }
    PendingUploadStore.BeginResult.Closed -> {
        call.respondText(
            """{"ok":false,"error":"stopping"}""",
            ContentType.Application.Json,
            HttpStatusCode.ServiceUnavailable,
        )
        null
    }
}

/**
 * Fetches an operator-named APK so it can be reviewed exactly like an uploaded one.
 *
 * This is a *source* for the existing review flow, never an installer: it ends at the same staged file
 * and the same one-shot token, and installation still requires the unchanged commit route with its
 * physical-approval gate. Downloading bytes here grants no authority that committing them does not.
 * Reaching the network is bounded by [AppInstaller.download] — HTTPS-only hops, a redirect cap, a
 * whole-operation deadline, and a byte ceiling enforced on bytes actually read rather than on any
 * length the peer declares.
 */
private suspend fun handleApkFetchFromUrl(call: ApplicationCall, dependencies: ApkUploadRouteDependencies) {
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
    val parameters = receiveBoundedFormParameters(call) ?: return
    val url = validApkFetchUrl(parameters["url"].orEmpty())
    if (url == null) {
        call.respondText(
            """{"ok":false,"error":"invalid-url"}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest,
        )
        return
    }
    val lease = beginApkStaging(call, dependencies) ?: return
    stageInspectAndRespond(call, dependencies, lease, declaredBytes = null) { staged, stagingLimit ->
        when (withContext(Dispatchers.IO) { dependencies.fetch(url, staged, stagingLimit) }) {
            AppInstaller.DownloadResult.Succeeded -> StagedBytes.Written
            // A ceiling the panel's own free space imposed is reported as storage, not as the operator
            // having named something too large, because those need different corrective action.
            AppInstaller.DownloadResult.TooLarge -> if (stagingLimit < dependencies.maxBytes) {
                StagedBytes.Refused(HttpStatusCode.InsufficientStorage, "insufficient-storage")
            } else {
                StagedBytes.Refused(HttpStatusCode.PayloadTooLarge, "fetch-too-large")
            }
            AppInstaller.DownloadResult.TimedOut ->
                StagedBytes.Refused(HttpStatusCode.RequestTimeout, "fetch-timeout")
            AppInstaller.DownloadResult.Failed ->
                StagedBytes.Refused(HttpStatusCode.BadGateway, "fetch-failed")
        }
    }
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
    val lease = beginApkStaging(call, dependencies) ?: return
    stageInspectAndRespond(call, dependencies, lease, declaredBytes) { staged, stagingLimit ->
        try {
            val received = withContext(Dispatchers.IO) {
                call.receiveStream().use { input ->
                    staged.outputStream().use { output ->
                        dependencies.receiveBody(input, output, stagingLimit)
                    }
                } > 0L
            }
            if (received) StagedBytes.Written
            else StagedBytes.Refused(HttpStatusCode.BadRequest, "upload-failed")
        } catch (_: ByteLimitExceeded) {
            if (stagingLimit < dependencies.maxBytes) {
                StagedBytes.Refused(HttpStatusCode.InsufficientStorage, "insufficient-storage")
            } else {
                StagedBytes.Refused(HttpStatusCode.PayloadTooLarge, "upload-too-large")
            }
        } catch (_: BodyReceiptTimeout) {
            StagedBytes.Refused(HttpStatusCode.RequestTimeout, "upload-timeout")
        } catch (_: Exception) {
            StagedBytes.Refused(HttpStatusCode.BadRequest, "upload-failed")
        }
    }
}

private suspend fun handlePlay(call: ApplicationCall, dependencies: ControlPlaneRouteDependencies) {
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
    if (!dependencies.authorize(
            call,
            SensitiveOperation.REMOTE_MEDIA,
            exactHttpApprovalPayload(call, sha256Hex(body.toByteArray(Charsets.UTF_8))),
            "Play media from $url",
        )
    ) return
    if (!dependencies.playAudio(url)) {
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
            if (!dependencies.authorize(
                    call,
                    SensitiveOperation.APK_INSTALL,
                    exactHttpApprovalPayload(call, parameters.canonicalDigest()),
                    "Install ${name.ifEmpty { "component" }} ${version.ifEmpty { "latest" }}",
                )
            ) return
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
    if (!dependencies.authorize(
            call,
            SensitiveOperation.BACKUP_EXPORT,
            exactHttpApprovalPayload(call, parameters.canonicalDigest()),
            "Export a backup containing panel credentials",
        )
    ) return
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
    var deliveryHandedOff = false
    var artifact: PanelBackup.Artifact? = null
    val deliveryCleaned = AtomicBoolean(false)
    fun cleanDelivery() {
        if (deliveryCleaned.compareAndSet(false, true)) {
            artifact?.close()
            delivery.close()
        }
    }
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
        deliveryHandedOff = true
        try {
            call.respondOutputStream(ContentType.Application.OctetStream) {
                try {
                    built.file.inputStream().use { input -> input.copyTo(this) }
                } finally {
                    cleanDelivery()
                }
            }
        } catch (error: Throwable) {
            deliveryHandedOff = false
            throw error
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
    } catch (_: BackupStagingRetainedException) {
        progressResult = "Sensitive backup staging file retained"
        call.respondText(
            """{"ok":false,"error":"backup-staging-retained","message":"Sensitive temporary backup data could not be removed. Check panel storage, then retry; no backup was downloaded."}""",
            ContentType.Application.Json,
            HttpStatusCode.InsufficientStorage,
        )
        return
    } finally {
        if (!deliveryHandedOff) {
            cleanDelivery()
        }
        if (!progressFinished) InstallProgress.finish(progress, progressResult)
    }
}

private val PLAY_URL = Regex("""https?://[^\s"']+""")
private val COMPONENT_NAMES = setOf("paneld", "companion", "webview")
private val COMPONENT_ACTIONS = setOf("update", "reinstall")
internal const val RESTORE_BODY_RECEIPT_DEADLINE_MS = 120_000L
internal const val STANDARD_BODY_RECEIPT_DEADLINE_MS = 30_000L
private const val APK_UPLOAD_RECEIPT_DEADLINE_MS = 600_000L
internal const val APK_FETCH_URL_MAX_CHARS = 2048
private const val APK_APPROVAL_PACKAGE_CHARS = 64
private const val APK_APPROVAL_SIGNER_CHARS = 64
private const val APK_APPROVAL_VERSION_CHARS = 20
