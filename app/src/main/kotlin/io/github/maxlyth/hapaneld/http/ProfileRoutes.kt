package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.device.profile.PassiveProfileDraft
import io.github.maxlyth.hapaneld.device.profile.PassiveProfileReport
import io.github.maxlyth.hapaneld.device.profile.ProfileActivationState
import io.github.maxlyth.hapaneld.device.profile.ProfileAdmin
import io.github.maxlyth.hapaneld.device.profile.ProfileDiff
import io.github.maxlyth.hapaneld.device.profile.ProfileIssue
import io.github.maxlyth.hapaneld.device.profile.ProfileMutation
import io.github.maxlyth.hapaneld.device.profile.ProfileRef
import io.github.maxlyth.hapaneld.device.profile.ProfileSelection
import io.github.maxlyth.hapaneld.device.profile.ProfileStatus
import io.github.maxlyth.hapaneld.device.profile.ProfileSummary
import io.github.maxlyth.hapaneld.util.BoundedStreams
import io.github.maxlyth.hapaneld.util.ByteLimitExceeded
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Read-only runtime facts stay separately injectable until they join the stable [ProfileAdmin] surface. */
internal data class ProfileRouteReadOnlyProviders(
    val template: () -> String? = { null },
    val deviceDraft: () -> PassiveProfileDraft? = { null },
    val latestReport: () -> PassiveProfileReport? = { null },
    val probe: (String) -> PassiveProfileReport? = { null },
)

internal data class ProfileRouteDependencies(
    val admin: ProfileAdmin,
    /** Must schedule a delayed controlled restart; it must never stop the service inline. */
    val requestRestart: () -> Unit = {},
    val readOnly: ProfileRouteReadOnlyProviders = ProfileRouteReadOnlyProviders(),
    val hardMaxYamlBytes: Long = MAX_PROFILE_YAML_BYTES,
)

internal const val MAX_PROFILE_YAML_BYTES = 256L * 1024L
private const val MAX_PROFILE_ACTION_BYTES = 16L * 1024L
private val PROFILE_ID = Regex("^[a-z0-9](?:[a-z0-9.-]{0,126}[a-z0-9])?$")
private val PROFILE_REVISION = Regex("^[a-f0-9]{64}$")

/**
 * Profile administration transport. The route owns bounds, explicit confirmations, optimistic
 * catalog revisions, and safe JSON projection; parsing, persistence, matching and activation state
 * remain entirely behind [ProfileAdmin]. Mount inside the existing `/api/v1` route so the server's
 * LAN-source, origin and Host guards apply before any profile bytes are read.
 */
internal fun Route.profileRoutes(dependencies: ProfileRouteDependencies) {
    val admin = dependencies.admin
    route("/profiles") {
        get {
            val status = admin.status()
            val lastKnownGood = (status.lastKnownGood as? ProfileSelection.Pinned)?.ref
            val body = statusJson(status).apply {
                put("profiles", JSONArray(admin.list().map { summary ->
                    summaryJson(summary).put("last_known_good", summary.ref == lastKnownGood)
                }))
            }
            call.respondProfileJson(body)
        }
        get("/schema") { call.respondProfileJson(schemaJson(admin)) }
        get("/drivers") {
            call.respondProfileJson(JSONObject().put("drivers", JSONArray(admin.drivers().map { driver ->
                JSONObject()
                    .put("id", driver.id)
                    .put("kind", driver.kind.name.lowercase())
                    .put("description", driver.description)
                    .put("privileged", driver.privileged)
            })))
        }
        get("/template") {
            val yaml = dependencies.readOnly.template()
            if (yaml == null) call.respondText("profile template unavailable\n", status = HttpStatusCode.NotImplemented)
            else call.respondProfileYaml(yaml, "new-panel-profile.yaml")
        }
        get("/device-draft") {
            val draft = dependencies.readOnly.deviceDraft()
            if (draft == null) call.respondText("passive device draft unavailable\n", status = HttpStatusCode.NotImplemented)
            else call.respondProfileYaml(draft.rawYaml, "passive-device-draft.yaml")
        }
        get("/report") {
            val report = dependencies.readOnly.latestReport()
            if (report == null) call.respondText("passive report unavailable\n", status = HttpStatusCode.NotFound)
            else call.respondProfileJson(reportJson(report))
        }
        get("/{id}/revisions/{revision}") {
            val ref = call.profileRefOrRespond() ?: return@get
            val yaml = admin.exportProfile(ref)
            if (yaml == null) call.respondText("profile revision not found\n", status = HttpStatusCode.NotFound)
            else call.respondProfileYaml(yaml, "${safeFileStem(ref.id)}-${ref.revision.take(12)}.yaml")
        }

        post("/preview") { handlePreview(call, dependencies, includeProbe = false) }
        post("/validate") { handlePreview(call, dependencies, includeProbe = false) }
        post("/compare") { handlePreview(call, dependencies, includeProbe = false) }
        post("/probe") { handlePreview(call, dependencies, includeProbe = true) }
        post("/import") { handleImport(call, dependencies) }
        post("/save") { handleImport(call, dependencies) }
        post("/select") { handleSelection(call, dependencies, rollback = false) }
        post("/activate") { handleSelection(call, dependencies, rollback = false) }
        post("/rollback") { handleSelection(call, dependencies, rollback = true) }
        post("/delete") { handleDelete(call, dependencies) }
    }
}

/** Keep the tab and documented paths honest before the runtime repository is wired by the service. */
internal fun Route.unavailableProfileRoutes() {
    route("/profiles") {
        get { call.respondText("profile administration unavailable\n", status = HttpStatusCode.ServiceUnavailable) }
        get("/{path...}") { call.respondText("profile administration unavailable\n", status = HttpStatusCode.ServiceUnavailable) }
        post("/{path...}") { call.respondText("profile administration unavailable\n", status = HttpStatusCode.ServiceUnavailable) }
    }
}

private suspend fun handlePreview(
    call: ApplicationCall,
    dependencies: ProfileRouteDependencies,
    includeProbe: Boolean,
) {
    val yaml = call.receiveProfileYaml(dependencies.maxYamlBytes()) ?: return
    val preview = dependencies.admin.preview(yaml)
    val body = previewJson(preview)
    if (includeProbe) dependencies.readOnly.probe(yaml)?.let { body.put("report", reportJson(it)) }
    call.respondProfileJson(body)
}

private suspend fun handleImport(call: ApplicationCall, dependencies: ProfileRouteDependencies) {
    val token = call.request.headers["X-Profile-Preview-Token"]?.trim().orEmpty()
    if (token.isBlank() || token.length > 512) {
        call.respondProfileError(HttpStatusCode.BadRequest, "preview-token-required")
        return
    }
    val yaml = call.receiveProfileYaml(dependencies.maxYamlBytes()) ?: return
    val mutation = dependencies.admin.importProfile(yaml, token)
    val importedRef = if (mutation is ProfileMutation.Success) {
        val hash = MessageDigest.getInstance("SHA-256").digest(yaml.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        dependencies.admin.list().singleOrNull { it.ref.revision == hash }?.ref
    } else null
    call.respondMutation(mutation, importedRef = importedRef)
}

private suspend fun handleSelection(
    call: ApplicationCall,
    dependencies: ProfileRouteDependencies,
    rollback: Boolean,
) {
    val request = call.receiveProfileAction() ?: return
    if (!request.optBoolean("confirm", false)) {
        call.respondProfileError(HttpStatusCode.Conflict, "explicit-confirmation-required")
        return
    }
    val expectedRevision = request.optLong("expected_catalog_revision", Long.MIN_VALUE)
    if (expectedRevision == Long.MIN_VALUE) {
        call.respondProfileError(HttpStatusCode.BadRequest, "expected-catalog-revision-required")
        return
    }
    val selection = if (rollback) {
        null
    } else if (request.optBoolean("auto", false)) {
        ProfileSelection.Auto
    } else {
        val ref = request.profileRefOrNull()
        if (ref == null) {
            call.respondProfileError(HttpStatusCode.BadRequest, "invalid-profile-ref")
            return
        }
        ProfileSelection.Pinned(ref)
    }
    val mutation = if (rollback) {
        dependencies.admin.rollbackToLastKnownGood(expectedRevision)
    } else {
        dependencies.admin.select(requireNotNull(selection), expectedRevision)
    }
    val restart = call.respondMutation(
        mutation,
        expectedCatalogRevision = expectedRevision,
        selectedRef = (selection as? ProfileSelection.Pinned)?.ref,
    )
    // The callback only schedules the START_STICKY process restart. Invoking it after respond lets the
    // accepted response queue first; the coordinator then supplies a short flush grace period.
    if (restart) runCatching(dependencies.requestRestart)
}

private suspend fun handleDelete(call: ApplicationCall, dependencies: ProfileRouteDependencies) {
    val request = call.receiveProfileAction() ?: return
    if (!request.optBoolean("confirm", false)) {
        call.respondProfileError(HttpStatusCode.Conflict, "explicit-confirmation-required")
        return
    }
    val ref = request.profileRefOrNull()
    val expectedRevision = request.optLong("expected_catalog_revision", Long.MIN_VALUE)
    if (ref == null || expectedRevision == Long.MIN_VALUE) {
        call.respondProfileError(HttpStatusCode.BadRequest, "invalid-delete-request")
        return
    }
    call.respondMutation(
        dependencies.admin.deleteProfile(ref, expectedRevision),
        expectedCatalogRevision = expectedRevision,
    )
}

private fun ProfileRouteDependencies.maxYamlBytes(): Long =
    minOf(hardMaxYamlBytes, admin.schema().maxBytes.toLong().coerceAtLeast(1L))

private suspend fun ApplicationCall.receiveProfileYaml(maxBytes: Long): String? {
    val contentType = request.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()
    if (contentType !in setOf("application/yaml", "application/x-yaml", "text/yaml", "text/plain")) {
        respondProfileError(HttpStatusCode.UnsupportedMediaType, "yaml-content-type-required")
        return null
    }
    return receiveBoundedUtf8(maxBytes, "profile-yaml-too-large")
}

private suspend fun ApplicationCall.receiveProfileAction(): JSONObject? {
    val contentType = request.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()
    if (contentType != ContentType.Application.Json.toString()) {
        respondProfileError(HttpStatusCode.UnsupportedMediaType, "json-content-type-required")
        return null
    }
    val body = receiveBoundedUtf8(MAX_PROFILE_ACTION_BYTES, "profile-action-too-large") ?: return null
    return try {
        JSONObject(body)
    } catch (_: Throwable) {
        respondProfileError(HttpStatusCode.BadRequest, "invalid-json")
        null
    }
}

private suspend fun ApplicationCall.receiveBoundedUtf8(maxBytes: Long, tooLarge: String): String? {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > maxBytes) {
        respondProfileError(HttpStatusCode.PayloadTooLarge, tooLarge)
        return null
    }
    val bytes = try {
        withContext(Dispatchers.IO) { receiveStream().use { BoundedStreams.readBytes(it, maxBytes) } }
    } catch (_: ByteLimitExceeded) {
        respondProfileError(HttpStatusCode.PayloadTooLarge, tooLarge)
        return null
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        respondProfileError(HttpStatusCode.BadRequest, "invalid-utf8")
        null
    }
}

private suspend fun ApplicationCall.profileRefOrRespond(): ProfileRef? {
    val ref = ProfileRef(parameters["id"].orEmpty(), parameters["revision"].orEmpty())
    if (!ref.valid()) {
        respondProfileError(HttpStatusCode.BadRequest, "invalid-profile-ref")
        return null
    }
    return ref
}

private fun JSONObject.profileRefOrNull(): ProfileRef? =
    ProfileRef(optString("id"), optString("revision")).takeIf { it.valid() }

private fun ProfileRef.valid(): Boolean = PROFILE_ID.matches(id) && ".." !in id && PROFILE_REVISION.matches(revision)

private suspend fun ApplicationCall.respondMutation(
    mutation: ProfileMutation,
    expectedCatalogRevision: Long? = null,
    selectedRef: ProfileRef? = null,
    importedRef: ProfileRef? = null,
): Boolean {
    return when (mutation) {
        is ProfileMutation.Success -> {
            val body = mutationJson(mutation).apply {
                selectedRef?.let { put("ref", refJson(it)) }
                importedRef?.let { put("imported_ref", refJson(it)) }
            }
            respondProfileJson(body, if (mutation.restartRequired) HttpStatusCode.Accepted else HttpStatusCode.OK)
            mutation.restartRequired
        }
        is ProfileMutation.Rejected -> {
            val staleCatalog = expectedCatalogRevision != null && mutation.status.catalogRevision != expectedCatalogRevision
            val stalePreview = mutation.issues.any {
                it.path == "preview_token" || it.message.contains("stale", ignoreCase = true) ||
                    it.message.contains("expired", ignoreCase = true)
            }
            respondProfileJson(
                mutationJson(mutation),
                if (staleCatalog || stalePreview) HttpStatusCode.Conflict else HttpStatusCode.UnprocessableEntity,
            )
            false
        }
    }
}

private suspend fun ApplicationCall.respondProfileJson(
    body: JSONObject,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respondText(body.toString(), ContentType.Application.Json, status)
}

private suspend fun ApplicationCall.respondProfileError(status: HttpStatusCode, error: String) {
    respondProfileJson(JSONObject().put("ok", false).put("error", error), status)
}

private suspend fun ApplicationCall.respondProfileYaml(yaml: String, fileName: String) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$fileName\"")
    respondText(yaml, ContentType.parse("application/yaml"))
}

private fun schemaJson(admin: ProfileAdmin): JSONObject {
    val schema = admin.schema()
    return JSONObject()
        .put("schema", schema.schema)
        .put("max_bytes", schema.maxBytes)
        .put("fields", JSONArray(schema.fields.map { field ->
            JSONObject()
                .put("path", field.path)
                .put("type", field.type)
                .put("required", field.required)
                .put("enum_values", JSONArray(field.enumValues))
                .put("description", field.description)
        }))
}

private fun statusJson(status: ProfileStatus): JSONObject = JSONObject()
    .put("catalog_revision", status.catalogRevision)
    .put("selection", selectionJson(status.selection))
    .put("active", status.active?.let(::summaryJson) ?: JSONObject.NULL)
    .put("activation", activationJson(status.activation))
    .put("rollback_ref", (status.lastKnownGood as? ProfileSelection.Pinned)?.ref?.let(::refJson) ?: JSONObject.NULL)
    .put("rollback_auto", status.lastKnownGood is ProfileSelection.Auto)
    .put("issues", JSONArray(status.issues.map(::issueJson)))

private fun activationJson(activation: ProfileActivationState): JSONObject = JSONObject()
    .put("state", activation.phase.name.lowercase())
    .put("generation", activation.generation)
    .put("previous", activation.previous?.let(::selectionJson) ?: JSONObject.NULL)
    .put("desired", activation.desired?.let(::selectionJson) ?: JSONObject.NULL)
    .put("message", activation.message ?: JSONObject.NULL)

private fun selectionJson(selection: ProfileSelection): JSONObject = when (selection) {
    ProfileSelection.Auto -> JSONObject().put("mode", "auto")
    is ProfileSelection.Pinned -> JSONObject().put("mode", "pinned").put("ref", refJson(selection.ref))
}

private fun summaryJson(summary: ProfileSummary): JSONObject = JSONObject()
    .put("ref", refJson(summary.ref))
    .put("display_name", summary.displayName)
    .put("origin", summary.origin.name.lowercase())
    .put("schema", summary.schema)
    .put("content_version", summary.contentVersion)
    .put("author", summary.author ?: JSONObject.NULL)
    .put("maturity", summary.maturity.name.lowercase())
    .put("min_core_version", summary.minCoreVersion ?: JSONObject.NULL)
    .put("matches_this_device", summary.matchesThisDevice)
    .put("active", summary.active)
    .put("selected", summary.selected)
    .put("shizuku_recommendation", summary.shizukuRecommendation.name.lowercase())
    .put("compatible", summary.compatible)
    .put("issues", JSONArray(summary.issues.map(::issueJson)))
    .put("risks", JSONArray(summary.risks.map { it.name.lowercase() }.sorted()))

private fun previewJson(preview: io.github.maxlyth.hapaneld.device.profile.ProfilePreview): JSONObject = JSONObject()
    .put("preview_token", preview.previewToken ?: JSONObject.NULL)
    .put("content_sha256", preview.contentSha256)
    .put("expires_at_epoch_ms", preview.expiresAtEpochMs ?: JSONObject.NULL)
    .put("summary", preview.summary?.let(::summaryJson) ?: JSONObject.NULL)
    .put("issues", JSONArray(preview.issues.map(::issueJson)))
    .put("diff_from_active", JSONArray(preview.diffFromActive.map(::diffJson)))
    .put("compatible", preview.compatible)

private fun mutationJson(mutation: ProfileMutation): JSONObject = when (mutation) {
    is ProfileMutation.Success -> JSONObject()
        .put("ok", true)
        .put("message", mutation.message)
        .put("restart_required", mutation.restartRequired)
        .put("catalog_revision", mutation.status.catalogRevision)
        .put("status", statusJson(mutation.status))
    is ProfileMutation.Rejected -> JSONObject()
        .put("ok", false)
        .put("error", "profile-mutation-rejected")
        .put("catalog_revision", mutation.status.catalogRevision)
        .put("status", statusJson(mutation.status))
        .put("issues", JSONArray(mutation.issues.map(::issueJson)))
}

private fun issueJson(issue: ProfileIssue): JSONObject = JSONObject()
    .put("severity", issue.severity.name.lowercase())
    .put("path", issue.path)
    .put("message", issue.message)

private fun diffJson(diff: ProfileDiff): JSONObject = JSONObject()
    .put("path", diff.path)
    .put("before", diff.before ?: JSONObject.NULL)
    .put("after", diff.after ?: JSONObject.NULL)

private fun refJson(ref: ProfileRef): JSONObject = JSONObject().put("id", ref.id).put("revision", ref.revision)

private fun reportJson(report: PassiveProfileReport): JSONObject = JSONObject()
    .put("generated_at_epoch_ms", report.generatedAtEpochMs)
    .put("facts", JSONObject()
        .put("model", report.facts.model)
        .put("device", report.facts.device)
        .put("product_version", report.facts.productVersion))
    .put("items", JSONArray(report.observations.map { observation ->
        JSONObject()
            .put("path", observation.path)
            .put("value", observation.value ?: JSONObject.NULL)
            .put("source", observation.source)
            .put("status", observation.confidence.name.lowercase())
            .put("note", observation.note ?: JSONObject.NULL)
    }))
    .put("issues", JSONArray(report.issues.map(::issueJson)))

private fun safeFileStem(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]+"), "-").take(128)
