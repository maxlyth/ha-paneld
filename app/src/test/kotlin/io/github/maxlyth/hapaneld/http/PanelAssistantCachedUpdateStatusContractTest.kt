package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.testsupport.TestSources
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Binds the privacy-safe cached update projection to its sole status surface and public grammar. */
class PanelAssistantCachedUpdateStatusContractTest {
    private val server by lazy { TestSources.kotlin("http/PaneldServer.kt").readText() }
    private val checker by lazy { TestSources.kotlin("util/UpdateChecker.kt").readText() }
    private val selfUpdater by lazy { TestSources.kotlin("util/SelfUpdater.kt").readText() }
    private val openApi by lazy { JSONObject(File("src/main/assets/openapi.json").readText()) }

    @Test fun statusProjectsOneExistingCachedReadWithoutStartingAReleaseLookup() {
        val status = server.substring(
            server.indexOf("private fun statusJson():"),
            server.indexOf("/** A health finding"),
        )
        assertTrue(status.contains("val currentUpdates = UpdateChecker.current(appContext)"))
        assertTrue(status.contains("\\\"panel_assistant_update\\\":\${UpdateChecker.panelAssistantUpdateJson(currentUpdates)}"))
        assertFalse(status.contains("UpdateChecker.check("))
        assertFalse(status.contains("UpdateChecker.checkIfStale("))

        val projection = checker.substring(
            checker.indexOf("internal fun panelAssistantUpdate(current:"),
            checker.indexOf("private fun installedCompanion"),
        )
        assertFalse(projection.contains("check("))
        assertFalse(projection.contains("checkIfStale("))
        assertFalse(projection.contains("releaseUrl"))
    }

    @Test fun cachedProjectionRetainsTheResolverIssuedTagInsteadOfReconstructingIt() {
        assertTrue(selfUpdater.contains("ReleaseCatalog.newestApkTarget(REPO, channel, APK_MATCH)"))
        assertTrue(
            selfUpdater.contains(
                "ComponentUpdater.Target(target.version, target.apkUrl, RELEASES_URL, target.tag)",
            ),
        )
    }

    @Test fun openApiKeepsTheProjectionAdditiveAndBoundsBothShapes() {
        val statusSchema = openApi.getJSONObject("paths").getJSONObject("/api/v1/status").getJSONObject("get")
            .getJSONObject("responses").getJSONObject("200").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
        assertFalse(statusSchema.getJSONArray("required").toString().contains("panel_assistant_update"))
        assertEquals(
            "#/components/schemas/PanelAssistantUpdate",
            statusSchema.getJSONObject("properties").getJSONObject("panel_assistant_update").getString("\$ref"),
        )

        val schema = openApi.getJSONObject("components").getJSONObject("schemas").getJSONObject("PanelAssistantUpdate")
        assertEquals(setOf("none", "available"), schema.getJSONObject("properties").getJSONObject("state")
            .getJSONArray("enum").let { values -> (0 until values.length()).map(values::getString).toSet() })
        assertEquals(64, schema.getJSONObject("properties").getJSONObject("current_version").getInt("maxLength"))
        assertEquals(64, schema.getJSONObject("properties").getJSONObject("target_version").getInt("maxLength"))
        assertEquals(64, schema.getJSONObject("properties").getJSONObject("tag").getInt("maxLength"))
        assertEquals(2, schema.getJSONArray("oneOf").length())
    }
}
