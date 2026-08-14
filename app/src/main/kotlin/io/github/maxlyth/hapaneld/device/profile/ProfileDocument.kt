package io.github.maxlyth.hapaneld.device.profile

/**
 * Parsed form of the version-2 profile format. It contains data and named, core-owned strategies only:
 * imported profiles cannot execute code, name arbitrary classes, use regular expressions, or add drivers.
 */
data class ProfileDocument(
    val schema: Int,
    val id: String,
    val version: String,
    val displayName: String,
    val socClass: String,
    val metadata: ProfileProvenance,
    val requires: ProfileRequirements,
    val match: ProfileMatch,
    val platform: ProfilePlatform,
    val hardware: ProfileHardware,
    val sensors: ProfileSensors,
    val identity: ProfileIdentity,
    val input: ProfileInput,
    val cpu: ProfileCpu,
    val display: ProfileDisplay,
    val provisioning: ProfileProvisioning,
    val soc: ProfileSoc? = null,
)

data class ProfileProvenance(
    val author: String,
    val source: String? = null,
    val license: String,
    val maturity: ProfileMaturity,
    val testedFirmware: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
    val links: List<ProfileLink> = emptyList(),
)

/** Offline, author-evidenced SoC facts. These values are descriptive and never runtime-probed. */
data class ProfileSoc(
    val model: String,
    val introducedYear: Int? = null,
    val cpuCores: List<ProfileCpuCoreCluster> = emptyList(),
) {
    fun displayText(): String = buildList {
        add(model)
        cpuCores.takeIf { it.isNotEmpty() }?.let { clusters ->
            add(clusters.joinToString(" + ") { "${it.count}× ${it.architecture}" })
        }
        introducedYear?.let { add("introduced $it") }
    }.joinToString(" · ")
}

data class ProfileCpuCoreCluster(
    val architecture: String,
    val count: Int,
)

/** Display-only reference. Profile links are never fetched by the service or used for provisioning. */
data class ProfileLink(
    val label: String,
    val url: String,
)

data class ProfileRequirements(
    val minCoreVersion: String? = null,
    val drivers: Set<String> = emptySet(),
)

data class ProfileMatch(
    val priority: Int = 0,
    val fallback: Boolean = false,
    /** OR across groups, AND across predicates within one group. */
    val any: List<ProfileMatchGroup> = emptyList(),
)

data class ProfileMatchGroup(
    /** Specificity of this OR branch; exact product identities must outrank broad SoC fallbacks. */
    val priority: Int,
    val all: List<ProfilePredicate>,
)

enum class ProfileFact(val yamlName: String) {
    MODEL("model"),
    DEVICE("device"),
    PRODUCT_VERSION("product_version"),
}

enum class ProfileMatchOp(val yamlName: String) {
    EQUALS("equals"),
    STARTS_WITH("starts_with"),
    CONTAINS("contains"),
}

data class ProfilePredicate(
    val field: ProfileFact,
    val op: ProfileMatchOp,
    val values: List<String>,
)

data class ProfilePlatform(
    val suForm: String,
    val appCanSu: Boolean,
    val hasRecents: Boolean = true,
    val hasNativeNavbar: Boolean = false,
)

data class ProfileHardware(
    val led: ProfileLed,
    val screenOff: String,
    val hasButtonBacklight: Boolean = false,
    val zigbeeGatewayDir: String? = null,
    val relayBase: String? = null,
    val relayBaseFallbacks: List<String> = emptyList(),
    val buttonLedGpioBase: Int? = null,
    val touchClickGain: Float? = null,
)

data class ProfileLed(
    val mechanism: String,
    val transfer: String = "identity",
)

data class ProfileSensors(
    val proximityTechnology: String? = null,
    val proximityGpio: Int? = null,
    val lightTechnology: String? = null,
    val cht8305: Boolean = false,
    /** Board carries an Evisionics VI530x time-of-flight sensor the helper can start and read. It is a
     *  distinct fact from [proximityTechnology], which only names the technology for display: this one
     *  says the daemon route exists, because the driver reports nothing until it is started. */
    val vi530x: Boolean = false,
    val roomTempOffsetC: Float = 0f,
)

data class ProfileIdentity(
    val manufacturer: String? = null,
    val model: String? = null,
    val modelLabelStrategy: String = "display-name",
)

data class ProfileInput(val evdevButtons: List<ProfileEvdevButton> = emptyList())

data class ProfileEvdevButton(
    val node: String,
    val code: Int,
    val grab: Boolean,
    val eventType: String,
    val sw: Boolean = false,
)

data class ProfileCpu(val governors: Map<String, String>? = null)

data class ProfileDisplay(
    val physicalPpi: Int? = null,
)

sealed interface ProfileDensity {
    data class Fixed(val value: Int) : ProfileDensity
    data class Strategy(val id: String) : ProfileDensity
}

data class ProfileProvisioning(
    val access: ProfileProvisioningAccess = ProfileProvisioningAccess(),
    val software: ProfileProvisioningSoftware = ProfileProvisioningSoftware(),
    val display: ProfileProvisioningDisplay = ProfileProvisioningDisplay(),
    val packages: List<ProfilePackageIntent> = emptyList(),
    val recipes: List<ProfileRecipeSelection> = emptyList(),
)

data class ProfileProvisioningAccess(
    val shizuku: ShizukuRecommendation = ShizukuRecommendation.NONE,
)

data class ProfileProvisioningSoftware(
    val webView: ProfileWebViewProvisioning? = null,
    val companion: ProfileCompanionProvisioning? = null,
)

data class ProfileWebViewProvisioning(
    /** Core-owned artifact id; profile files never define APK URLs or signer trust roots. */
    val artifact: String,
)

data class ProfileCompanionProvisioning(
    val maxVersion: String,
)

data class ProfileProvisioningDisplay(
    val density: ProfileDensity? = null,
    val fontScale: Float? = null,
)

data class ProfilePackageIntent(
    val packageName: String,
    val desiredState: ProfilePackageDesiredState,
    val importance: ProfileProvisioningImportance,
    val tags: List<String> = emptyList(),
    val note: String = "",
)

enum class ProfilePackageDesiredState(val yamlName: String) {
    DISABLED("disabled"),
}

enum class ProfileProvisioningImportance(val yamlName: String) {
    RECOMMENDED("recommended"),
    OPTIONAL("optional"),
}

data class ProfileRecipeSelection(val id: String)
