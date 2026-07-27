package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.security.ApprovalBroker
import io.github.maxlyth.hapaneld.security.SensitiveOperation
import io.ktor.http.Parameters
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormApprovalBindingTest {
    @Test fun duplicateValueOrderIsBoundWhenItChangesTheConsumedFirstValue() {
        val sensitiveFields = listOf(
            Triple("uninstall package", "pkg", "com.example.first" to "com.example.second"),
            Triple("component install target", "name", "paneld" to "companion"),
            Triple("staged APK token", "token", "first-token" to "second-token"),
            Triple("remote action", "a", "reboot" to "home"),
            Triple("install, tame, or display action", "action", "tame" to "untame"),
            Triple("display density", "density", "160" to "240"),
            Triple("backup passphrase", "passphrase", "first-secret" to "second-secret"),
        )

        for ((gate, field, values) in sensitiveFields) {
            val firstThenSecond = duplicate(field, values.first, values.second)
            val secondThenFirst = duplicate(field, values.second, values.first)
            assertEquals("$gate must consume the first submitted value", values.first, firstThenSecond[field])
            assertEquals("$gate must consume the reordered first value", values.second, secondThenFirst[field])
            assertNotEquals(
                "$gate approval must change when duplicate-value order changes behavior",
                firstThenSecond.canonicalDigest(),
                secondThenFirst.canonicalDigest(),
            )
        }
    }

    @Test fun distinctFieldOrderIsCanonicalWithoutDiscardingPerFieldValueOrder() {
        val first = Parameters.build {
            append("action", "tame")
            append("action", "untame")
            append("pkg", "com.example.app")
        }
        val reorderedNames = Parameters.build {
            append("pkg", "com.example.app")
            append("action", "tame")
            append("action", "untame")
        }

        assertEquals(first.canonicalDigest(), reorderedNames.canonicalDigest())
    }

    @Test fun directConfigRejectsAmbiguousDuplicatesBeforeApprovalInEitherOrder() {
        val firstThenSecond = duplicate("tame_vendor_packages", "com.example.first", "com.example.second")
        val secondThenFirst = duplicate("tame_vendor_packages", "com.example.second", "com.example.first")

        assertTrue(normalizeConfigPostParameters(firstThenSecond) is ConfigPostParameters.Bad)
        assertTrue(normalizeConfigPostParameters(secondThenFirst) is ConfigPostParameters.Bad)
    }

    @Test fun approvedFirstValueCannotAuthorizeAReorderedTarget() {
        fun payload(first: String, second: String) = exactHttpApprovalPayload(
            method = "POST",
            path = "/api/v1/uninstall",
            parameters = emptyList(),
            bodyDigest = duplicate("pkg", first, second).canonicalDigest(),
        )

        val approved = payload("com.example.first", "com.example.second")
        val substituted = payload("com.example.second", "com.example.first")
        val broker = ApprovalBroker()
        val challenge = broker.request(
            SensitiveOperation.PACKAGE_UNINSTALL,
            "192.0.2.10",
            approved,
            "Uninstall com.example.first",
        )
        assertEquals(ApprovalBroker.Decision.PENDING, challenge.first)
        broker.approve(challenge.second)

        assertEquals(
            ApprovalBroker.Decision.PENDING,
            broker.request(
                SensitiveOperation.PACKAGE_UNINSTALL,
                "192.0.2.10",
                substituted,
                "Uninstall com.example.second",
            ).first,
        )
        assertEquals(
            ApprovalBroker.Decision.APPROVED,
            broker.request(
                SensitiveOperation.PACKAGE_UNINSTALL,
                "192.0.2.10",
                approved,
                "Uninstall com.example.first",
            ).first,
        )
    }

    @Test fun everySensitiveFormHandlerUsesTheOrderSensitiveDigest() {
        val server = source("PaneldServer.kt")
        val control = source("ControlPlaneRoutes.kt")

        assertCanonicalGate(server, "post(\"/uninstall\")", "get(\"/radio\")")
        assertCanonicalGate(server, "internal suspend fun handleRemoteAction", "/** One renderer-sensitive execution seam")
        assertCanonicalGate(server, "post(\"/tame\")", "get(\"/tame/suggest\")", minimumUses = 2)
        assertCanonicalGate(server, "post(\"/display/density\")", "get(\"/inspect\")")
        assertCanonicalGate(server, "private suspend fun handleConfigPost", "private fun configSchemaJson")
        assertCanonicalGate(control, "private suspend fun handleApkCommit", "private suspend fun handleApkUpload")
        assertCanonicalGate(control, "private suspend fun handleComponentInstall", "private suspend fun handleBackup")
        assertCanonicalGate(control, "private suspend fun handleBackup", "private val PLAY_URL")
    }

    private fun duplicate(field: String, first: String, second: String) = Parameters.build {
        append(field, first)
        append(field, second)
    }

    private fun assertCanonicalGate(
        source: String,
        start: String,
        end: String,
        minimumUses: Int = 1,
    ) {
        val startIndex = source.indexOf(start)
        assertTrue("missing sensitive form handler: $start", startIndex >= 0)
        val endIndex = source.indexOf(end, startIndex)
        assertTrue("missing end marker $end after $start", endIndex > startIndex)
        val handler = source.substring(startIndex, endIndex)
        assertTrue(
            "$start must bind every sensitive form branch through canonicalDigest",
            Regex("\\.canonicalDigest\\(\\)").findAll(handler).count() >= minimumUses,
        )
    }

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/http/$name"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/$name"),
    ).first(File::isFile).readText()
}
