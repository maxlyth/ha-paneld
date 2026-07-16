package io.github.maxlyth.hapaneld.device.profile

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.device.Generic
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import io.github.maxlyth.hapaneld.persistence.AppState
import io.github.maxlyth.hapaneld.util.SystemProps
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.SecureRandom

internal interface ProfileRevisionPersistence {
    fun createDirectory(directory: File): Boolean
    fun writeAndSync(file: File, bytes: ByteArray)
    fun atomicRename(source: File, target: File): Boolean
    fun delete(file: File): Boolean
    fun syncDirectory(directory: File)
}

internal object FileProfileRevisionPersistence : ProfileRevisionPersistence {
    override fun createDirectory(directory: File): Boolean = directory.isDirectory || directory.mkdir()

    override fun writeAndSync(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    override fun atomicRename(source: File, target: File): Boolean = source.renameTo(target)

    override fun delete(file: File): Boolean = file.delete()

    override fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }
}

/**
 * Local immutable-revision profile registry. Imports are inert until explicitly selected; selection is
 * staged for the next process start and rolls back if that activation never reports healthy.
 */
class RuntimeProfileRegistry internal constructor(
    private val filesDir: File,
    private val preferences: ProfilePreferences,
    private val bundledLoader: () -> Map<String, String>,
    private val facts: DeviceFacts,
    private val coreVersion: String,
    private val clock: () -> Long,
    private val revisionPersistence: ProfileRevisionPersistence = FileProfileRevisionPersistence,
    private val catalogFileReader: ((File) -> String)? = null,
) : ProfileAdmin, ProfileResolver {
    private data class StoredProfile(
        val ref: ProfileRef,
        val origin: ProfileOrigin,
        val rawYaml: String,
        val sourceBytes: Int,
        val document: ProfileDocument?,
        val issues: List<ProfileIssue> = emptyList(),
        val rollbackOnly: Boolean = false,
    ) {
        val compatible: Boolean get() = document != null && issues.none { it.severity == ProfileIssueSeverity.ERROR }
    }

    private val importedDir = File(filesDir, "device-profiles/imported")
    private val rollbackDir = File(filesDir, "device-profiles/rollback")
    private val previewTokens = PreviewTokenStore(clock)
    private var entries: Map<ProfileRef, StoredProfile> = emptyMap()
    private var catalogIssues: List<ProfileIssue> = emptyList()
    private var importedDiskCount: Int = 0
    private var importedDiskBytes: Long = 0
    private var importedDiskCountById: Map<String, Int> = emptyMap()
    private var fullyHydrated = false
    private var resolvedStartupRef: ProfileRef? = null

    init {
        loadStartupCatalog()
    }

    constructor(
        context: Context,
        facts: DeviceFacts = DeviceFacts(
            Build.MODEL.orEmpty(),
            Build.DEVICE.orEmpty(),
            SystemProps.get("ro.product.version"),
        ),
        coreVersion: String = BuildConfig.VERSION_NAME,
    ) : this(
        filesDir = context.applicationContext.filesDir,
        preferences = AndroidProfilePreferences(
            AppState.preferences(context, "device-profiles", PREFS_NAME),
        ),
        bundledLoader = {
            context.applicationContext.assets.list(BUNDLED_DIR).orEmpty()
                .filter { it.endsWith(".yaml") || it.endsWith(".yml") }
                .sorted()
                .associateWith { name ->
                    context.applicationContext.assets.open("$BUNDLED_DIR/$name").bufferedReader().use { it.readText() }
                }
        },
        facts = facts,
        coreVersion = coreVersion,
        clock = System::currentTimeMillis,
    )

    override fun schema(): ProfileSchemaDescriptor = ProfileMetadata.schema

    override fun drivers(): List<ProfileDriverDescriptor> = ProfileMetadata.drivers

    @Synchronized
    override fun status(): ProfileStatus {
        ensureHydrated()
        return statusLocked()
    }

    @Synchronized
    override fun list(): List<ProfileSummary> {
        ensureHydrated()
        val state = readActivation()
        val requestedSelection = state.desired ?: readSelection()
        val selectedRef = when (requestedSelection) {
            ProfileSelection.Auto -> resolveSelection(requestedSelection).entry?.ref
            is ProfileSelection.Pinned -> requestedSelection.ref
        }
        val activeSelection = if (state.phase == ProfileActivationPhase.PENDING) state.previous ?: ProfileSelection.Auto else readSelection()
        val activeRef = resolveSelection(activeSelection).entry?.ref
        return entries.values
            .map { summary(it, active = it.ref == activeRef, selected = it.ref == selectedRef) }
            .sortedWith(compareBy<ProfileSummary>({ it.origin }, { it.displayName.lowercase() }, { it.contentVersion }, { it.ref.revision }))
    }

    @Synchronized
    override fun preview(rawYaml: String): ProfilePreview {
        ensureHydrated()
        val parsed = measuredParse(rawYaml)
        val validation = parsed.document?.let { measuredValidate(it, bundled = false) }.orEmpty()
        val issues = parsed.issues + validation
        val compatible = parsed.document != null && issues.none { it.severity == ProfileIssueSeverity.ERROR }
        val token = if (compatible) previewTokens.issue(parsed.contentSha256) else null
        val document = parsed.document
        val ref = document?.let { ProfileRef(it.id, parsed.contentSha256) }
        val stored = if (document != null && ref != null) StoredProfile(
            ref, ProfileOrigin.IMPORTED, rawYaml, parsed.sourceBytes, document, issues,
        ) else null
        return ProfilePreview(
            previewToken = token?.value,
            contentSha256 = parsed.contentSha256,
            expiresAtEpochMs = token?.expiresAt,
            summary = stored?.let { summary(it, active = false, selected = false) },
            issues = issues,
            diffFromActive = document?.let { diffFromActive(it) }.orEmpty(),
            compatible = compatible,
        )
    }

    @Synchronized
    override fun importProfile(rawYaml: String, previewToken: String): ProfileMutation {
        ensureHydrated()
        val hash = ProfileYaml.sha256(rawYaml)
        if (!previewTokens.consume(previewToken, hash)) {
            return rejected("preview_token", "Preview token is invalid, expired, already used, or belongs to different content.")
        }
        val parsed = measuredParse(rawYaml)
        val document = parsed.document
        val issues = parsed.issues + document?.let { measuredValidate(it, bundled = false) }.orEmpty()
        if (document == null || issues.any { it.severity == ProfileIssueSeverity.ERROR }) return ProfileMutation.Rejected(statusLocked(), issues)
        val ref = ProfileRef(document.id, hash)
        if (entries[ref] == null) {
            val rawBytes = rawYaml.toByteArray(Charsets.UTF_8).size.toLong()
            val quotaIssue = when {
                importedDiskCount >= ProfileMetadata.MAX_IMPORTED_REVISIONS -> "Imported catalog is limited to ${ProfileMetadata.MAX_IMPORTED_REVISIONS} revisions."
                importedDiskCountById.getOrDefault(document.id, 0) >= ProfileMetadata.MAX_IMPORTED_REVISIONS_PER_ID -> "Profile '${document.id}' is limited to ${ProfileMetadata.MAX_IMPORTED_REVISIONS_PER_ID} revisions."
                importedDiskBytes > ProfileMetadata.MAX_IMPORTED_BYTES - rawBytes -> "Imported catalog is limited to ${ProfileMetadata.MAX_IMPORTED_BYTES} bytes."
                else -> null
            }
            if (quotaIssue != null) return rejected("catalog.quota", quotaIssue)
            // Reserve the optimistic-concurrency revision before touching disk. A crash or write failure
            // may conservatively make clients refresh, but can no longer change the catalog while leaving
            // its revision unchanged.
            if (!bumpCatalogRevision()) return rejected("catalog_revision", "Could not reserve the catalog change.")
            if (!writeImported(ref, rawYaml)) return rejected("$", "Could not store the immutable profile revision.")
            reload()
        }
        return ProfileMutation.Success(statusLocked(), restartRequired = false, message = "Imported ${document.displayName} ${document.version}.")
    }

    @Synchronized
    override fun exportProfile(ref: ProfileRef): String? {
        ensureHydrated()
        return entries[ref]?.rawYaml
    }

    @Synchronized
    override fun exportBackup(): ProfileBackup {
        ensureHydrated()
        val status = statusLocked()
        val revisions = entries.values.asSequence()
            .filter { it.origin == ProfileOrigin.IMPORTED }
            .sortedWith(compareBy<StoredProfile>({ it.ref.id }, { it.ref.revision }))
            .map { ProfileBackupRevision(it.ref, it.rawYaml) }
            .toList()
        return ProfileBackup(
            revisions = revisions,
            selection = status.selection,
            active = status.active?.ref,
            lastKnownGood = status.lastKnownGood,
        )
    }

    @Synchronized
    override fun planBackupRestore(payload: ProfileBackup): ProfileBackupRestorePlan {
        ensureHydrated()
        return planBackupRestoreLocked(payload)
    }

    @Synchronized
    override fun restoreBackup(
        payload: ProfileBackup,
        expectedCatalogRevision: Long,
    ): ProfileBackupRestoreResult {
        ensureHydrated()
        val before = statusLocked()
        if (expectedCatalogRevision != before.catalogRevision) {
            return rejectedBackupRestore(
                before,
                listOf(ProfileIssue(ProfileIssueSeverity.ERROR, "profiles.catalog_revision", "Catalog changed; revalidate the backup and retry.")),
            )
        }
        val plan = planBackupRestoreLocked(payload)
        if (!plan.valid) return rejectedBackupRestore(plan.status, plan.issues, plan.alreadyPresent)

        val candidates = payload.revisions.associateBy { it.ref }
        val imported = mutableListOf<ProfileRef>()
        val writeIssues = mutableListOf<ProfileIssue>()
        if (plan.toImport.isNotEmpty()) {
            // Reserve the CAS token before any file appears. A failed write can leave only inert,
            // immutable revisions behind and forces every concurrent administrator to refresh.
            if (!bumpCatalogRevision()) {
                return rejectedBackupRestore(
                    statusLocked(),
                    listOf(ProfileIssue(ProfileIssueSeverity.ERROR, "profiles.catalog_revision", "Could not reserve the catalog restore.")),
                    plan.alreadyPresent,
                )
            }
            plan.toImport.forEach { ref ->
                val raw = candidates.getValue(ref).rawYaml
                if (writeImported(ref, raw)) {
                    imported += ref
                } else {
                    writeIssues += ProfileIssue(
                        ProfileIssueSeverity.ERROR,
                        "profiles.revisions[${ref.id}@${ref.revision}]",
                        "Could not store the immutable revision.",
                    )
                }
            }
            reload()
            imported.toList().forEach { ref ->
                val expectedRaw = candidates.getValue(ref).rawYaml
                val stored = entries[ref]
                if (stored?.compatible != true || stored.rawYaml != expectedRaw) {
                    imported.remove(ref)
                    writeIssues += ProfileIssue(
                        ProfileIssueSeverity.ERROR,
                        "profiles.revisions[${ref.id}@${ref.revision}]",
                        "Stored revision did not pass the post-write coherency check.",
                    )
                }
            }
        }
        if (writeIssues.isNotEmpty()) {
            return ProfileBackupRestoreResult(
                outcome = ProfileBackupRestoreOutcome.PARTIAL,
                status = statusLocked(),
                imported = imported.sortedRefs(),
                alreadyPresent = plan.alreadyPresent,
                issues = plan.issues + writeIssues,
                selectionStaged = false,
                restartRequired = false,
                message = "Some profile revisions were imported inertly; selection was not changed.",
            )
        }

        val current = readSelection()
        if (payload.selection == current) {
            return ProfileBackupRestoreResult(
                outcome = ProfileBackupRestoreOutcome.SUCCEEDED,
                status = statusLocked(),
                imported = imported.sortedRefs(),
                alreadyPresent = plan.alreadyPresent,
                issues = plan.issues,
                selectionStaged = false,
                restartRequired = false,
                message = "Profile catalog restored; selection is unchanged.",
            )
        }

        // Do not copy transient activation state or replace the destination rollback target. select()
        // records the current proven selection as `previous` and health-gates the restored selection.
        return when (val selected = select(payload.selection, catalogRevision())) {
            is ProfileMutation.Success -> ProfileBackupRestoreResult(
                outcome = ProfileBackupRestoreOutcome.SUCCEEDED,
                status = selected.status,
                imported = imported.sortedRefs(),
                alreadyPresent = plan.alreadyPresent,
                issues = plan.issues,
                selectionStaged = true,
                restartRequired = selected.restartRequired,
                message = "Profile catalog restored; selection staged for a health-gated restart.",
            )
            is ProfileMutation.Rejected -> ProfileBackupRestoreResult(
                outcome = if (imported.isEmpty()) ProfileBackupRestoreOutcome.REJECTED else ProfileBackupRestoreOutcome.PARTIAL,
                status = selected.status,
                imported = imported.sortedRefs(),
                alreadyPresent = plan.alreadyPresent,
                issues = plan.issues + selected.issues,
                selectionStaged = false,
                restartRequired = false,
                message = if (imported.isEmpty()) {
                    "Profile selection could not be restored."
                } else {
                    "Profile revisions were imported inertly, but selection could not be staged."
                },
            )
        }
    }

    @Synchronized
    override fun select(selection: ProfileSelection, expectedCatalogRevision: Long): ProfileMutation {
        ensureHydrated()
        if (expectedCatalogRevision != catalogRevision()) return staleCatalog()
        if (selection is ProfileSelection.Pinned) {
            val entry = entries[selection.ref] ?: return rejected("selection", "Profile revision does not exist.")
            if (!entry.compatible) return ProfileMutation.Rejected(
                statusLocked(),
                entry.issues.ifEmpty { listOf(ProfileIssue(ProfileIssueSeverity.ERROR, "selection", "Profile revision is incompatible with this core.")) },
            )
        }
        val state = readActivation()
        if (state.phase == ProfileActivationPhase.PENDING || state.phase == ProfileActivationPhase.APPLYING) {
            return rejected("activation", "A profile activation is already in progress.")
        }
        val current = readSelection()
        if (selection == current) return ProfileMutation.Success(statusLocked(), false, "Profile selection is unchanged.")
        val generation = maxOf(state.generation, preferences.getLong(KEY_GENERATION, 0L)) + 1
        val next = ProfileActivationState(
            phase = ProfileActivationPhase.PENDING,
            generation = generation,
            previous = current,
            desired = selection,
            message = "Selection staged; restart required.",
        )
        if (!preferences.put(
                KEY_SELECTION to encode(selection),
                KEY_PHASE to next.phase.name,
                KEY_GENERATION to generation,
                KEY_PREVIOUS to encode(current),
                KEY_DESIRED to encode(selection),
                KEY_MESSAGE to next.message,
                KEY_CATALOG_REVISION to catalogRevision() + 1,
            )) return rejected("activation", "Could not persist the staged selection.")
        return ProfileMutation.Success(statusLocked(), true, "Profile selection staged for restart.")
    }

    @Synchronized
    override fun rollbackToLastKnownGood(expectedCatalogRevision: Long): ProfileMutation {
        ensureHydrated()
        if (expectedCatalogRevision != catalogRevision()) return staleCatalog()
        val target = readLastKnownGood() ?: return rejected("last_known_good", "No previous proven selection is available.")
        return select(target, expectedCatalogRevision)
    }

    @Synchronized
    override fun deleteProfile(ref: ProfileRef, expectedCatalogRevision: Long): ProfileMutation {
        ensureHydrated()
        if (expectedCatalogRevision != catalogRevision()) return staleCatalog()
        val entry = entries[ref] ?: return rejected("profile", "Profile revision does not exist.")
        if (entry.origin != ProfileOrigin.IMPORTED) return rejected("profile", "Bundled profile revisions cannot be deleted.")
        val state = readActivation()
        val referenced = listOfNotNull(readSelection(), state.previous, state.desired, readLastKnownGood())
            .any { selection -> selection is ProfileSelection.Pinned && selection.ref == ref }
        val active = statusLocked().active?.ref == ref
        if (referenced || active) return rejected("profile", "Selected, active, or rollback profile revisions cannot be deleted.")
        val file = importedFile(ref)
        // As with import, advance the CAS token first. A failed delete can cause only a harmless extra
        // refresh; a successful delete can never be published under the old catalog revision.
        if (!bumpCatalogRevision()) return rejected("catalog_revision", "Could not reserve the catalog change.")
        if (!deleteImmutable(file)) {
            reload()
            return rejected("profile", "Could not durably delete the imported revision.")
        }
        reload()
        return ProfileMutation.Success(statusLocked(), false, "Deleted imported profile revision.")
    }

    /** Called once, before controllers are constructed for this process lifetime. */
    @Synchronized
    override fun resolveForStartup(): ResolvedProfile {
        val cost = FeatureCosts.registry.span(FeatureCostOperation.PROFILE_STARTUP_RESOLVE)
        return try {
            val state = readActivation()
            when (state.phase) {
            ProfileActivationPhase.PENDING -> {
                val desired = state.desired ?: readSelection()
                val resolution = resolveSelection(desired)
                if (resolution.entry == null) {
                    rollbackInvalidPending(state, resolution.issues)
                } else if (!preferences.put(
                        KEY_PHASE to ProfileActivationPhase.APPLYING.name,
                        KEY_MESSAGE to "Applying selected profile.",
                        KEY_CATALOG_REVISION to catalogRevision() + 1,
                    )) {
                    rollbackInvalidPending(
                        state,
                        listOf(ProfileIssue(ProfileIssueSeverity.ERROR, "activation", "Could not persist the applying state.")),
                    )
                } else {
                    resolved(desired, state.generation)
                }
            }
            ProfileActivationPhase.APPLYING -> {
                val rollback = compatibleRollbackTarget(state.previous)
                val persisted = preferences.put(
                    KEY_SELECTION to encode(rollback),
                    KEY_PHASE to ProfileActivationPhase.ROLLED_BACK.name,
                    KEY_PREVIOUS to null,
                    KEY_DESIRED to null,
                    KEY_MESSAGE to "Previous activation did not report healthy; rolled back.",
                    KEY_CATALOG_REVISION to catalogRevision() + 1,
                )
                val issue = if (persisted) {
                    ProfileIssue(ProfileIssueSeverity.WARNING, "activation", "Previous profile activation was rolled back after an unhealthy restart.")
                } else {
                    ProfileIssue(ProfileIssueSeverity.ERROR, "activation", "Using the previous profile for this run, but the rollback could not be persisted and will be retried after restart.")
                }
                resolved(rollback, null, listOf(issue))
            }
            ProfileActivationPhase.ACTIVE, ProfileActivationPhase.ROLLED_BACK -> {
                val selection = readSelection()
                val resolution = resolveSelection(selection)
                if (selection is ProfileSelection.Pinned && resolution.entry == null) {
                    recoverInvalidActive(selection, resolution.issues)
                } else if (selection == ProfileSelection.Auto) {
                    val current = resolution.entry
                    val previous = readActiveRef()
                    if (current != null && previous != null && previous != current.ref && entries[previous]?.compatible == true) {
                        val generation = maxOf(state.generation, preferences.getLong(KEY_GENERATION, 0L)) + 1
                        if (preferences.put(
                                KEY_PHASE to ProfileActivationPhase.APPLYING.name,
                                KEY_GENERATION to generation,
                                KEY_PREVIOUS to encode(ProfileSelection.Pinned(previous)),
                                KEY_DESIRED to encode(ProfileSelection.Auto),
                                KEY_MESSAGE to "Applying updated automatically matched profile.",
                                KEY_CATALOG_REVISION to catalogRevision() + 1,
                            )) {
                            resolved(selection, generation)
                        } else {
                            resolved(ProfileSelection.Pinned(previous), null, listOf(ProfileIssue(ProfileIssueSeverity.ERROR, "activation", "Could not stage the automatically updated profile; retained the previous revision.")))
                        }
                    } else {
                        resolved(selection, null)
                    }
                } else {
                    resolved(selection, null)
                }
            }
            }.also { resolved ->
                resolvedStartupRef = resolved.summary.ref.takeIf { it in entries }
            }
        } catch (error: Throwable) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            cost.work(units = 1L).close()
        }
    }

    @Synchronized
    override fun markActivationHealthy(generation: Long): Boolean {
        val state = readActivation()
        if (state.phase != ProfileActivationPhase.APPLYING || state.generation != generation) return false
        val activeRef = resolveSelection(readSelection()).entry?.ref ?: return false
        if (!writeRollbackSnapshot(activeRef)) return false
        val persisted = preferences.put(
            KEY_PHASE to ProfileActivationPhase.ACTIVE.name,
            KEY_ACTIVE_REF to encode(ProfileSelection.Pinned(activeRef)),
            KEY_LAST_KNOWN_GOOD to (state.previous ?: readLastKnownGood())?.let(::encode),
            KEY_PREVIOUS to null,
            KEY_DESIRED to null,
            KEY_MESSAGE to null,
            KEY_CATALOG_REVISION to catalogRevision() + 1,
        )
        if (persisted) pruneRollbackSnapshots(activeRef)
        return persisted
    }

    /** Records the proven runtime revision after an ordinary (non-activation) startup. */
    @Synchronized
    fun markStartupHealthy(ref: ProfileRef): Boolean {
        val state = readActivation()
        if (state.phase == ProfileActivationPhase.PENDING || state.phase == ProfileActivationPhase.APPLYING) return false
        if (!writeRollbackSnapshot(ref)) return false
        val persisted = preferences.put(KEY_ACTIVE_REF to encode(ProfileSelection.Pinned(ref)))
        if (persisted) pruneRollbackSnapshots(ref)
        return persisted
    }

    /** Records the revision returned by [resolveForStartup] without hydrating the admin catalog. */
    @Synchronized
    fun markResolvedStartupHealthy(): Boolean {
        val ref = resolvedStartupRef ?: return false
        return markStartupHealthy(ref)
    }

    /** Cancels a staged restart when teardown could not release every restart-critical owner. */
    @Synchronized
    fun abortPendingActivation(message: String): Boolean {
        val state = readActivation()
        if (state.phase != ProfileActivationPhase.PENDING) return false
        val rollback = state.previous ?: readLastKnownGood() ?: ProfileSelection.Auto
        return preferences.put(
            KEY_SELECTION to encode(rollback),
            KEY_PHASE to ProfileActivationPhase.ROLLED_BACK.name,
            KEY_PREVIOUS to null,
            KEY_DESIRED to null,
            KEY_MESSAGE to message.trim().take(240).ifBlank { "Profile restart was cancelled during teardown." },
            KEY_CATALOG_REVISION to catalogRevision() + 1,
        )
    }

    private data class SelectionResolution(val entry: StoredProfile?, val issues: List<ProfileIssue>)

    private fun planBackupRestoreLocked(payload: ProfileBackup): ProfileBackupRestorePlan {
        val status = statusLocked()
        val issues = mutableListOf<ProfileIssue>()
        if (payload.schema != ProfileBackup.SCHEMA) {
            issues += ProfileIssue(ProfileIssueSeverity.ERROR, "profiles.schema", "Unsupported profile backup schema '${payload.schema}'.")
        }
        if (payload.revisions.size > ProfileMetadata.MAX_IMPORTED_REVISIONS) {
            issues += ProfileIssue(ProfileIssueSeverity.ERROR, "profiles.revisions", "Profile backup exceeds the revision limit.")
        }
        val seen = mutableSetOf<ProfileRef>()
        val validated = linkedMapOf<ProfileRef, ProfileBackupRevision>()
        val alreadyPresent = mutableListOf<ProfileRef>()
        var payloadBytes = 0L
        var newBytes = 0L
        val payloadById = mutableMapOf<String, Int>()
        val newById = mutableMapOf<String, Int>()
        payload.revisions.sortedWith(compareBy<ProfileBackupRevision>({ it.ref.id }, { it.ref.revision }))
            .forEachIndexed { index, candidate ->
                val path = "profiles.revisions[$index]"
                if (!seen.add(candidate.ref)) {
                    issues += ProfileIssue(ProfileIssueSeverity.ERROR, path, "Duplicate immutable revision.")
                    return@forEachIndexed
                }
                val parsed = measuredParse(candidate.rawYaml)
                val document = parsed.document
                val candidateIssues = parsed.issues + document?.let { measuredValidate(it, bundled = false) }.orEmpty()
                issues += candidateIssues.map { it.copy(path = "$path.${it.path}") }
                val sourceBytes = parsed.sourceBytes.toLong()
                payloadBytes = if (Long.MAX_VALUE - payloadBytes < sourceBytes) Long.MAX_VALUE else payloadBytes + sourceBytes
                val payloadIdCount = payloadById.getOrDefault(candidate.ref.id, 0) + 1
                payloadById[candidate.ref.id] = payloadIdCount
                if (payloadIdCount > ProfileMetadata.MAX_IMPORTED_REVISIONS_PER_ID) {
                    issues += ProfileIssue(ProfileIssueSeverity.ERROR, path, "Profile '${candidate.ref.id}' exceeds the backup revision limit.")
                }
                if (parsed.contentSha256 != candidate.ref.revision) {
                    issues += ProfileIssue(ProfileIssueSeverity.ERROR, "$path.revision", "Revision does not match the YAML SHA-256.")
                }
                if (document != null && document.id != candidate.ref.id) {
                    issues += ProfileIssue(ProfileIssueSeverity.ERROR, "$path.id", "Revision id does not match the YAML document id.")
                }
                if (parsed.sourceBytes > ProfileMetadata.MAX_BYTES) {
                    issues += ProfileIssue(ProfileIssueSeverity.ERROR, "$path.yaml", "Revision exceeds the per-file size limit.")
                }
                if (document == null || candidateIssues.any { it.severity == ProfileIssueSeverity.ERROR } ||
                    parsed.contentSha256 != candidate.ref.revision || document.id != candidate.ref.id
                ) return@forEachIndexed

                val existing = entries[candidate.ref]
                if (existing != null) {
                    if (!existing.compatible || existing.rawYaml != candidate.rawYaml) {
                        issues += ProfileIssue(ProfileIssueSeverity.ERROR, path, "Existing immutable revision conflicts with the backup.")
                    } else {
                        alreadyPresent += candidate.ref
                        validated[candidate.ref] = candidate
                    }
                    return@forEachIndexed
                }
                val bytes = sourceBytes
                newBytes = if (Long.MAX_VALUE - newBytes < bytes) Long.MAX_VALUE else newBytes + bytes
                newById[candidate.ref.id] = newById.getOrDefault(candidate.ref.id, 0) + 1
                validated[candidate.ref] = candidate
            }

        if (payloadBytes > ProfileMetadata.MAX_IMPORTED_BYTES) {
            issues += ProfileIssue(ProfileIssueSeverity.ERROR, "profiles.revisions", "Profile backup exceeds the aggregate byte limit.")
        }

        val toImport = validated.keys.filterNot { it in alreadyPresent }.sortedRefs()
        if (importedDiskCount > ProfileMetadata.MAX_IMPORTED_REVISIONS - toImport.size) {
            issues += ProfileIssue(ProfileIssueSeverity.ERROR, "profiles.revisions", "Restored catalog would exceed the revision limit.")
        }
        newById.toSortedMap().forEach { (id, count) ->
            if (importedDiskCountById.getOrDefault(id, 0) > ProfileMetadata.MAX_IMPORTED_REVISIONS_PER_ID - count) {
                issues += ProfileIssue(ProfileIssueSeverity.ERROR, "profiles.revisions", "Restored profile '$id' would exceed its revision limit.")
            }
        }
        if (importedDiskBytes > ProfileMetadata.MAX_IMPORTED_BYTES - newBytes) {
            issues += ProfileIssue(ProfileIssueSeverity.ERROR, "profiles.revisions", "Restored catalog would exceed the aggregate byte limit.")
        }

        val available = entries.filterValues { it.compatible }.keys + validated.keys
        fun requireSelection(selection: ProfileSelection?, path: String) {
            val ref = (selection as? ProfileSelection.Pinned)?.ref ?: return
            if (ref !in available) {
                issues += ProfileIssue(ProfileIssueSeverity.ERROR, path, "Referenced immutable revision is missing or incompatible.")
            }
        }
        requireSelection(payload.selection, "profiles.selection")
        (payload.lastKnownGood as? ProfileSelection.Pinned)?.ref?.takeIf { it !in available }?.let {
            issues += ProfileIssue(
                ProfileIssueSeverity.WARNING,
                "profiles.last_known_good",
                "The source rollback revision is not present in this core; the destination's current selection remains the rollback target.",
            )
        }
        payload.active?.takeIf { it !in available }?.let {
            issues += ProfileIssue(
                ProfileIssueSeverity.WARNING,
                "profiles.active",
                "The source active revision is not present in this core; it will not be activated.",
            )
        }
        val activation = readActivation()
        if (activation.phase == ProfileActivationPhase.PENDING || activation.phase == ProfileActivationPhase.APPLYING) {
            issues += ProfileIssue(ProfileIssueSeverity.ERROR, "profiles.activation", "A profile activation is already in progress.")
        }
        return ProfileBackupRestorePlan(
            status = status,
            expectedCatalogRevision = status.catalogRevision,
            valid = issues.none { it.severity == ProfileIssueSeverity.ERROR },
            toImport = toImport,
            alreadyPresent = alreadyPresent.sortedRefs(),
            issues = issues,
            restartRequired = payload.selection != readSelection(),
        )
    }

    private fun rejectedBackupRestore(
        status: ProfileStatus,
        issues: List<ProfileIssue>,
        alreadyPresent: List<ProfileRef> = emptyList(),
    ) = ProfileBackupRestoreResult(
        outcome = ProfileBackupRestoreOutcome.REJECTED,
        status = status,
        imported = emptyList(),
        alreadyPresent = alreadyPresent,
        issues = issues,
        selectionStaged = false,
        restartRequired = false,
        message = "Profile catalog restore was rejected before mutation.",
    )

    private fun Iterable<ProfileRef>.sortedRefs(): List<ProfileRef> =
        sortedWith(compareBy<ProfileRef>({ it.id }, { it.revision }))

    private fun resolveSelection(selection: ProfileSelection): SelectionResolution = when (selection) {
        ProfileSelection.Auto -> autoResolve()
        is ProfileSelection.Pinned -> {
            val entry = entries[selection.ref]
            when {
                entry == null -> SelectionResolution(null, listOf(ProfileIssue(ProfileIssueSeverity.ERROR, "selection", "Pinned revision is missing.")))
                !entry.compatible -> SelectionResolution(
                    null,
                    entry.issues.ifEmpty { listOf(ProfileIssue(ProfileIssueSeverity.ERROR, "selection", "Pinned revision is incompatible with this core.")) },
                )
                else -> SelectionResolution(entry, emptyList())
            }
        }
    }

    private fun autoResolve(): SelectionResolution {
        val bundled = entries.values.filter { it.origin == ProfileOrigin.BUNDLED && !it.rollbackOnly }
        val generic = bundled.singleOrNull { it.document?.match?.fallback == true && it.compatible }
        val bundledMatches = bundled.filter { it.compatible && it.document?.matches(facts) == true }
        val chosenBundled = highestUnique(bundledMatches)
        if (chosenBundled.entry != null) return chosenBundled
        if (bundledMatches.isNotEmpty()) return SelectionResolution(generic, chosenBundled.issues)
        // Local/community revisions are never activated from a fingerprint alone. Their match block is
        // preview information; an administrator must pin the immutable revision explicitly.
        return if (generic != null) SelectionResolution(generic, emptyList())
        else SelectionResolution(null, listOf(ProfileIssue(ProfileIssueSeverity.ERROR, "catalog", "Bundled generic fallback is missing.")))
    }

    private fun highestUnique(candidates: List<StoredProfile>): SelectionResolution {
        if (candidates.isEmpty()) return SelectionResolution(null, emptyList())
        val topGroupPriority = candidates.maxOf { it.document?.matchedGroupPriority(facts) ?: -1 }
        val groupHighest = candidates.filter { (it.document?.matchedGroupPriority(facts) ?: -1) == topGroupPriority }
        val topProfilePriority = groupHighest.maxOf { it.document?.match?.priority ?: -1 }
        val highest = groupHighest.filter { it.document?.match?.priority == topProfilePriority }
        return if (highest.size == 1) SelectionResolution(highest.single(), emptyList())
        else SelectionResolution(null, listOf(ProfileIssue(ProfileIssueSeverity.ERROR, "match", "Ambiguous automatic match at branch priority $topGroupPriority: ${highest.joinToString { it.ref.id }}.")))
    }

    private fun resolved(selection: ProfileSelection, generation: Long?, extraIssues: List<ProfileIssue> = emptyList()): ResolvedProfile {
        val resolution = resolveSelection(selection)
        val entry = resolution.entry ?: entries.values.firstOrNull {
            it.origin == ProfileOrigin.BUNDLED && !it.rollbackOnly && it.compatible && it.document?.match?.fallback == true
        }
        if (entry == null) {
            return ResolvedProfile(
                profile = Generic,
                summary = emergencySummary(active = true),
                activationGeneration = null,
                issues = catalogIssues + resolution.issues + extraIssues + ProfileIssue(
                    ProfileIssueSeverity.ERROR,
                    "catalog",
                    "Using the compiled emergency Generic profile because the bundled fallback is unavailable.",
                ),
            )
        }
        return ResolvedProfile(
            profile = DataDeviceProfile(
                requireNotNull(entry.document),
                facts.productVersion,
                revision = entry.ref.revision,
            ),
            summary = summary(entry, active = true, selected = true),
            activationGeneration = generation,
            issues = catalogIssues + resolution.issues + extraIssues,
        )
    }

    private fun rollbackInvalidPending(state: ProfileActivationState, problems: List<ProfileIssue>): ResolvedProfile {
        val rollback = compatibleRollbackTarget(state.previous)
        val persisted = preferences.put(
            KEY_SELECTION to encode(rollback),
            KEY_PHASE to ProfileActivationPhase.ROLLED_BACK.name,
            KEY_PREVIOUS to null,
            KEY_DESIRED to null,
            KEY_MESSAGE to "Selected profile could not be resolved; rolled back.",
            KEY_CATALOG_REVISION to catalogRevision() + 1,
        )
        return resolved(
            rollback,
            generation = null,
            extraIssues = problems + ProfileIssue(
                ProfileIssueSeverity.ERROR,
                "activation",
                if (persisted) "Selected profile could not be activated; the previous selection was restored."
                else "Selected profile could not be activated; using the previous selection for this run, but the rollback could not be persisted.",
            ),
        )
    }

    private fun recoverInvalidActive(
        invalid: ProfileSelection.Pinned,
        problems: List<ProfileIssue>,
    ): ResolvedProfile {
        val rollback = compatibleRollbackTarget(readLastKnownGood(), excluding = invalid.ref)
        val lkg = readLastKnownGood()
        val keepLkg = lkg?.takeIf { resolveSelection(it).entry != null }
        val persisted = preferences.put(
            KEY_SELECTION to encode(rollback),
            KEY_PHASE to ProfileActivationPhase.ROLLED_BACK.name,
            KEY_PREVIOUS to null,
            KEY_DESIRED to null,
            KEY_LAST_KNOWN_GOOD to keepLkg?.let(::encode),
            KEY_MESSAGE to "Active profile became incompatible after a core change; restored the last known good selection.",
            KEY_CATALOG_REVISION to catalogRevision() + 1,
        )
        return resolved(
            rollback,
            generation = null,
            extraIssues = problems + ProfileIssue(
                ProfileIssueSeverity.ERROR,
                "activation",
                if (persisted) {
                    "Profile ${invalid.ref.id}@${invalid.ref.revision.take(12)} is incompatible; last known good selection restored."
                } else {
                    "Profile ${invalid.ref.id}@${invalid.ref.revision.take(12)} is incompatible; using the rollback selection for this run, but recovery could not be persisted."
                },
            ),
        )
    }

    private fun compatibleRollbackTarget(
        preferred: ProfileSelection?,
        excluding: ProfileRef? = null,
    ): ProfileSelection {
        val candidates = listOfNotNull(preferred, readLastKnownGood(), ProfileSelection.Auto).distinct()
        return candidates.firstOrNull { candidate ->
            (candidate as? ProfileSelection.Pinned)?.ref != excluding && resolveSelection(candidate).entry != null
        } ?: ProfileSelection.Auto
    }

    private fun summary(entry: StoredProfile, active: Boolean, selected: Boolean): ProfileSummary = ProfileSummary(
        ref = entry.ref,
        displayName = entry.document?.displayName ?: entry.ref.id,
        origin = entry.origin,
        schema = entry.document?.schema ?: 0,
        minCoreVersion = entry.document?.requires?.minCoreVersion,
        matchesThisDevice = entry.document?.matches(facts) == true,
        active = active,
        selected = selected,
        shizukuRecommendation = entry.document?.provisioning?.access?.shizuku ?: ShizukuRecommendation.NONE,
        risks = entry.document?.let { document ->
            ProfileValidator.risks(document, entry.origin == ProfileOrigin.IMPORTED && entries.values.any { it.origin == ProfileOrigin.BUNDLED && it.ref.id == entry.ref.id })
        }.orEmpty(),
        contentVersion = entry.document?.version.orEmpty(),
        author = entry.document?.metadata?.author,
        maturity = entry.document?.metadata?.maturity ?: ProfileMaturity.DRAFT,
        compatible = entry.compatible,
        issues = entry.issues,
    )

    private fun statusLocked(): ProfileStatus {
        val activation = readActivation()
        val selection = readSelection()
        val activeSelection = if (activation.phase == ProfileActivationPhase.PENDING) activation.previous ?: ProfileSelection.Auto else selection
        val resolution = resolveSelection(activeSelection)
        val activeEntry = resolution.entry ?: entries.values.firstOrNull {
            it.origin == ProfileOrigin.BUNDLED && !it.rollbackOnly && it.compatible && it.document?.match?.fallback == true
        }
        val requestedRef = resolveSelection(selection).entry?.ref
        val active = activeEntry?.let { summary(it, active = true, selected = it.ref == requestedRef) }
            ?: emergencySummary(active = true)
        return ProfileStatus(
            catalogRevision = catalogRevision(),
            selection = selection,
            active = active,
            activation = activation,
            issues = catalogIssues + resolution.issues,
            lastKnownGood = readLastKnownGood(),
        )
    }

    private fun diffFromActive(candidate: ProfileDocument): List<ProfileDiff> {
        val activeDocument = statusLocked().active?.ref?.let { entries[it]?.document } ?: return emptyList()
        val before = flatten(activeDocument.toYamlMap())
        val after = flatten(candidate.toYamlMap())
        return (before.keys + after.keys).toSortedSet().mapNotNull { path ->
            if (before[path] == after[path]) null else ProfileDiff(path, before[path], after[path])
        }.take(MAX_DIFFS)
    }

    private fun flatten(value: Any?, path: String = "", output: MutableMap<String, String?> = linkedMapOf()): Map<String, String?> {
        when (value) {
            is Map<*, *> -> value.forEach { (key, child) -> flatten(child, if (path.isEmpty()) key.toString() else "$path.$key", output) }
            is List<*> -> value.forEachIndexed { index, child -> flatten(child, "$path[$index]", output) }
            else -> output[path] = value?.toString()
        }
        return output
    }

    private fun ensureHydrated() {
        if (!fullyHydrated) reload()
    }

    /**
     * Foreground startup needs every bundled asset for automatic matching, but imported revisions are
     * inert unless an administrator pins them. Read only pinned revisions that can participate in the
     * current activation or its recovery; the rest of the imported catalog is hydrated on first admin use.
     */
    private fun loadStartupCatalog() {
        val cost = FeatureCosts.registry.span(FeatureCostOperation.PROFILE_CATALOG_LOAD)
        val loaded = linkedMapOf<ProfileRef, StoredProfile>()
        val issues = mutableListOf<ProfileIssue>()
        try {
            bundledLoader().forEach { (name, raw) ->
                loadEntry(raw, ProfileOrigin.BUNDLED, "assets/$BUNDLED_DIR/$name", loaded, issues)
            }
            val activation = readActivation()
            val required = listOfNotNull(
                readSelection(),
                activation.previous,
                activation.desired,
                readLastKnownGood(),
                readActiveRef()?.let { ProfileSelection.Pinned(it) },
            ).filterIsInstance<ProfileSelection.Pinned>()
                .mapTo(linkedSetOf()) { it.ref }
            required.forEach { ref ->
                if (ref in loaded) return@forEach
                val imported = importedFile(ref)
                val rollback = rollbackFile(ref)
                when {
                    // Full hydration admits retained bundled rollback snapshots before imports. Preserve
                    // that precedence if an unexpected duplicate exists in both stores.
                    rollback.isFile -> loadStartupFile(
                        rollback,
                        ref,
                        ProfileOrigin.BUNDLED,
                        rollbackOnly = true,
                        loaded = loaded,
                        issues = issues,
                    )
                    imported.isFile -> loadStartupFile(
                        imported,
                        ref,
                        ProfileOrigin.IMPORTED,
                        rollbackOnly = false,
                        loaded = loaded,
                        issues = issues,
                    )
                }
            }
            entries = loaded
            if (loaded.values.none {
                    it.origin == ProfileOrigin.BUNDLED && !it.rollbackOnly &&
                        it.compatible && it.document?.match?.fallback == true
                }
            ) {
                issues += ProfileIssue(
                    ProfileIssueSeverity.ERROR,
                    "catalog",
                    "Bundled generic fallback is missing or invalid; compiled emergency fallback will be used.",
                )
            }
            catalogIssues = issues
        } catch (error: Throwable) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            val bytes = loaded.values.fold(0L) { total, entry ->
                val size = entry.sourceBytes.toLong()
                if (Long.MAX_VALUE - total < size) Long.MAX_VALUE else total + size
            }
            cost.work(units = loaded.size.toLong(), bytes = bytes).close()
        }
    }

    private fun loadStartupFile(
        file: File,
        expected: ProfileRef,
        origin: ProfileOrigin,
        rollbackOnly: Boolean,
        loaded: MutableMap<ProfileRef, StoredProfile>,
        issues: MutableList<ProfileIssue>,
    ) {
        val raw = runCatching { readCatalogFile(file) }.getOrElse {
            issues += ProfileIssue(
                ProfileIssueSeverity.ERROR,
                "catalog[${file.path}]",
                "Could not read required profile revision.",
            )
            return
        }
        loadEntry(raw, origin, file.path, loaded, issues, expectedRef = expected, rollbackOnly = rollbackOnly)
    }

    private fun reload() {
        val cost = FeatureCosts.registry.span(FeatureCostOperation.PROFILE_CATALOG_LOAD)
        val loaded = linkedMapOf<ProfileRef, StoredProfile>()
        val issues = mutableListOf<ProfileIssue>()
        try {
        bundledLoader().forEach { (name, raw) -> loadEntry(raw, ProfileOrigin.BUNDLED, "assets/$BUNDLED_DIR/$name", loaded, issues) }
        rollbackDir.listFiles().orEmpty().filter { it.isDirectory }.flatMap { directory ->
            directory.listFiles().orEmpty().filter { it.isFile && it.extension == "yaml" }
        }.forEach { file ->
            val expected = expectedRef(file) ?: return@forEach
            if (expected in loaded) return@forEach
            val raw = runCatching { readCatalogFile(file) }.getOrNull() ?: return@forEach
            loadEntry(raw, ProfileOrigin.BUNDLED, file.path, loaded, issues, expectedRef = expected, rollbackOnly = true)
        }
        val importedFiles = importedDir.listFiles().orEmpty().filter { it.isDirectory }.flatMap { idDir ->
            idDir.listFiles().orEmpty().filter { it.isFile && it.extension == "yaml" }
        }
        importedDiskCount = importedFiles.size
        importedDiskBytes = importedFiles.fold(0L) { total, file ->
            val length = file.length().coerceAtLeast(0)
            if (Long.MAX_VALUE - total < length) Long.MAX_VALUE else total + length
        }
        importedDiskCountById = importedFiles.groupingBy { it.parentFile?.name.orEmpty() }.eachCount()
        val activation = readActivation()
        val protected = listOfNotNull(readSelection(), activation.previous, activation.desired, readLastKnownGood())
            .filterIsInstance<ProfileSelection.Pinned>().mapTo(mutableSetOf()) { it.ref }
        val orderedFiles = importedFiles.sortedWith(
            compareBy<File> { file -> expectedRef(file) !in protected }
                .thenBy { it.parentFile?.name.orEmpty() }
                .thenBy { it.name },
        )
        var admittedCount = 0
        var admittedBytes = 0L
        val admittedById = mutableMapOf<String, Int>()
        orderedFiles.forEach { file ->
            val expected = expectedRef(file)
            if (expected == null) {
                issues += ProfileIssue(ProfileIssueSeverity.ERROR, "catalog[${file.path}]", "Imported revision path is not canonical <id>/<sha256>.yaml.")
                return@forEach
            }
            val length = file.length()
            val quotaMessage = when {
                length < 0 || length > ProfileMetadata.MAX_BYTES -> "Imported revision exceeds the per-file size limit."
                admittedCount >= ProfileMetadata.MAX_IMPORTED_REVISIONS -> "Imported revision skipped: catalog count quota exceeded."
                admittedById.getOrDefault(expected.id, 0) >= ProfileMetadata.MAX_IMPORTED_REVISIONS_PER_ID -> "Imported revision skipped: per-profile revision quota exceeded."
                admittedBytes + length > ProfileMetadata.MAX_IMPORTED_BYTES -> "Imported revision skipped: aggregate byte quota exceeded."
                else -> null
            }
            if (quotaMessage != null) {
                issues += ProfileIssue(ProfileIssueSeverity.ERROR, "catalog[${file.path}]", quotaMessage)
                return@forEach
            }
            admittedCount++
            admittedBytes += length
            admittedById[expected.id] = admittedById.getOrDefault(expected.id, 0) + 1
            val raw = runCatching { readCatalogFile(file) }.getOrElse {
                issues += ProfileIssue(ProfileIssueSeverity.ERROR, "catalog[${file.path}]", "Could not read imported profile.")
                return@forEach
            }
            loadEntry(raw, ProfileOrigin.IMPORTED, file.path, loaded, issues, expectedRef = expected)
        }
        entries = loaded
        fullyHydrated = true
        if (loaded.values.none { it.origin == ProfileOrigin.BUNDLED && !it.rollbackOnly && it.compatible && it.document?.match?.fallback == true }) {
            issues += ProfileIssue(ProfileIssueSeverity.ERROR, "catalog", "Bundled generic fallback is missing or invalid; compiled emergency fallback will be used.")
        }
        catalogIssues = issues
        } catch (error: Throwable) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            val bytes = loaded.values.fold(0L) { total, entry ->
                val size = entry.sourceBytes.toLong()
                if (Long.MAX_VALUE - total < size) Long.MAX_VALUE else total + size
            }
            cost.work(units = loaded.size.toLong(), bytes = bytes).close()
        }
    }

    private fun emergencySummary(active: Boolean) = ProfileSummary(
        ref = EMERGENCY_GENERIC_REF,
        displayName = Generic.displayName,
        origin = ProfileOrigin.BUNDLED,
        schema = 0,
        minCoreVersion = null,
        matchesThisDevice = true,
        active = active,
        selected = true,
        shizukuRecommendation = ShizukuRecommendation.OPTIONAL,
        risks = setOf(ProfileRisk.ROOT_PATHS),
        contentVersion = "compiled",
        author = "ha-paneld",
        maturity = ProfileMaturity.VERIFIED,
    )

    private fun loadEntry(
        raw: String,
        origin: ProfileOrigin,
        source: String,
        loaded: MutableMap<ProfileRef, StoredProfile>,
        issues: MutableList<ProfileIssue>,
        expectedRef: ProfileRef? = null,
        rollbackOnly: Boolean = false,
    ) {
        val parsed = measuredParse(raw)
        val document = parsed.document
        val validation = document?.let { measuredValidate(it, bundled = origin == ProfileOrigin.BUNDLED) }.orEmpty()
        val allIssues = parsed.issues + validation
        val computedRef = document?.let { ProfileRef(it.id, parsed.contentSha256) }
        if (origin == ProfileOrigin.BUNDLED && (document == null || allIssues.any { it.severity == ProfileIssueSeverity.ERROR })) {
            issues += allIssues.map { it.copy(path = "catalog[$source].${it.path}") }
            return
        }
        val ref = expectedRef ?: computedRef ?: return
        if (expectedRef != null && parsed.contentSha256 != expectedRef.revision) {
            issues += ProfileIssue(ProfileIssueSeverity.ERROR, "catalog[$source]", "Imported filename does not match the content SHA-256; revision ignored.")
            return
        }
        val identityIssue = if (expectedRef != null && document != null && document.id != expectedRef.id) {
            ProfileIssue(ProfileIssueSeverity.ERROR, "id", "Document id '${document.id}' does not match storage id '${expectedRef.id}'.")
        } else null
        val storedIssues = allIssues + listOfNotNull(identityIssue)
        if (storedIssues.any { it.severity == ProfileIssueSeverity.ERROR }) {
            issues += storedIssues.map { it.copy(path = "catalog[$source].${it.path}") }
        }
        val storedDocument = document?.takeIf { identityIssue == null }
        val previous = loaded.putIfAbsent(
            ref,
            StoredProfile(ref, origin, raw, parsed.sourceBytes, storedDocument, storedIssues, rollbackOnly),
        )
        if (previous != null) issues += ProfileIssue(ProfileIssueSeverity.WARNING, "catalog[$source]", "Duplicate immutable revision ignored.")
    }

    private fun measuredParse(raw: String): ProfileParseResult {
        val cost = FeatureCosts.registry.span(FeatureCostOperation.PROFILE_YAML_PARSE)
        return try {
            ProfileYaml.parse(raw).also { parsed ->
                cost.work(units = 1L, bytes = parsed.sourceBytes.toLong())
            }
        } catch (error: Throwable) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            cost.close()
        }
    }

    private fun measuredValidate(document: ProfileDocument, bundled: Boolean): List<ProfileIssue> {
        val cost = FeatureCosts.registry.span(FeatureCostOperation.PROFILE_VALIDATE).work(units = 1L)
        return try {
            ProfileValidator.validate(document, coreVersion, bundled)
        } catch (error: Throwable) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            cost.close()
        }
    }

    private fun writeImported(ref: ProfileRef, raw: String): Boolean = writeImmutable(importedFile(ref), raw)

    private fun readBounded(file: File): String {
        if (file.length() > ProfileMetadata.MAX_BYTES) error("profile is too large")
        val bytes = ByteArrayOutputStream(minOf(file.length().coerceAtLeast(0).toInt(), ProfileMetadata.MAX_BYTES))
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > ProfileMetadata.MAX_BYTES) error("profile is too large")
                bytes.write(buffer, 0, read)
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun readCatalogFile(file: File): String = catalogFileReader?.invoke(file) ?: readBounded(file)

    private fun importedFile(ref: ProfileRef) = File(File(importedDir, ref.id), "${ref.revision}.yaml")

    private fun rollbackFile(ref: ProfileRef) = File(File(rollbackDir, ref.id), "${ref.revision}.yaml")

    private fun writeRollbackSnapshot(ref: ProfileRef): Boolean {
        val entry = entries[ref] ?: return false
        // Imported revisions already have durable immutable storage and must retain their untrusted
        // origin on reload. Only bundled assets need a private snapshot before an app update removes them.
        if (entry.origin == ProfileOrigin.IMPORTED) return true
        return writeImmutable(rollbackFile(ref), entry.rawYaml)
    }

    private fun pruneRollbackSnapshots(activeRef: ProfileRef) {
        val state = readActivation()
        val keep = listOfNotNull(
            activeRef,
            readActiveRef(),
            (readLastKnownGood() as? ProfileSelection.Pinned)?.ref,
            (state.previous as? ProfileSelection.Pinned)?.ref,
            (state.desired as? ProfileSelection.Pinned)?.ref,
        ).toSet()
        rollbackDir.listFiles().orEmpty().filter { it.isDirectory }.forEach { directory ->
            directory.listFiles().orEmpty().filter { it.isFile && it.extension == "yaml" }.forEach { file ->
                if (expectedRef(file) !in keep) deleteImmutable(file)
            }
        }
    }

    private fun writeImmutable(target: File, raw: String): Boolean = runCatching {
        val parent = target.parentFile ?: error("revision has no parent directory")
        if (!createDirectoriesDurably(parent)) error("could not create revision directory")
        if (target.exists()) {
            if (readBounded(target) != raw) return@runCatching false
            revisionPersistence.syncDirectory(parent)
            return@runCatching true
        }
        val staging = File(parent, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            val bytes = raw.toByteArray(Charsets.UTF_8)
            require(bytes.size <= ProfileMetadata.MAX_BYTES)
            revisionPersistence.writeAndSync(staging, bytes)
            if (!revisionPersistence.atomicRename(staging, target)) error("atomic rename failed")
            revisionPersistence.syncDirectory(parent)
        } finally {
            if (staging.exists()) revisionPersistence.delete(staging)
        }
        true
    }.getOrDefault(false)

    private fun createDirectoriesDurably(directory: File): Boolean {
        if (directory.isDirectory) return true
        val missing = generateSequence(directory) { it.parentFile }
            .takeWhile { !it.exists() }
            .toList()
        missing.asReversed().forEach { candidate ->
            val parent = candidate.parentFile ?: return false
            if (!revisionPersistence.createDirectory(candidate)) return false
            revisionPersistence.syncDirectory(parent)
        }
        return directory.isDirectory
    }

    private fun deleteImmutable(file: File): Boolean = runCatching {
        val parent = file.parentFile ?: error("revision has no parent directory")
        if (!file.isFile || !revisionPersistence.delete(file)) error("revision unlink failed")
        revisionPersistence.syncDirectory(parent)
        if (parent.list().isNullOrEmpty() && revisionPersistence.delete(parent)) {
            parent.parentFile?.let(revisionPersistence::syncDirectory)
        }
        true
    }.getOrDefault(false)

    private fun expectedRef(file: File): ProfileRef? {
        val id = file.parentFile?.name ?: return null
        val revision = file.name.removeSuffix(".yaml")
        if (!Regex("^[a-z0-9](?:[a-z0-9.-]{0,126}[a-z0-9])?$").matches(id) || ".." in id) return null
        if (!Regex("^[0-9a-f]{64}$").matches(revision)) return null
        return ProfileRef(id, revision)
    }

    private fun readSelection(): ProfileSelection = decode(preferences.getString(KEY_SELECTION, AUTO)) ?: ProfileSelection.Auto

    private fun readLastKnownGood(): ProfileSelection? = decode(preferences.getString(KEY_LAST_KNOWN_GOOD, ""))

    private fun readActiveRef(): ProfileRef? =
        (decode(preferences.getString(KEY_ACTIVE_REF, "")) as? ProfileSelection.Pinned)?.ref

    private fun readActivation(): ProfileActivationState {
        val phase = runCatching { ProfileActivationPhase.valueOf(preferences.getString(KEY_PHASE, ProfileActivationPhase.ACTIVE.name)) }
            .getOrDefault(ProfileActivationPhase.ACTIVE)
        return ProfileActivationState(
            phase = phase,
            generation = preferences.getLong(KEY_GENERATION, 0L),
            previous = decode(preferences.getString(KEY_PREVIOUS, "")),
            desired = decode(preferences.getString(KEY_DESIRED, "")),
            message = preferences.getString(KEY_MESSAGE, "").ifBlank { null },
        )
    }

    private fun encode(selection: ProfileSelection): String = when (selection) {
        ProfileSelection.Auto -> AUTO
        is ProfileSelection.Pinned -> "${selection.ref.id}@${selection.ref.revision}"
    }

    private fun decode(encoded: String): ProfileSelection? {
        if (encoded == AUTO) return ProfileSelection.Auto
        val at = encoded.lastIndexOf('@')
        if (at <= 0) return null
        val id = encoded.substring(0, at)
        val revision = encoded.substring(at + 1)
        if (!Regex("^[a-z0-9][a-z0-9.-]{0,127}$").matches(id) || !Regex("^[0-9a-f]{64}$").matches(revision)) return null
        return ProfileSelection.Pinned(ProfileRef(id, revision))
    }

    private fun catalogRevision() = preferences.getLong(KEY_CATALOG_REVISION, 0L)

    private fun bumpCatalogRevision(): Boolean = preferences.put(KEY_CATALOG_REVISION to catalogRevision() + 1)

    private fun staleCatalog() = rejected("catalog_revision", "Catalog changed; reload and retry with the current revision.")

    private fun rejected(path: String, message: String) = ProfileMutation.Rejected(
        statusLocked(),
        listOf(ProfileIssue(ProfileIssueSeverity.ERROR, path, message)),
    )

    companion object {
        private const val PREFS_NAME = "ha-paneld-device-profiles"
        private const val BUNDLED_DIR = "device-profiles"
        private const val AUTO = "auto"
        private const val KEY_SELECTION = "selection"
        private const val KEY_PHASE = "activation_phase"
        private const val KEY_GENERATION = "activation_generation"
        private const val KEY_PREVIOUS = "activation_previous"
        private const val KEY_DESIRED = "activation_desired"
        private const val KEY_MESSAGE = "activation_message"
        private const val KEY_CATALOG_REVISION = "catalog_revision"
        private const val KEY_LAST_KNOWN_GOOD = "last_known_good"
        private const val KEY_ACTIVE_REF = "active_ref"
        private const val MAX_DIFFS = 256
        private val EMERGENCY_GENERIC_REF = ProfileRef("generic", ProfileYaml.sha256("compiled-emergency-generic"))
    }
}

private data class IssuedPreviewToken(val value: String, val expiresAt: Long)

private class PreviewTokenStore(
    private val clock: () -> Long,
    private val random: SecureRandom = SecureRandom(),
) {
    private data class Entry(val hash: String, val expiresAt: Long)
    private val entries = linkedMapOf<String, Entry>()

    fun issue(hash: String): IssuedPreviewToken {
        purge()
        while (entries.size >= MAX_TOKENS) entries.remove(entries.keys.first())
        val bytes = ByteArray(24).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        val expires = clock() + TTL_MS
        entries[token] = Entry(hash, expires)
        return IssuedPreviewToken(token, expires)
    }

    fun consume(token: String, hash: String): Boolean {
        purge()
        val entry = entries.remove(token) ?: return false
        return entry.hash == hash && entry.expiresAt >= clock()
    }

    private fun purge() {
        val now = clock()
        entries.entries.removeAll { it.value.expiresAt < now }
    }

    private companion object {
        const val TTL_MS = 10 * 60 * 1000L
        const val MAX_TOKENS = 32
    }
}

internal interface ProfilePreferences {
    fun getString(key: String, default: String): String
    fun getLong(key: String, default: Long): Long
    /** Null values remove keys; all changes are committed atomically. */
    fun put(vararg values: Pair<String, Any?>): Boolean
}

private class AndroidProfilePreferences(private val preferences: SharedPreferences) : ProfilePreferences {
    override fun getString(key: String, default: String): String = preferences.getString(key, default) ?: default
    override fun getLong(key: String, default: Long): Long = preferences.getLong(key, default)
    override fun put(vararg values: Pair<String, Any?>): Boolean {
        val editor = preferences.edit()
        values.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                else -> error("Unsupported preference value")
            }
        }
        return editor.commit()
    }
}
