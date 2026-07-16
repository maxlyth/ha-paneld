package io.github.maxlyth.hapaneld.device.profile

import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.device.EchoShow5Gen2
import io.github.maxlyth.hapaneld.device.Generic
import io.github.maxlyth.hapaneld.device.NSPanelPro
import io.github.maxlyth.hapaneld.device.S9e
import io.github.maxlyth.hapaneld.device.ShellyWallDisplay
import io.github.maxlyth.hapaneld.device.ShellyWallDisplayV2
import io.github.maxlyth.hapaneld.device.Smt1019
import io.github.maxlyth.hapaneld.device.Tpa10
import io.github.maxlyth.hapaneld.device.Wf1589t
import io.github.maxlyth.hapaneld.device.ZxSmt156
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Migration lock for the initial bundled YAML catalog. The legacy Kotlin objects remain the behavioral
 * oracle until callers have fully moved to [RuntimeProfileRegistry]; this prevents a transcription from
 * silently changing a path, strategy, tame suggestion, transfer curve, or order-sensitive fingerprint.
 */
class BundledProfileParityTest {
    private val assetsDir: File = listOf(
        File("src/main/assets/device-profiles"),
        File("app/src/main/assets/device-profiles"),
        File("../app/src/main/assets/device-profiles"),
    ).firstOrNull(File::isDirectory) ?: error("Bundled profile assets directory not found")

    private val rawByName: Map<String, String> by lazy {
        assetsDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension in setOf("yaml", "yml") }
            .sortedBy { it.name }
            .associate { it.name to it.readText() }
    }

    private val documents: Map<String, ProfileDocument> by lazy {
        rawByName.mapValues { (name, raw) ->
            val parsed = ProfileYaml.parse(raw)
            assertNotNull("$name did not parse: ${parsed.issues}", parsed.document)
            val issues = parsed.issues + ProfileValidator.validate(parsed.document!!, CORE_VERSION, bundled = true)
            assertTrue("$name has schema/driver issues: $issues", issues.isEmpty())
            parsed.document!!
        }.values.associateBy(ProfileDocument::id)
    }

    private val legacyById: Map<String, DeviceProfile> = listOf(
        NSPanelPro,
        Tpa10,
        EchoShow5Gen2,
        ZxSmt156,
        ShellyWallDisplayV2,
        ShellyWallDisplay,
        Smt1019,
        Wf1589t,
        S9e,
        Generic,
    ).associateBy(DeviceProfile::id)

    @Test fun catalogHasExactlyOneValidAssetForEveryLegacyProfile() {
        assertEquals(10, rawByName.size)
        assertEquals(legacyById.keys, documents.keys)
        assertEquals(documents.keys, rawByName.keys.map { it.substringBeforeLast('.') }.toSet())
        assertEquals(setOf("generic"), documents.values.filter { it.match.fallback }.map { it.id }.toSet())
    }

    @Test fun publicAuthoringExamplesValidateAsImportedProfiles() {
        val examplesDir = listOf(
            File("docs/profiles/examples"),
            File("../docs/profiles/examples"),
        ).firstOrNull(File::isDirectory) ?: error("Public profile examples directory not found")
        val examples = examplesDir.listFiles().orEmpty().filter { it.isFile && it.extension in setOf("yaml", "yml") }
        assertTrue("no public YAML examples found", examples.isNotEmpty())
        examples.forEach { example ->
            val parsed = ProfileYaml.parse(example.readText())
            assertNotNull("${example.name} did not parse: ${parsed.issues}", parsed.document)
            val issues = parsed.issues + ProfileValidator.validate(parsed.document!!, CORE_VERSION, bundled = false)
            assertTrue("${example.name} has schema/driver issues: $issues", issues.isEmpty())
        }
    }

    @Test fun dataBackedProfilesPreserveEveryLegacyCapabilityField() {
        legacyById.forEach { (id, legacy) ->
            val actual = DataDeviceProfile(documents.getValue(id), productVersion = "")
            assertEquals("$id id", legacy.id, actual.id)
            assertEquals("$id displayName", legacy.displayName, actual.displayName)
            assertEquals("$id socClass", legacy.socClass, actual.socClass)
            assertEquals("$id suForm", legacy.suForm, actual.suForm)
            assertEquals("$id appCanSu", legacy.appCanSu, actual.appCanSu)
            assertEquals("$id usesDaemon", legacy.usesDaemon, actual.usesDaemon)
            assertEquals("$id hasRecents", legacy.hasRecents, actual.hasRecents)
            assertEquals("$id ledMechanism", legacy.ledMechanism, actual.ledMechanism)
            assertEquals("$id screenOff", legacy.screenOff, actual.screenOff)
            assertEquals("$id hasButtonBacklight", legacy.hasButtonBacklight, actual.hasButtonBacklight)
            assertEquals("$id zigbeeGatewayDir", legacy.zigbeeGatewayDir, actual.zigbeeGatewayDir)
            assertEquals("$id relayBase", legacy.relayBase, actual.relayBase)
            assertEquals("$id relayBaseFallbacks", legacy.relayBaseFallbacks, actual.relayBaseFallbacks)
            assertEquals("$id buttonLedGpioBase", legacy.buttonLedGpioBase, actual.buttonLedGpioBase)
            assertEquals("$id proximityTech", legacy.proximityTech, actual.proximityTech)
            assertEquals("$id proximityNearBelow", legacy.proximityNearBelow, actual.proximityNearBelow)
            assertEquals("$id proximityNearRaw", legacy.proximityNearRaw, actual.proximityNearRaw)
            assertEquals("$id proximityFarRaw", legacy.proximityFarRaw, actual.proximityFarRaw)
            assertEquals("$id proximityGpio", legacy.proximityGpio, actual.proximityGpio)
            assertEquals("$id lightTech", legacy.lightTech, actual.lightTech)
            assertEquals("$id hasCht8305", legacy.hasCht8305, actual.hasCht8305)
            assertEquals("$id roomTempOffsetC", legacy.roomTempOffsetC, actual.roomTempOffsetC)
            assertEquals("$id manufacturer", legacy.manufacturer, actual.manufacturer)
            assertEquals("$id model", legacy.model, actual.model)
            assertEquals("$id evdevButtons", legacy.evdevButtons, actual.evdevButtons)
            assertEquals("$id cpuGovernors", legacy.cpuGovernors, actual.cpuGovernors)
            assertEquals("$id recommendedDensity", legacy.recommendedDensity, actual.recommendedDensity)
            assertEquals("$id recommendedFontScale", legacy.recommendedFontScale, actual.recommendedFontScale)
            assertEquals("$id physicalPpi", legacy.physicalPpi, actual.physicalPpi)
            assertEquals("$id recommendedWebView", legacy.recommendedWebView, actual.recommendedWebView)
            assertEquals("$id companionMaxVersion", legacy.companionMaxVersion, actual.companionMaxVersion)
            assertEquals("$id tameVendorCandidates", legacy.tameVendorCandidates, actual.tameVendorCandidates)
            assertEquals("$id default model label", legacy.panelModelLabel(""), actual.panelModelLabel(""))

            for (input in 0..255) {
                assertEquals("$id LED red($input)", legacy.ledTransfer.red(input), actual.ledTransfer.red(input))
                assertEquals("$id LED green($input)", legacy.ledTransfer.green(input), actual.ledTransfer.green(input))
                assertEquals("$id LED blue($input)", legacy.ledTransfer.blue(input), actual.ledTransfer.blue(input))
            }
        }
    }

    @Test fun shizukuRecommendationsAreExplicitAndMatchPanelAuthorityPolicy() {
        val recommended = setOf("smt1019", "shelly-wall-display", "shelly-wall-display-v2", "zx-smt156")
        assertEquals(recommended, documents.values.filter {
            it.provisioning.access.shizuku == ShizukuRecommendation.RECOMMENDED
        }.map { it.id }.toSet())
        assertEquals(legacyById.keys - recommended, documents.values.filter {
            it.provisioning.access.shizuku == ShizukuRecommendation.OPTIONAL
        }.map { it.id }.toSet())
        assertFalse(documents.values.any { it.provisioning.access.shizuku == ShizukuRecommendation.NONE })
    }

    @Test fun bundledMatchingIsLosslessForLegacyFixturesAndBranchCollisions() {
        val fixtures = listOf(
            Facts("PX30_EVB", "px30", ""),
            Facts("rk3326_evb", "", ""),
            Facts("", "rk3326-s", ""),
            Facts("px30", "px30", "NSPanel120P_3.7.1"),
            Facts("TPA10", "tpa10", ""),
            Facts("something", "tpa10", ""),
            Facts("Amzn Echo Show 5 (2nd Generation)", "cronos", ""),
            Facts("CRONOS", "unrelated", ""),
            Facts("rk3566_t", "rk3566_t", "ZX-SMT156-R128V1.2B"),
            Facts("unrelated", "RK3566_T", ""),
            Facts("blake", "", ""),
            Facts("jenna", "", ""),
            Facts("SAWD-3A1XE10EU2", "", ""),
            Facts("", "sawd-4a1xx", ""),
            Facts("stargate", "", ""),
            Facts("atlantis", "", ""),
            Facts("SAWD-0A1XX10EU1", "", ""),
            Facts("", "k400_mt6580", ""),
            Facts("", "e500_7731e", ""),
            Facts("rk3576_u", "wf1589t", ""),
            Facts("rk3576_u", "unrelateddev", ""),
            Facts("", "s9e", ""),
            Facts("s9", "rk3566_r", "S9_Android_1.1.0"),
            Facts("foo", "bar", "s9_android_1.1.0"),
            Facts("mysterypanel", "unknowndev", ""),
            Facts("", "", ""),
            Facts("rk3576_u", "wf2489t", ""),
            Facts("PX30_EVB", "Jenna", ""),
            Facts("rk3326", "SAWD-5A1XX10EU0", ""),
            Facts("rk3326", "cronos", ""),
            Facts("rk3576_u", "rk3566_t", ""),
            Facts("unknown", "unknown", "NSPanel120P_3.7.1"),
            Facts("unknown", "unknown", "s6_android_4.6.0"),
            // These synthetic collisions prove group priority reproduces the legacy branch order.
            Facts("px30", "wf1589t", ""),
            Facts("rk3576_u", "wf1589t", "s9_android_1.1.0"),
            Facts("rk3576_u", "unrelated", "NSPanel120P_3.7.1"),
        )
        fixtures.forEach { facts ->
            val expected = DeviceProfile.match(facts.model, facts.device, facts.productVersion).id
            assertEquals("profile mismatch for $facts", expected, resolve(facts))
        }
    }

    @Test fun nspanelNamedStrategiesPreserveEveryBoundaryAndLabelVariant() {
        val document = documents.getValue("nspanel-pro")
        val variants = listOf(
            "s6_android_2.9.9" to Triple(true, "NSPanel 86P · fw 2.9.9", 160),
            "s6_android_3.0.0" to Triple(false, "NSPanel 86P · fw 3.0.0", 160),
            "NSPanel120P_3.4.9" to Triple(true, "NSPanel 120P · fw 3.4.9", 250),
            "NSPanel120P_3.5.0" to Triple(false, "NSPanel 120P · fw 3.5.0", 250),
            "unrelated_vendor_format" to Triple(null, NSPanelPro.displayName, 160),
        )
        variants.forEach { (productVersion, expected) ->
            val actual = DataDeviceProfile(document, productVersion)
            assertEquals(productVersion, NSPanelPro.proximityGradedForFirmware(productVersion), actual.proximityGradedForFirmware(productVersion))
            assertEquals(productVersion, expected.first, actual.proximityGradedForFirmware(productVersion))
            assertEquals(productVersion, NSPanelPro.panelModelLabel(productVersion), actual.panelModelLabel(productVersion))
            assertEquals(productVersion, expected.second, actual.panelModelLabel(productVersion))
            assertEquals(productVersion, expected.third, actual.recommendedDensity)
        }
    }

    private fun resolve(facts: Facts): String {
        val filesDir = Files.createTempDirectory("bundled-profile-parity").toFile()
        return try {
            RuntimeProfileRegistry(
                filesDir = filesDir,
                preferences = MemoryPreferences(),
                bundledLoader = { rawByName },
                facts = DeviceFacts(facts.model, facts.device, facts.productVersion),
                coreVersion = CORE_VERSION,
                clock = { 1_000L },
            ).resolveForStartup().profile.id
        } finally {
            filesDir.deleteRecursively()
        }
    }

    private data class Facts(val model: String, val device: String, val productVersion: String)

    private class MemoryPreferences : ProfilePreferences {
        private val values = mutableMapOf<String, Any?>()
        override fun getString(key: String, default: String): String = values[key] as? String ?: default
        override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default
        override fun put(vararg values: Pair<String, Any?>): Boolean {
            values.forEach { (key, value) ->
                if (value == null) this.values.remove(key) else this.values[key] = value
            }
            return true
        }
    }

    private companion object {
        val CORE_VERSION: String = BuildConfig.VERSION_NAME
    }
}
