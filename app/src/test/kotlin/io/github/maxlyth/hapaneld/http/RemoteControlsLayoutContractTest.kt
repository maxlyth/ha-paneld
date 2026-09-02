package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlsLayoutContractTest {
    private val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
    private val openApi = File("src/main/assets/openapi.json").readText()

    @Test fun `controls omit volume and lead the second row with Dashboard`() {
        val controls = source.substringAfter("private fun controlsHtml(s: Snap?, strings: AppStrings): String")
            .substringBefore("private fun infoJson(strings: AppStrings)")
        val secondary = controls.substringAfter("<div class=\"ctlrow ctlrow-secondary\">")
            .substringBefore("</div>\"\"\"")

        assertFalse(controls.contains("pbtn(\"voldn\""))
        assertFalse(controls.contains("pbtn(\"volup\""))
        assertTrue(secondary.contains("strings.get(\"dashboard.controls.dashboard\")"))
        assertTrue(secondary.contains("strings.get(\"dashboard.controls.reload\")"))
        assertFalse(secondary.contains("Home Assistant"))
        assertTrue(secondary.indexOf("pbtn(\"dashboard\"") < secondary.indexOf("pbtn(\"reload\""))
    }

    @Test fun hiddenVolumeActionsRemainSupportedByApiAndDispatcher() {
        val actions = source.substringAfter("internal val REMOTE_ACTIONS").substringBefore(")")
        val dispatcher = source.substringAfter("\"back\" -> interactive.back()").substringBefore("else -> false")
        val apiAction = openApi.substringAfter("\"/api/v1/action\"").substringBefore("\"/api/v1/input\"")

        assertTrue(actions.contains("volup"))
        assertTrue(actions.contains("voldn"))
        assertTrue(dispatcher.contains("volume.step(up = true)"))
        assertTrue(dispatcher.contains("volume.step(up = false)"))
        assertTrue(apiAction.contains("volup"))
        assertTrue(apiAction.contains("voldn"))
    }
}
