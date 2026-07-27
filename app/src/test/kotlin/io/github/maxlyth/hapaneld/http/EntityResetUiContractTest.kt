package io.github.maxlyth.hapaneld.http

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityResetUiContractTest {
    private val server = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
    ).first(File::isFile).readText()
    private val script = listOf(
        File("src/main/assets/entities.js"),
        File("app/src/main/assets/entities.js"),
    ).first(File::isFile).readText()

    @Test fun `entities page exposes an explicit reset action`() {
        val page = server.substringAfter("private fun entitiesBody()")
            .substringBefore("private fun entityTableHtml")

        assertTrue(page.contains("id=\"entity-reset\""))
        assertTrue(page.contains(">Reset learned data</button>"))
        assertTrue(page.contains("id=\"entity-action-result\""))
    }

    @Test fun `reset preserves the active filter and refreshes every diagnostic surface`() {
        val handler = script.substringAfter("resetButton.addEventListener('click'")
            .substringBefore("async function savePolicy")

        assertTrue(handler.indexOf("confirm('") < handler.indexOf("mutationRequest(claim,'/api/v1/dashboard/entities/reset'"))
        assertTrue(handler.contains("JSON.stringify({confirm:true,clear_filter:false})"))
        assertFalse(handler.contains("clear_filter:true"))
        assertTrue(handler.contains("entity-discovery safety ignore decisions"))
        assertTrue(handler.contains("resultMessage='Reset failed: '"))
        assertTrue(handler.contains("resultKind='ok'"))
        assertTrue(handler.contains("Promise.all([loadStatus(),loadIssues(),resetAll()])"))
        assertTrue(handler.contains("current live subscription remains in place until that scan succeeds"))
        assertTrue(handler.contains("replacement scan did not start"))
    }

    @Test fun `policy changes refresh runtime coverage advisories`() {
        val handler = script.substringAfter("async function savePolicy()")
            .substringBefore("autoStatic.addEventListener")

        assertTrue(handler.contains("Promise.all([loadStatus(),loadIssues(),resetAll()])"))
        assertTrue(script.contains("issue.type==='runtime_coverage'"))
    }

    @Test fun `openapi exposes the stronger clear filter reset option`() {
        val openApi = listOf(
            File("src/main/assets/openapi.json"),
            File("app/src/main/assets/openapi.json"),
        ).first(File::isFile).readText()
        val schema = JSONObject(openApi).getJSONObject("paths")
            .getJSONObject("/api/v1/dashboard/entities/reset")
            .getJSONObject("post")
            .getJSONObject("requestBody")
            .getJSONObject("content")
            .getJSONObject("application/json")
            .getJSONObject("schema")

        assertEquals(listOf("confirm"), schema.getJSONArray("required").let { array ->
            List(array.length()) { array.getString(it) }
        })
        assertFalse(schema.getJSONObject("properties").getJSONObject("clear_filter").getBoolean("default"))
    }

    @Test fun `one owner guards every entity mutation across asynchronous work`() {
        assertTrue(script.contains("function claimMutation(name)"))
        assertTrue(script.contains("function ownsMutation(claim)"))
        assertTrue(script.contains("function releaseMutation(claim)"))
        assertTrue(script.contains("function mutationRequest(claim,url,options){if(!ownsMutation(claim))"))
        assertTrue(script.contains("finally{releaseMutation(claim)}"))

        listOf(
            "'/api/v1/dashboard/entities/sync'",
            "'/api/v1/dashboard/entities/activate'",
            "'/api/v1/dashboard/entities/policy'",
            "'/api/v1/dashboard/entities/override'",
            "'/api/v1/dashboard/entities/overrides'",
            "'/api/v1/dashboard/entities/issues'",
            "'/api/v1/dashboard/entities/reset'",
        ).forEach { endpoint -> assertTrue("missing guarded mutation $endpoint", "mutationRequest(claim,$endpoint" in script) }
        assertTrue(script.contains("refreshMutationControls();return d"))
        assertTrue(script.contains("selectedMsg.textContent=state.selected.size+' selected';refreshMutationControls()"))
        assertTrue(script.contains(".entity-list select[data-id],.entity-list button[data-bulk],.entity-list button[data-all-candidates],.entity-issue-toggle"))
    }

    @Test fun `deferred mutation request excludes every competing action`() {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val fixture = listOf(
            File(working, "app/src/test/js/entity-mutation-gate-test.mjs"),
            File(working, "src/test/js/entity-mutation-gate-test.mjs"),
        ).first(File::isFile)
        val asset = listOf(
            File(working, "app/src/main/assets/entities.js"),
            File(working, "src/main/assets/entities.js"),
        ).first(File::isFile)
        val process = ProcessBuilder("node", fixture.absolutePath, asset.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(output, 0, process.waitFor())
        assertTrue(output, output.contains("entity mutation gate deferred-fetch cases passed"))
    }
}
