package io.github.maxlyth.hapaneld.device.profile

import io.github.maxlyth.hapaneld.device.DeviceProfile

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
    DEFAULT_TAMING,
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
    /** False when a stored imported revision cannot be activated by this core version. */
    val compatible: Boolean = true,
    val issues: List<ProfileIssue> = emptyList(),
)

enum class ProfileDriverKind { LED, SCREEN, RADIO, RELAY, SENSOR, INPUT, UPDATE, ACCESS }

data class ProfileDriverDescriptor(
    val id: String,
    val kind: ProfileDriverKind,
    val description: String,
    val privileged: Boolean,
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
