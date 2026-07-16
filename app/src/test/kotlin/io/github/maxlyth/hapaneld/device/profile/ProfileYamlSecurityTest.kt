package io.github.maxlyth.hapaneld.device.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileYamlSecurityTest {
    @Test fun `valid document round trips through strict yaml`() {
        val document = testProfileDocument()
        val parsed = ProfileYaml.parse(ProfileYaml.serialize(document))

        assertEquals(emptyList<ProfileIssue>(), parsed.issues)
        assertEquals(document, parsed.document)
        assertTrue(ProfileValidator.validate(parsed.document!!, "1.0.0", bundled = false).isEmpty())
    }

    @Test fun `duplicate keys are rejected`() {
        val raw = ProfileYaml.serialize(testProfileDocument()).replaceFirst("schema: 2", "schema: 2\nschema: 2")
        assertRejected(raw)
    }

    @Test fun `non string map key reports issue and never escapes`() {
        val raw = "1: value\n" + ProfileYaml.serialize(testProfileDocument())
        assertRejected(raw)
    }

    @Test fun `collection aliases are rejected`() {
        val raw = ProfileYaml.serialize(testProfileDocument()).replace(
            "limitations:\n    - Test-only profile.",
            "limitations: &shared\n    - Test-only profile.\n  tested_firmware: *shared",
        )
        assertRejected(raw)
    }

    @Test fun `unknown fields are rejected`() {
        assertRejected(ProfileYaml.serialize(testProfileDocument()) + "unknown_field: true\n")
    }

    @Test fun `two thousand nested collections are rejected before construction`() {
        val raw = "[".repeat(2_000) + "0" + "]".repeat(2_000)
        assertRejected(raw)
    }

    @Test fun `oversize yaml is rejected before loading`() {
        val raw = "#" + "x".repeat(ProfileMetadata.MAX_BYTES + 1)
        assertRejected(raw)
    }

    @Test fun `privileged paths are driver specific allowlists`() {
        val unsupported = testProfileDocument().copy(
            requires = ProfileRequirements(drivers = setOf("screen.brightness-zero", "relay.sysfs")),
            hardware = testProfileDocument().hardware.copy(relayBase = "/sys/class/strelay;id"),
        )
        val issues = ProfileValidator.validate(unsupported, "1.0.0", bundled = false)
        assertTrue(issues.any { it.path == "hardware.relay_base" && it.severity == ProfileIssueSeverity.ERROR })
    }

    @Test fun `evdev path code event and duplicate mappings fail closed`() {
        val invalid = ProfileEvdevButton("/dev/input/event1;id", 0, false, "POWER")
        val document = testProfileDocument().copy(
            requires = ProfileRequirements(drivers = setOf("screen.brightness-zero", "input.evdev")),
            input = ProfileInput(listOf(invalid, invalid)),
        )
        val issues = ProfileValidator.validate(document, "1.0.0", bundled = false)
        assertTrue(issues.any { it.path.endsWith(".node") })
        assertTrue(issues.any { it.path.endsWith(".code") })
        assertTrue(issues.any { it.path.endsWith(".event_type") })
        assertTrue(issues.any { "Duplicate" in it.message })
    }

    @Test fun `relay fallback duplicates and overflowing gpio block are rejected`() {
        val document = testProfileDocument().copy(
            requires = ProfileRequirements(drivers = setOf("screen.brightness-zero", "relay.sysfs", "relay.gpio-button-led")),
            hardware = testProfileDocument().hardware.copy(
                relayBase = "/sys/class/strelay",
                relayBaseFallbacks = listOf("/sys/class/strelay", "/sys/class/strelay"),
                buttonLedGpioBase = 4093,
            ),
        )
        val issues = ProfileValidator.validate(document, "1.0.0", bundled = false)
        assertTrue(issues.any { it.path == "hardware.relay_base_fallbacks" })
        assertTrue(issues.any { it.path == "hardware.button_led_gpio_base" })
    }

    @Test fun `profile cannot supply an apk trust root`() {
        val raw = ProfileYaml.serialize(testProfileDocument()).replace(
            "software: {}",
            "software:\n    webview:\n      artifact: lineageos-150-arm64\n      url: https://evil.example/app.apk\n      cert_sha256: ${"0".repeat(64)}",
        )
        assertRejected(raw)
        val unknown = testProfileDocument().copy(
            requires = ProfileRequirements(drivers = setOf("screen.brightness-zero", "update.webview")),
            provisioning = testProfileDocument().provisioning.copy(
                software = ProfileProvisioningSoftware(
                    webView = ProfileWebViewProvisioning("third-party-apk"),
                ),
            ),
        )
        assertTrue(ProfileValidator.validate(unknown, "1.0.0", bundled = false).any {
            it.path == "provisioning.software.webview.artifact"
        })
    }

    @Test fun `known core webview artifact resolves compiled trust data`() {
        val document = testProfileDocument().copy(
            requires = ProfileRequirements(drivers = setOf("screen.brightness-zero", "update.webview")),
            provisioning = testProfileDocument().provisioning.copy(
                software = ProfileProvisioningSoftware(
                    webView = ProfileWebViewProvisioning("lineageos-150-arm64"),
                ),
            ),
        )
        assertTrue(ProfileValidator.validate(document, "1.0.0", bundled = false).isEmpty())
        assertEquals(ProfileArtifacts.webViews["lineageos-150-arm64"], DataDeviceProfile(document, "").recommendedWebView)
    }

    @Test fun `display recommendations stay within controller bounds`() {
        val density = testProfileDocument().copy(
            provisioning = testProfileDocument().provisioning.copy(
                display = ProfileProvisioningDisplay(
                    density = ProfileDensity.Fixed(641),
                    fontScale = 1.51f,
                ),
            ),
        )

        val issues = ProfileValidator.validate(density, "1.0.0", bundled = false)

        assertTrue(issues.any { it.path == "provisioning.display.density" })
        assertTrue(issues.any { it.path == "provisioning.display.font_scale" })
    }

    @Test fun `schema 1 locations are not interpreted by schema 2`() {
        val serialized = ProfileYaml.serialize(testProfileDocument())
        assertRejected(serialized.replace("schema: 2", "schema: 1"))
        assertRejected(serialized.replace(
            "has_recents: true",
            "has_recents: true\n  shizuku: recommended",
        ))
        assertRejected(serialized + "updates: {}\n")
        assertRejected(serialized + "taming: []\n")
    }

    @Test fun `recipes are core owned argument free and unique`() {
        val known = testProfileDocument().copy(
            provisioning = testProfileDocument().provisioning.copy(
                recipes = listOf(ProfileRecipeSelection("tpa10.vendor-stack-minimize")),
            ),
        )
        assertTrue(ProfileValidator.validate(known, "1.0.0", bundled = false).isEmpty())

        val unknown = known.copy(
            provisioning = known.provisioning.copy(
                recipes = listOf(ProfileRecipeSelection("community.shell-command")),
            ),
        )
        assertTrue(ProfileValidator.validate(unknown, "1.0.0", bundled = false).any {
            it.path == "provisioning.recipes[0].id"
        })

        val raw = ProfileYaml.serialize(known).replace(
            "id: tpa10.vendor-stack-minimize",
            "id: tpa10.vendor-stack-minimize\n      command: reboot",
        )
        assertRejected(raw)
    }

    @Test fun `sandbox walled profile cannot declare su polled gpio proximity`() {
        val document = testProfileDocument().copy(
            requires = ProfileRequirements(drivers = setOf("screen.brightness-zero", "sensor.gpio-proximity")),
            sensors = ProfileSensors(proximityGpio = 18),
        )

        val issues = ProfileValidator.validate(document, "1.0.0", bundled = false)

        assertTrue(issues.any { it.path == "sensors.proximity_gpio" && "app_can_su" in it.message })
    }

    @Test fun `project rc suffixes compare numerically and precede stable`() {
        assertTrue(ProfileValidator.compareVersions("0.9.3-rc10", "0.9.3-rc2") > 0)
        assertEquals(0, ProfileValidator.compareVersions("0.9.3-rc2", "0.9.3-rc2"))
        assertTrue(ProfileValidator.compareVersions("0.9.3-rc2", "0.9.3") < 0)
        assertTrue(ProfileValidator.compareVersions("0.9.3", "0.9.3-rc10") > 0)
    }

    private fun assertRejected(raw: String) {
        val result = runCatching { ProfileYaml.parse(raw) }
        assertTrue("parser threw ${result.exceptionOrNull()}", result.isSuccess)
        assertNull(result.getOrThrow().document)
        assertFalse(result.getOrThrow().issues.isEmpty())
    }
}
