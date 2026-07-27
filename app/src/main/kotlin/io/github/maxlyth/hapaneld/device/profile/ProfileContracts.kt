package io.github.maxlyth.hapaneld.device.profile

import io.github.maxlyth.hapaneld.device.DeviceProfile
import org.json.JSONArray
import org.json.JSONObject

/** Immutable identity of one profile revision. [revision] is the lowercase SHA-256 of its YAML. */
data class ProfileRef(val id: String, val revision: String)

/** Where a profile revision came from. Bundled revisions ship in the APK; imported revisions are local. */
enum class ProfileOrigin { BUNDLED, IMPORTED }

/** Author guidance only. Runtime Shizuku installation/readiness remains separate live state. */
enum class ShizukuRecommendation { NONE, OPTIONAL, RECOMMENDED }

enum class ProfileMaturity { DRAFT, EXPERIMENTAL, VERIFIED }

/** Automatic fingerprint matching, or an explicit immutable revision selected by the administrator. */
sealed interface ProfileSelection {
    data object Auto : ProfileSelection
    data class Pinned(val ref: ProfileRef) : ProfileSelection
}

/** Activation is restart-bound so every controller sees one coherent profile for its whole lifetime. */
enum class ProfileActivationPhase { ACTIVE, PENDING, APPLYING, ROLLED_BACK }

data class ProfileActivationState(
    val phase: ProfileActivationPhase,
    val generation: Long = 0,
    val previous: ProfileSelection? = null,
    val desired: ProfileSelection? = null,
    val message: String? = null,
)

enum class ProfileIssueSeverity { INFO, WARNING, ERROR }

data class ProfileIssue(
    val severity: ProfileIssueSeverity,
    /** Stable schema-style path, for example `hardware.relay_base`. */
    val path: String,
    val message: String,
)

/** Privileged or unusually consequential behavior highlighted before a local profile is imported. */
enum class ProfileRisk {
    ROOT_PATHS,
    RELAY_OR_GPIO_WRITES,
    EVDEV_READ,
    EVDEV_GRAB,
    PACKAGE_DISABLE_RECOMMENDATIONS,
    WEBVIEW_INSTALL,
    OVERRIDES_BUNDLED,
}

data class ProfileSummary(
    val ref: ProfileRef,
    val displayName: String,
    val origin: ProfileOrigin,
    val schema: Int,
    val minCoreVersion: String?,
    val matchesThisDevice: Boolean,
    val active: Boolean,
    val selected: Boolean,
    val shizukuRecommendation: ShizukuRecommendation,
    val risks: Set<ProfileRisk> = emptySet(),
    val contentVersion: String = "",
    val author: String? = null,
    val maturity: ProfileMaturity = ProfileMaturity.DRAFT,
    /** True only for content shipped by ha-paneld. Author-declared maturity is not a trust signal. */
    val trustedProvenance: Boolean = false,
    /** False when a stored imported revision cannot be activated by this core version. */
    val compatible: Boolean = true,
    val issues: List<ProfileIssue> = emptyList(),
    val soc: ProfileSoc? = null,
    val links: List<ProfileLink> = emptyList(),
)

enum class ProfileDriverKind { LED, SCREEN, RADIO, RELAY, SENSOR, INPUT, UPDATE, ACCESS }

data class ProfileDriverDescriptor(
    val id: String,
    val kind: ProfileDriverKind,
    val description: String,
    val privileged: Boolean,
    /**
     * Root-helper authority this driver demands. This is the single source for the helper-need
     * decision; [ProfileMetadata.helperAuthorityDemand] is derived from it, so the demand table can
     * never drift from — or fail to cover — the canonical driver table. Deliberately separate from
     * [privileged]: a privileged driver may satisfy its access through app `su` or a trusted-host
     * operation rather than the root helper (see [ProfileHelperAuthorityDemand]).
     */
    val helperDemand: ProfileHelperAuthorityDemand,
)

data class ProfileFieldDescriptor(
    val path: String,
    val type: String,
    val required: Boolean = false,
    val enumValues: List<String> = emptyList(),
    val description: String,
)

/** Stable, typed metadata used by `/profiles/schema`, authoring tools, and generated documentation. */
data class ProfileSchemaDescriptor(
    val schema: Int,
    val maxBytes: Int,
    val fields: List<ProfileFieldDescriptor>,
)

data class ProfileDiff(
    val path: String,
    val before: String?,
    val after: String?,
)

/** Result of parsing an import candidate. [previewToken] is short-lived and bound to the exact raw hash. */
data class ProfilePreview(
    val previewToken: String?,
    val contentSha256: String,
    val expiresAtEpochMs: Long?,
    val summary: ProfileSummary?,
    val issues: List<ProfileIssue>,
    val diffFromActive: List<ProfileDiff>,
    val compatible: Boolean,
)

data class ProfileStatus(
    val catalogRevision: Long,
    val selection: ProfileSelection,
    val active: ProfileSummary?,
    val activation: ProfileActivationState,
    val issues: List<ProfileIssue> = emptyList(),
    /** Previous proven selection available as an explicit one-step rollback target. */
    val lastKnownGood: ProfileSelection? = null,
)

/** One immutable imported revision carried by a full panel backup. */
data class ProfileBackupRevision(
    val ref: ProfileRef,
    val rawYaml: String,
)

/**
 * Bounded profile-catalog component of a full `.hpb` backup. Activation's transient
 * PENDING/APPLYING state is deliberately absent: restore stages [selection] through the normal
 * health-gated activation path and treats the destination's current selection as its rollback target.
 */
data class ProfileBackup(
    val schema: Int = SCHEMA,
    val revisions: List<ProfileBackupRevision>,
    val selection: ProfileSelection,
    /** Exact revision observed active when the backup was made; restore uses it for coherency checks only. */
    val active: ProfileRef?,
    /** Source-side rollback identity. The destination's current selection remains the first rollback target. */
    val lastKnownGood: ProfileSelection?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", schema)
        put("revisions", JSONArray().apply {
            revisions.sortedWith(compareBy<ProfileBackupRevision>({ it.ref.id }, { it.ref.revision }))
                .forEach { revision ->
                    put(JSONObject().apply {
                        put("id", revision.ref.id)
                        put("revision", revision.ref.revision)
                        put("yaml", revision.rawYaml)
                    })
                }
        })
        put("selection", selectionJson(selection))
        put("active", active?.let(::refJson) ?: JSONObject.NULL)
        put("last_known_good", lastKnownGood?.let(::selectionJson) ?: JSONObject.NULL)
    }

    companion object {
        const val SCHEMA = 1

        /** Decode and enforce the transport-level bounds before YAML parsing or filesystem mutation. */
        fun fromJson(value: JSONObject): ProfileBackupDecodeResult {
            val issues = mutableListOf<ProfileIssue>()
            val rawSchema = value.opt("schema")
            val schema = (rawSchema as? Number)?.toInt() ?: -1
            if (rawSchema !is Number || rawSchema.toLong() != schema.toLong() || schema != SCHEMA) {
                issues += backupIssue("profiles.schema", "Unsupported profile backup schema.")
            }
            val revisionsArray = value.optJSONArray("revisions")
            if (revisionsArray == null) {
                issues += backupIssue("profiles.revisions", "Profile backup revisions must be an array.")
            } else if (revisionsArray.length() > ProfileMetadata.MAX_IMPORTED_REVISIONS) {
                issues += backupIssue(
                    "profiles.revisions",
                    "Profile backup exceeds the ${ProfileMetadata.MAX_IMPORTED_REVISIONS}-revision limit.",
                )
            }
            val revisions = mutableListOf<ProfileBackupRevision>()
            val seen = mutableSetOf<ProfileRef>()
            val byId = mutableMapOf<String, Int>()
            var totalBytes = 0L
            if (revisionsArray != null) {
                for (index in 0 until minOf(revisionsArray.length(), ProfileMetadata.MAX_IMPORTED_REVISIONS + 1)) {
                    val item = revisionsArray.optJSONObject(index)
                    if (item == null) {
                        issues += backupIssue("profiles.revisions[$index]", "Revision must be an object.")
                        continue
                    }
                    val ref = decodeRef(item, "profiles.revisions[$index]", issues)
                    val raw = item.opt("yaml") as? String
                    if (raw == null) {
                        issues += backupIssue("profiles.revisions[$index].yaml", "Revision YAML is missing.")
                        continue
                    }
                    val bytes = raw.toByteArray(Charsets.UTF_8).size.toLong()
                    if (bytes > ProfileMetadata.MAX_BYTES) {
                        issues += backupIssue("profiles.revisions[$index].yaml", "Revision exceeds the per-file size limit.")
                    }
                    totalBytes = if (Long.MAX_VALUE - totalBytes < bytes) Long.MAX_VALUE else totalBytes + bytes
                    if (ref != null) {
                        if (!seen.add(ref)) {
                            issues += backupIssue("profiles.revisions[$index]", "Duplicate immutable revision.")
                        }
                        val count = byId.getOrDefault(ref.id, 0) + 1
                        byId[ref.id] = count
                        if (count > ProfileMetadata.MAX_IMPORTED_REVISIONS_PER_ID) {
                            issues += backupIssue(
                                "profiles.revisions[$index]",
                                "Profile '${ref.id}' exceeds the per-profile revision limit.",
                            )
                        }
                        revisions += ProfileBackupRevision(ref, raw)
                    }
                }
            }
            if (totalBytes > ProfileMetadata.MAX_IMPORTED_BYTES) {
                issues += backupIssue("profiles.revisions", "Profile backup exceeds the aggregate byte limit.")
            }
            val selection = decodeSelection(value.optJSONObject("selection"), "profiles.selection", issues)
            val activeObj = value.optJSONObject("active")
            val active = when {
                !value.has("active") || value.isNull("active") -> null
                activeObj == null -> {
                    issues += backupIssue("profiles.active", "Active revision must be an object or null.")
                    null
                }
                else -> decodeRef(activeObj, "profiles.active", issues)
            }
            val lastKnownGood = when {
                !value.has("last_known_good") || value.isNull("last_known_good") -> null
                else -> decodeSelection(value.optJSONObject("last_known_good"), "profiles.last_known_good", issues)
            }
            val payload = if (issues.any { it.severity == ProfileIssueSeverity.ERROR } || selection == null) null else {
                ProfileBackup(schema, revisions, selection, active, lastKnownGood)
            }
            return ProfileBackupDecodeResult(payload, issues)
        }

        private fun selectionJson(selection: ProfileSelection): JSONObject = JSONObject().apply {
            when (selection) {
                ProfileSelection.Auto -> put("mode", "auto")
                is ProfileSelection.Pinned -> {
                    put("mode", "pinned")
                    put("id", selection.ref.id)
                    put("revision", selection.ref.revision)
                }
            }
        }

        private fun refJson(ref: ProfileRef): JSONObject = JSONObject().apply {
            put("id", ref.id)
            put("revision", ref.revision)
        }

        private fun decodeSelection(
            value: JSONObject?,
            path: String,
            issues: MutableList<ProfileIssue>,
        ): ProfileSelection? {
            if (value == null) {
                issues += backupIssue(path, "Selection must be an object.")
                return null
            }
            return when (value.opt("mode") as? String) {
                "auto" -> ProfileSelection.Auto
                "pinned" -> decodeRef(value, path, issues)?.let(ProfileSelection::Pinned)
                else -> {
                    issues += backupIssue("$path.mode", "Selection mode must be 'auto' or 'pinned'.")
                    null
                }
            }
        }

        private fun decodeRef(
            value: JSONObject,
            path: String,
            issues: MutableList<ProfileIssue>,
        ): ProfileRef? {
            val id = value.opt("id") as? String ?: ""
            val revision = value.opt("revision") as? String ?: ""
            if (!PROFILE_ID.matches(id) || ".." in id) {
                issues += backupIssue("$path.id", "Profile id is invalid.")
            }
            if (!REVISION.matches(revision)) {
                issues += backupIssue("$path.revision", "Revision must be a lowercase SHA-256.")
            }
            return if (PROFILE_ID.matches(id) && ".." !in id && REVISION.matches(revision)) {
                ProfileRef(id, revision)
            } else null
        }

        private val PROFILE_ID = Regex("^[a-z0-9](?:[a-z0-9.-]{0,126}[a-z0-9])?$")
        private val REVISION = Regex("^[0-9a-f]{64}$")
    }
}

data class ProfileBackupDecodeResult(
    val payload: ProfileBackup?,
    val issues: List<ProfileIssue>,
)

data class ProfileBackupRestorePlan(
    val status: ProfileStatus,
    val expectedCatalogRevision: Long,
    val valid: Boolean,
    val toImport: List<ProfileRef>,
    val alreadyPresent: List<ProfileRef>,
    val issues: List<ProfileIssue>,
    val restartRequired: Boolean,
)

enum class ProfileBackupRestoreOutcome { SUCCEEDED, PARTIAL, REJECTED }

/** Structured profile-component outcome for restore progress and API responses. */
data class ProfileBackupRestoreResult(
    val outcome: ProfileBackupRestoreOutcome,
    val status: ProfileStatus,
    val imported: List<ProfileRef>,
    val alreadyPresent: List<ProfileRef>,
    val issues: List<ProfileIssue>,
    val selectionStaged: Boolean,
    val restartRequired: Boolean,
    val message: String,
)

private fun backupIssue(path: String, message: String) =
    ProfileIssue(ProfileIssueSeverity.ERROR, path, message)

sealed interface ProfileMutation {
    data class Success(
        val status: ProfileStatus,
        val restartRequired: Boolean,
        val message: String,
    ) : ProfileMutation

    data class Rejected(
        val status: ProfileStatus,
        val issues: List<ProfileIssue>,
    ) : ProfileMutation
}

/**
 * Narrow administrative surface consumed by HTTP/UI code. It exposes no filesystem paths, parser
 * implementation, or mutable profile object. Selecting a revision only stages it; the caller responds
 * first and then requests a controlled process restart when [ProfileMutation.Success.restartRequired].
 */
interface ProfileAdmin {
    fun schema(): ProfileSchemaDescriptor
    fun drivers(): List<ProfileDriverDescriptor>
    fun status(): ProfileStatus
    fun list(): List<ProfileSummary>
    fun preview(rawYaml: String): ProfilePreview
    fun importProfile(rawYaml: String, previewToken: String): ProfileMutation
    fun exportProfile(ref: ProfileRef): String?
    fun select(selection: ProfileSelection, expectedCatalogRevision: Long): ProfileMutation
    fun rollbackToLastKnownGood(expectedCatalogRevision: Long): ProfileMutation
    fun deleteProfile(ref: ProfileRef, expectedCatalogRevision: Long): ProfileMutation

    /** Export the bounded profile component for a full panel backup. Fleet config bundles do not use it. */
    fun exportBackup(): ProfileBackup {
        val current = status()
        val revisions = list().asSequence()
            .filter { it.origin == ProfileOrigin.IMPORTED }
            .mapNotNull { summary -> exportProfile(summary.ref)?.let { ProfileBackupRevision(summary.ref, it) } }
            .sortedWith(compareBy<ProfileBackupRevision>({ it.ref.id }, { it.ref.revision }))
            .toList()
        return ProfileBackup(
            revisions = revisions,
            selection = current.selection,
            active = current.active?.ref,
            lastKnownGood = current.lastKnownGood,
        )
    }

    fun planBackupRestore(payload: ProfileBackup): ProfileBackupRestorePlan = ProfileBackupRestorePlan(
        status = status(),
        expectedCatalogRevision = status().catalogRevision,
        valid = false,
        toImport = emptyList(),
        alreadyPresent = emptyList(),
        issues = listOf(backupIssue("profiles", "Profile catalog restore is unavailable.")),
        restartRequired = false,
    )

    fun restoreBackup(payload: ProfileBackup, expectedCatalogRevision: Long): ProfileBackupRestoreResult =
        ProfileBackupRestoreResult(
            outcome = ProfileBackupRestoreOutcome.REJECTED,
            status = status(),
            imported = emptyList(),
            alreadyPresent = emptyList(),
            issues = listOf(backupIssue("profiles", "Profile catalog restore is unavailable.")),
            selectionStaged = false,
            restartRequired = false,
            message = "Profile catalog restore is unavailable.",
        )
}

/** Build values available without probing hardware. Matching is deliberately limited to these facts. */
data class DeviceFacts(
    val model: String,
    val device: String,
    val productVersion: String,
) {
    fun normalized(): DeviceFacts = DeviceFacts(
        model = model.lowercase(),
        device = device.lowercase(),
        productVersion = productVersion.lowercase(),
    )
}

/** Profile chosen for one service lifetime, plus an activation generation to acknowledge after startup. */
data class ResolvedProfile(
    val profile: DeviceProfile,
    val summary: ProfileSummary,
    val activationGeneration: Long? = null,
    val issues: List<ProfileIssue> = emptyList(),
)

interface ProfileResolver {
    fun resolveForStartup(): ResolvedProfile
    fun markActivationHealthy(generation: Long): Boolean
}

/** Passive evidence only: producing this report must not write hardware, change settings, or run su. */
data class PassiveProfileReport(
    val generatedAtEpochMs: Long,
    val facts: DeviceFacts,
    val observations: List<PassiveProfileObservation>,
    val issues: List<ProfileIssue> = emptyList(),
)

enum class PassiveProfileConfidence { OBSERVED, INFERRED }

data class PassiveProfileObservation(
    /** Candidate schema path, for example `sensors.light_technology`. */
    val path: String,
    val value: String?,
    val source: String,
    val confidence: PassiveProfileConfidence,
    val note: String? = null,
)

data class PassiveProfileDraft(
    val rawYaml: String,
    val report: PassiveProfileReport,
    val issues: List<ProfileIssue>,
)
