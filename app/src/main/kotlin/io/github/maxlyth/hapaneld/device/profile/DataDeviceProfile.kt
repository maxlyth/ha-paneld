package io.github.maxlyth.hapaneld.device.profile

import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.device.EvdevButton
import io.github.maxlyth.hapaneld.device.LedMechanism
import io.github.maxlyth.hapaneld.device.PackageDesiredState
import io.github.maxlyth.hapaneld.device.PackageIntent
import io.github.maxlyth.hapaneld.device.ProvisioningImportance
import io.github.maxlyth.hapaneld.device.ProvisioningIntent
import io.github.maxlyth.hapaneld.device.ScreenOff
import io.github.maxlyth.hapaneld.device.SuForm
import io.github.maxlyth.hapaneld.hardware.LedTransfer
import java.security.MessageDigest

/** DeviceProfile adapter for a validated declarative document. */
class DataDeviceProfile internal constructor(
    val document: ProfileDocument,
    private val productVersion: String,
    override val revision: String = canonicalRevision(document),
) : DeviceProfile {
    override val id = document.id
    override val displayName = document.displayName
    override val socClass = document.socClass
    override val suForm = when (document.platform.suForm) {
        "toolbox" -> SuForm.TOOLBOX
        "android" -> SuForm.ANDROID
        else -> SuForm.NONE
    }
    override val appCanSu = document.platform.appCanSu
    override val hasRecents = document.platform.hasRecents
    override val ledMechanism = when (document.hardware.led.mechanism) {
        "rk3576-ioctl" -> LedMechanism.RK3576_IOCTL
        "rk3576-ioctl-daemon" -> LedMechanism.RK3576_IOCTL_DAEMON
        "sysfs-daemon" -> LedMechanism.SYSFS_DAEMON
        "autodetect" -> LedMechanism.AUTODETECT
        else -> LedMechanism.NONE
    }
    override val ledTransfer: LedTransfer = when (document.hardware.led.transfer) {
        "rk3576-four-bit" -> LedTransfer.Rk3576FourBit
        else -> LedTransfer.Identity
    }
    override val screenOff = when (document.hardware.screenOff) {
        "su-blpower" -> ScreenOff.SU_BLPOWER
        "daemon-blpower" -> ScreenOff.DAEMON_BLPOWER
        else -> ScreenOff.BRIGHTNESS_ZERO
    }
    override val hasButtonBacklight = document.hardware.hasButtonBacklight
    override val zigbeeGatewayDir = document.hardware.zigbeeGatewayDir
    override val relayBase = document.hardware.relayBase
    override val relayBaseFallbacks = document.hardware.relayBaseFallbacks
    override val buttonLedGpioBase = document.hardware.buttonLedGpioBase
    override val proximityTech = document.sensors.proximityTechnology
    override val proximityNearBelow = document.sensors.proximityNearBelow
    override val proximityNearRaw = document.sensors.proximityNearRaw
    override val proximityFarRaw = document.sensors.proximityFarRaw
    override val proximityGpio = document.sensors.proximityGpio
    override val lightTech = document.sensors.lightTechnology
    override val hasCht8305 = document.sensors.cht8305
    override val roomTempOffsetC = document.sensors.roomTempOffsetC
    override val manufacturer = document.identity.manufacturer
    override val model = document.identity.model
    override val evdevButtons = document.input.evdevButtons.map {
        EvdevButton(it.node, it.code, it.grab, it.eventType, it.sw)
    }
    override val cpuGovernors = document.cpu.governors
    override val physicalPpi = document.display.physicalPpi
    override val provisioning = ProvisioningIntent(
        shizuku = document.provisioning.access.shizuku,
        webViewArtifactId = document.provisioning.software.webView?.artifact,
        companionMaxVersion = document.provisioning.software.companion?.maxVersion,
        density = when (val density = document.provisioning.display.density) {
            is ProfileDensity.Fixed -> density.value
            is ProfileDensity.Strategy -> when (density.id) {
                "nspanel-variant" -> if ("120" in productVersion.substringBefore('_')) 250 else 160
                else -> null
            }
            null -> null
        },
        fontScale = document.provisioning.display.fontScale,
        packages = document.provisioning.packages.map {
            PackageIntent(
                packageName = it.packageName,
                desiredState = when (it.desiredState) {
                    ProfilePackageDesiredState.DISABLED -> PackageDesiredState.DISABLED
                },
                importance = when (it.importance) {
                    ProfileProvisioningImportance.RECOMMENDED -> ProvisioningImportance.RECOMMENDED
                    ProfileProvisioningImportance.OPTIONAL -> ProvisioningImportance.OPTIONAL
                },
                tags = it.tags,
                note = it.note,
            )
        },
        recipeIds = document.provisioning.recipes.mapTo(linkedSetOf()) { it.id },
    )

    override fun proximityGradedForFirmware(productVersion: String): Boolean? =
        if (document.sensors.proximityGradedStrategy == "nspanel-firmware-cutover") {
            val fw = nspanelFirmwareVersion(productVersion) ?: return null
            val threshold = if ("120" in productVersion.substringBefore('_')) "3.5.0" else "3.0.0"
            versionLessThan(fw, threshold)
        } else {
            null
        }

    override fun panelModelLabel(productVersion: String): String =
        if (document.identity.modelLabelStrategy == "nspanel-product-version") {
            when {
                productVersion.startsWith(S6_VERSION_PREFIX, ignoreCase = true) ->
                    "NSPanel 86P" + nspanelFirmwareVersion(productVersion)?.let { " · fw $it" }.orEmpty()
                productVersion.startsWith("NSPanel", ignoreCase = true) -> {
                    val suffix = productVersion.substringBefore('_').drop("NSPanel".length)
                    if (suffix.isBlank()) displayName
                    else "NSPanel $suffix" + nspanelFirmwareVersion(productVersion)?.let { " · fw $it" }.orEmpty()
                }
                else -> displayName
            }
        } else {
            displayName
        }

    private fun nspanelFirmwareVersion(value: String): String? = when {
        value.startsWith(S6_VERSION_PREFIX, ignoreCase = true) -> value.drop(S6_VERSION_PREFIX.length)
        value.startsWith("NSPanel", ignoreCase = true) -> value.substringAfter('_', "")
        else -> ""
    }.ifBlank { null }

    private fun versionLessThan(a: String, b: String): Boolean {
        val left = a.split('.')
        val right = b.split('.')
        for (index in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrNull(index)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            val y = right.getOrNull(index)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            if (x != y) return x < y
        }
        return false
    }

    private companion object {
        const val S6_VERSION_PREFIX = "s6_android_"

        fun canonicalRevision(document: ProfileDocument): String = MessageDigest.getInstance("SHA-256")
            .digest(ProfileYaml.serialize(document).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

internal fun ProfileDocument.matchedGroupPriority(rawFacts: DeviceFacts): Int? {
    if (match.fallback) return null
    val facts = rawFacts.normalized()
    return match.any.filter { group ->
        group.all.all { predicate ->
            val actual = when (predicate.field) {
                ProfileFact.MODEL -> facts.model
                ProfileFact.DEVICE -> facts.device
                ProfileFact.PRODUCT_VERSION -> facts.productVersion
            }
            predicate.values.any { expected ->
                when (predicate.op) {
                    ProfileMatchOp.EQUALS -> actual == expected
                    ProfileMatchOp.STARTS_WITH -> actual.startsWith(expected)
                    ProfileMatchOp.CONTAINS -> expected in actual
                }
            }
        }
    }.maxOfOrNull { it.priority }
}

internal fun ProfileDocument.matches(rawFacts: DeviceFacts): Boolean = matchedGroupPriority(rawFacts) != null
