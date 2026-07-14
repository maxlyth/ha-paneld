package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.util.BoundedStreams
import io.github.maxlyth.hapaneld.util.ByteLimitExceeded
import io.github.maxlyth.hapaneld.util.InstallProgress
import io.github.maxlyth.hapaneld.util.Json
import io.github.maxlyth.hapaneld.util.ReleaseCatalog
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class CompanionBackupUnavailable(val reason: String) : Exception(reason)

internal data class ControlPlaneRouteDependencies(
    val playAudio: (String) -> Boolean,
    val installComponent: (String, String, String) -> Boolean,
    val buildBackup: suspend (includeCompanion: Boolean, passphrase: String) -> ByteArray,
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
)

internal data class UploadedApkIdentity(val pkg: String, val version: String, val signerSha256: String?)

/** Registers the control routes whose request parsing and admission decisions are independent of Android. */
internal fun Route.controlPlaneRoutes(dependencies: ControlPlaneRouteDependencies) {
    post("/play") { handlePlay(call, dependencies.playAudio) }
    route("/api/v1") {
        post("/play") { handlePlay(call, dependencies.playAudio) }
        post("/install/component") { handleComponentInstall(call, dependencies.installComponent) }
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
    val token = call.receiveParameters()["token"].orEmpty()
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
    val lease = dependencies.pending.begin()
    if (lease == null) {
        call.respondText(
            """{"ok":false,"error":"stopping"}""",
            ContentType.Application.Json,
            HttpStatusCode.ServiceUnavailable,
        )
        return
    }
    val staged = runCatching { dependencies.createStagingFile() }.getOrElse {
        call.respondText(
            """{"ok":false,"error":"upload-staging-failed"}""",
            ContentType.Application.Json,
            HttpStatusCode.InternalServerError,
        )
        return
    }
    val received = try {
        withContext(Dispatchers.IO) {
            call.receiveStream().use { input ->
                staged.outputStream().use { output -> BoundedStreams.copy(input, output, dependencies.maxBytes) }
            } > 0L
        }
    } catch (_: ByteLimitExceeded) {
        staged.delete()
        call.respondText(
            """{"ok":false,"error":"upload-too-large"}""",
            ContentType.Application.Json,
            HttpStatusCode.PayloadTooLarge,
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
}

private suspend fun handlePlay(call: ApplicationCall, playAudio: (String) -> Boolean) {
    val body = try {
        withContext(Dispatchers.IO) {
            call.receiveStream().use {
                String(BoundedStreams.readBytes(it, PaneldServer.MAX_PLAY_BODY_BYTES), Charsets.UTF_8)
            }
        }
    } catch (_: ByteLimitExceeded) {
        call.respondText("body-too-large\n", status = HttpStatusCode.PayloadTooLarge)
        return
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
    installComponent: (String, String, String) -> Boolean,
) {
    val parameters = call.receiveParameters()
    val name = parameters["name"]?.trim().orEmpty()
    val action = parameters["action"]?.trim()?.ifEmpty { "update" } ?: "update"
    val version = parameters["version"]?.trim().orEmpty()
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
        else -> {
            val status = if (installComponent(name, action, version)) "started" else "busy"
            call.respondText("""{"status":"$status"}""", ContentType.Application.Json)
        }
    }
}

private suspend fun handleBackup(call: ApplicationCall, dependencies: ControlPlaneRouteDependencies) {
    val parameters = call.receiveParameters()
    val passphrase = parameters["passphrase"].orEmpty()
    val includeCompanion = parameters["include_companion"]?.let { it == "true" || it == "1" } ?: true
    val progress = if (includeCompanion) InstallProgress.start("Backup") else null
    if (includeCompanion && progress == null) {
        call.respondText(
            """{"ok":false,"error":"busy"}""",
            ContentType.Application.Json,
            HttpStatusCode.Conflict,
        )
        return
    }
    var progressResult = "backup cancelled"
    val bytes = try {
        dependencies.buildBackup(includeCompanion, passphrase).also { progressResult = "backup ready" }
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
        if (progress != null) InstallProgress.finish(progress, progressResult)
    }
    val extension = if (passphrase.isEmpty()) "json" else "hpb"
    call.response.headers.append(
        "Content-Disposition",
        "attachment; filename=\"${dependencies.backupFileStem()}-backup.$extension\"",
    )
    call.respondBytes(bytes, ContentType.Application.OctetStream)
}

private val PLAY_URL = Regex("""https?://[^\s"']+""")
private val COMPONENT_NAMES = setOf("paneld", "companion", "webview")
private val COMPONENT_ACTIONS = setOf("update", "reinstall")
