package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.i18n.CatalogueLoader
import io.github.maxlyth.hapaneld.i18n.Strings
import java.io.File
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class DashboardRuntimeValueLocalizationTest {
    private val strings = CatalogueLoader { path -> File("src/main/assets", path).readText() }.strings("zh-Hans")
    private val server = (unsafe().allocateInstance(PaneldServer::class.java) as PaneldServer).also { instance ->
        // Unsafe deliberately skips the enormous production constructor. Restore the two private
        // row-name fields that localizedRuntimeValue dispatches on; literal-key branches need no state.
        setField(instance, "HA_RENDERER_FACT", "HA renderer")
        setField(instance, "CAMERA_FACT", "Camera")
    }
    private val localize: Method = PaneldServer::class.java.getDeclaredMethod(
        "localizedRuntimeValue",
        String::class.java,
        String::class.java,
        Strings::class.java,
    ).apply { isAccessible = true }

    @Test fun ordinaryBuiltInRendererStatesUseTheSelectedCatalogue() {
        val renderedRaw = "built-in · dashboard rendered · admitted 2m ago"
        val renderedStatus = format(
            "dashboard.runtime.renderer.builtin",
            "summary" to strings.get("dashboard.runtime.renderer.rendered"),
        )
        val rendered = translated("HA renderer", renderedRaw)

        assertEquals(
            format(
                "dashboard.runtime.renderer.admitted_age",
                "status" to renderedStatus,
                "age" to "2m",
            ),
            rendered,
        )
        assertTechnicalEvidence(rendered, "2m")
        assertNoEnglish(rendered, "built-in", "dashboard rendered", "admitted", "ago")

        val checkingRaw = "built-in · checking Home Assistant compatibility"
        val checking = translated("HA renderer", checkingRaw)
        assertEquals(
            format(
                "dashboard.runtime.renderer.builtin",
                "summary" to strings.get("dashboard.runtime.renderer.checking"),
            ),
            checking,
        )
        assertNotEquals(checkingRaw, checking)
        assertNoEnglish(checking, "built-in", "checking Home Assistant compatibility")
    }

    @Test fun normalMqttStatusLocalizesItsScaffoldAndPreservesProtocolEvidence() {
        val raw = "connected · TLS/IPv4 · last-ok 12s ago · last-auth never · auth-ok · family Automatic (next IPv6)"
        val actual = translated("MQTT state", raw)
        val expected = format(
            "dashboard.runtime.mqtt.status",
            "state" to strings.get("dashboard.runtime.mqtt.state.connected"),
            "transport" to "TLS/IPv4",
            "lastOk" to format("dashboard.runtime.mqtt.age_seconds_ago", "seconds" to "12"),
            "lastAuth" to strings.get("dashboard.runtime.mqtt.age_never"),
            "auth" to "auth-ok",
            "family" to format("dashboard.runtime.mqtt.family_automatic", "family" to "IPv6"),
        )

        assertEquals(expected, actual)
        assertTechnicalEvidence(actual, "TLS/IPv4", "12", "auth-ok", "IPv6")
        assertNoEnglish(actual, "connected", "last-ok", "last-auth", "never", "family", "Automatic", "next")
    }

    @Test fun retryingAndConfigurationErrorMqttStatesCannotFallThroughRawEnglish() {
        val retryRaw = "auth-retrying · TCP/IPv4 · last-ok never · last-auth 7s ago · " +
            "auth-retrying · rejects 2 · attempt 3 · next 9s · family Prefer IPv4"
        val retry = translated("MQTT state", retryRaw)
        val retryState = strings.get("dashboard.runtime.mqtt.state.auth_retrying")
        val expectedAuth = format(
            "dashboard.runtime.mqtt.auth_retry",
            "state" to retryState,
            "rejects" to "2",
            "attempt" to "3",
            "next" to format("dashboard.runtime.mqtt.seconds", "seconds" to "9"),
        )
        assertEquals(
            format(
                "dashboard.runtime.mqtt.status",
                "state" to retryState,
                "transport" to "TCP/IPv4",
                "lastOk" to strings.get("dashboard.runtime.mqtt.age_never"),
                "lastAuth" to format("dashboard.runtime.mqtt.age_seconds_ago", "seconds" to "7"),
                "auth" to expectedAuth,
                "family" to strings.get("dashboard.runtime.mqtt.family_prefer_ipv4"),
            ),
            retry,
        )
        assertTechnicalEvidence(retry, "TCP/IPv4", "2", "3", "9", "7")
        assertNoEnglish(retry, "auth-retrying", "last-ok", "last-auth", "rejects", "attempt", "next", "family", "Prefer")

        val errorRaw = "config-error · invalid or unsupported broker URL"
        val error = translated("MQTT state", errorRaw)
        assertEquals(strings.get("dashboard.runtime.mqtt.config_error"), error)
        assertNotEquals(errorRaw, error)
        assertNoEnglish(error, "config-error", "invalid", "unsupported broker")
    }

    @Test fun databaseOptionalShapesLocalizeLabelsWithoutChangingMeasurements() {
        listOf(
            "1.5 MB used" to format("dashboard.runtime.database.used", "bytes" to "1.5 MB"),
            "1.5 MB used · 2.0 MB on disk" to (
                format("dashboard.runtime.database.used", "bytes" to "1.5 MB") + " · " +
                    format("dashboard.runtime.database.on_disk", "bytes" to "2.0 MB")
                ),
            "1.5 MB used · schema 11" to (
                format("dashboard.runtime.database.used", "bytes" to "1.5 MB") + " · " +
                    format("dashboard.runtime.database.schema", "version" to "11")
                ),
            "1.5 MB used · 2.0 MB on disk · schema 11" to (
                format("dashboard.runtime.database.used", "bytes" to "1.5 MB") + " · " +
                    format("dashboard.runtime.database.on_disk", "bytes" to "2.0 MB") + " · " +
                    format("dashboard.runtime.database.schema", "version" to "11")
                ),
        ).forEach { (raw, expected) ->
            val actual = translated("App database", raw)
            assertEquals(raw, expected, actual)
            Regex("""\d+(?:\.\d+)? (?:[KMGTPE]?B)|(?<=schema )\d+""")
                .findAll(raw)
                .map { it.value }
                .forEach { evidence ->
                    assertTrue("$raw lost $evidence in $actual", actual.contains(evidence))
                }
            assertNoEnglish(actual, " used", "on disk", "schema")
        }
    }

    @Test fun cameraCommonStatesLocalizeWhileCountsFaultsPortsAndUrlsRemainExact() {
        val streamUrl = "rtsp://192.0.2.1:8554/live"
        val cases = listOf(
            Triple("camera off", strings.get("dashboard.camera.session.off"), emptyList()),
            Triple(
                "camera on, but Android has not granted the permission",
                strings.get("dashboard.camera.session.permission_needed"),
                listOf("Android"),
            ),
            Triple(
                "camera opening; stream listening on port 8554",
                strings.get("dashboard.camera.session.opening") + "; " +
                    format("dashboard.runtime.camera.stream_port", "port" to "8554"),
                listOf("8554"),
            ),
            Triple(
                "camera closed; nobody is watching; stream at $streamUrl (not for this panel's own dashboard)",
                strings.get("dashboard.camera.session.closed") + " · " +
                    strings.get("dashboard.camera.watchers.none") + "; " +
                    format("dashboard.runtime.camera.stream_url", "url" to streamUrl),
                listOf(streamUrl),
            ),
            Triple(
                "camera open for 2 clients (1 streaming); stream listening on port 8554",
                strings.get("dashboard.camera.session.open") + " · " +
                    format(
                        "dashboard.camera.watchers.streaming",
                        "watching" to format("dashboard.camera.watchers.many", "count" to "2"),
                        "count" to "1",
                    ) + "; " + format("dashboard.runtime.camera.stream_port", "port" to "8554"),
                listOf("2", "1", "8554"),
            ),
            Triple(
                "camera gave up after 3 failures (encode); stream not listening",
                format("dashboard.camera.session.degraded", "count" to "3") +
                    " (encode); " + strings.get("dashboard.runtime.camera.stream_not_listening"),
                listOf("3", "encode"),
            ),
        )

        cases.forEach { (raw, expected, evidence) ->
            val actual = translated("Camera", raw)
            assertEquals(raw, expected, actual)
            assertNotEquals(raw, actual)
            assertTechnicalEvidence(actual, *evidence.toTypedArray())
            assertNoEnglish(
                actual,
                "camera off",
                "camera on, but",
                "camera opening",
                "camera closed",
                "nobody is watching",
                "camera open for",
                "clients",
                "streaming",
                "camera gave up after",
                "failures",
                "stream listening on port",
                "stream not listening",
                "not for this panel's own dashboard",
            )
        }
    }

    @Test fun unknownEvidenceStaysVerbatimWhileRecognizedHaEvidenceLocalizesOnlyItsPrefix() {
        val unknown = "vendor probe E42 · /dev/example <opaque>&"
        assertEquals(unknown, translated("unrecognized fact", unknown))

        val evidence = "RTT p95 842 ms via fd00::7"
        val raw = "healthy; $evidence"
        val actual = translated("HA network path", raw)
        assertEquals(format("dashboard.runtime.ha_network_healthy", "evidence" to evidence), actual)
        assertNotEquals(raw, actual)
        assertTechnicalEvidence(actual, evidence)
        assertEquals("recognized evidence must occur exactly once", 1, actual.windowed(evidence.length).count { it == evidence })
        assertNoEnglish(actual, "healthy;")
    }

    private fun translated(key: String, value: String): String =
        localize.invoke(server, key, value, strings) as String

    private fun format(key: String, vararg values: Pair<String, String>): String =
        values.fold(strings.get(key)) { text, (name, value) -> text.replace("{$name}", value) }

    private fun assertTechnicalEvidence(actual: String, vararg evidence: String) {
        evidence.forEach { item -> assertTrue("missing technical evidence $item in $actual", actual.contains(item)) }
    }

    private fun assertNoEnglish(actual: String, vararg fragments: String) {
        fragments.forEach { fragment ->
            assertFalse("raw English scaffold '$fragment' leaked in $actual", actual.contains(fragment, ignoreCase = true))
        }
    }

    private fun unsafe(): Unsafe {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        return field.get(null) as Unsafe
    }

    private fun setField(instance: PaneldServer, name: String, value: String) {
        PaneldServer::class.java.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }
}
