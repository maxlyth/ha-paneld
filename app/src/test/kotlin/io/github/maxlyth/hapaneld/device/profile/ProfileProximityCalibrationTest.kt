package io.github.maxlyth.hapaneld.device.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileProximityCalibrationTest {
    private val binary = ProfileProximityCalibration(1, "binary", 1f, 0f, "Attended test fixture")

    private fun document(calibration: ProfileProximityCalibration?) = testProfileDocument().let {
        it.copy(sensors = it.sensors.copy(proximityCalibration = calibration))
    }

    private fun issues(calibration: ProfileProximityCalibration) =
        ProfileValidator.validate(document(calibration), "1.0.0", bundled = false)

    @Test fun `absent baseline stays absent and product metadata cannot synthesize one`() {
        val source = document(null)
        val yaml = ProfileYaml.serialize(source)
        assertFalse(yaml.contains("proximity_calibration"))
        assertNull(ProfileYaml.parse(yaml).document!!.sensors.proximityCalibration)
        assertNull(DataDeviceProfile(source, "120P range 65535", "revision", true).proximityCalibration)
    }

    @Test fun `both binary polarities and both ranged directions round trip and project unchanged`() {
        listOf(binary, binary.copy(clearRaw = 0f, nearRaw = 1f),
            binary.copy(mode = "ranged", clearRaw = 450f, nearRaw = 20f),
            binary.copy(mode = "ranged", clearRaw = 20f, nearRaw = 450f),
        ).forEach { calibration ->
            val source = document(calibration)
            val parsed = ProfileYaml.parse(ProfileYaml.serialize(source))
            assertEquals(emptyList<ProfileIssue>(), parsed.issues)
            assertEquals(source, parsed.document)
            assertEquals(emptyList<ProfileIssue>(), issues(calibration))
            assertEquals(calibration, DataDeviceProfile(source, "lying range metadata", "revision", false).proximityCalibration)
        }
    }

    @Test fun `minimal baseline fills bounded timing and threshold defaults`() {
        val yaml = ProfileYaml.serialize(document(binary)).lineSequence().filterNot {
            it.trimStart().startsWith("near_enter:") || it.trimStart().startsWith("clear_exit:") ||
                it.trimStart().substringBefore(':').endsWith("_ms")
        }.joinToString("\n")
        assertEquals(binary, ProfileYaml.parse(yaml).document!!.sensors.proximityCalibration)
    }

    @Test fun `required evidence endpoints revision and representation cannot be omitted`() {
        listOf("revision", "mode", "clear_raw", "near_raw", "verification").forEach { key ->
            val yaml = ProfileYaml.serialize(document(binary)).lineSequence()
                .filterNot { it.trimStart().startsWith("$key:") }.joinToString("\n")
            val parsed = ProfileYaml.parse(yaml)
            assertNull(key, parsed.document)
            assertTrue(key, parsed.issues.any { it.path == "sensors.proximity_calibration.$key" })
        }
    }

    @Test fun `unknown keys overflow and string numeric values fail strict parsing`() {
        listOf(
            "    maximum_range: 65535\n    revision: 1",
            "    revision: 2147483648",
            "    revision: '1'",
        ).forEach { replacement ->
            val parsed = ProfileYaml.parse(ProfileYaml.serialize(document(binary)).replace("    revision: 1", replacement))
            assertNull(parsed.document)
            assertTrue(parsed.issues.isNotEmpty())
        }
        listOf("1e100", "'0'", "true").forEach { value ->
            val parsed = ProfileYaml.parse(ProfileYaml.serialize(document(binary)).replace("near_raw: 0.0", "near_raw: $value"))
            assertNull(value, parsed.document)
        }
    }

    @Test fun `invalid calibration values are rejected even for directly constructed documents`() {
        listOf(
            binary.copy(revision = 0), binary.copy(mode = "auto"), binary.copy(verification = ""),
            binary.copy(verification = "bad\nreference"), binary.copy(verification = "x".repeat(501)),
            binary.copy(clearRaw = Float.NaN), binary.copy(nearRaw = Float.POSITIVE_INFINITY),
            binary.copy(nearRaw = 1f), binary.copy(clearRaw = 2f),
            binary.copy(mode = "ranged", clearRaw = -Float.MAX_VALUE, nearRaw = Float.MAX_VALUE),
            binary.copy(nearEnter = Float.NaN), binary.copy(clearExit = Float.NaN),
            binary.copy(nearEnter = 1f), binary.copy(clearExit = 0f), binary.copy(clearExit = 0.65f),
            binary.copy(debounceMs = 49), binary.copy(debounceMs = 1001),
            binary.copy(clearArmMs = 199), binary.copy(clearArmMs = 10001),
            binary.copy(debounceMs = 300, clearArmMs = 200, minimumNearMs = 300),
            binary.copy(minimumNearMs = 49), binary.copy(minimumNearMs = 2001),
            binary.copy(minimumNearMs = 100), binary.copy(maximumNearMs = 199),
            binary.copy(maximumNearMs = 10001), binary.copy(maximumNearMs = 200),
            binary.copy(cooldownMs = 199), binary.copy(cooldownMs = 10001),
        ).forEach { invalid ->
            assertTrue(invalid.toString(), issues(invalid).any { it.severity == ProfileIssueSeverity.ERROR })
        }
    }
}
