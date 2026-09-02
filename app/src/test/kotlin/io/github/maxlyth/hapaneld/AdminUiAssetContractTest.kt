package io.github.maxlyth.hapaneld

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class AdminUiAssetContractTest {
    private val assetsDir: File =
        listOf("src/main/assets", "app/src/main/assets", "../app/src/main/assets")
            .map(::File)
            .first { it.isDirectory }

    @Test fun configureSwitchesExposeStateAndKeyboardControl() {
        val source = File(assetsDir, "configure.js").readText()
        assertTrue(source.contains("\"aria-checked\""))
        assertTrue(source.contains("event.key !== \"Enter\""))
        assertTrue(source.contains("event.key !== \" \""))
        assertTrue(source.contains("event.preventDefault()"))
    }

    @Test fun collapsedNavigationReportsStateAndClosesOnEscape() {
        val source = File(assetsDir, "switcher.js").readText()
        assertTrue(source.contains("aria-expanded"))
        assertTrue(source.contains("aria-controls"))
        assertTrue(source.contains("event.key !== 'Escape'"))
        assertTrue(source.contains("burger.focus()"))
    }

    @Test fun narrowConfigureRowsStackWithoutMinimumWidthOverflow() {
        val source = File(assetsDir, "info.css").readText()
        assertTrue(source.contains("@media(max-width:600px)"))
        assertTrue(source.contains(".frow{flex-direction:column"))
        assertTrue(source.contains("width:100%;min-width:0;box-sizing:border-box"))
    }

    @Test fun advisoryPanelsHaveEqualVerticalMargins() {
        val css = File(assetsDir, "info.css").readText()

        assertTrue(css.contains(".setup{background:var(--setup-bg)"))
        assertTrue(css.contains("margin:10px 0!important;color:var(--setup-fg)"))
        assertTrue(!css.contains("margin:10px 0 0;color:var(--setup-fg)"))
    }

    @Test fun wrongDeviceProfilesAreInertAndServerIssuesRemainVisible() {
        val source = File(assetsDir, "profiles.js").readText()
        assertTrue(source.contains("summary.matches_this_device === false"))
        assertTrue(source.contains("Does not match this device"))
        assertTrue(source.contains("must never reach the Hardened approval endpoint"))
        assertTrue(source.contains("error.body && error.body.issues && error.body.issues[0]"))
    }

    @Test fun secretConfigExportDoesNotUseAChallengeNavigatingAnchor() {
        val install = File(assetsDir, "install.js").readText()
        assertTrue(install.contains("window.configExport = function"))
        assertTrue(install.contains("if (r.status === 202) return approvalAwareJson(r)"))

        val server = listOf(
            "src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
            "app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
            "../app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
        ).map(::File).first { it.isFile }.readText()
        assertTrue(server.contains("onclick=\"configExport(true,this)\""))
        assertFalse(server.contains("href=\"/api/v1/config/export?include_secrets=1\""))
    }

    @Test fun installOwnedFormsHaveTruthfulStructuredContractsAndLocalReturns() {
        val server = listOf(
            "src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
            "app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
            "../app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
        ).map(::File).first { it.isFile }.readText()
        assertTrue(server.contains("\"apply-failed\""))
        assertTrue(server.contains("val responseStatus = if (ok) HttpStatusCode.OK else HttpStatusCode.InternalServerError"))
        assertFalse(server.contains("density unchanged"))
        assertTrue(server.contains("url=/install#cfg-tame"))
        assertTrue(server.contains("url=/install#cfg-display"))
    }

    @Test fun hardenedModeBlocksDevToolsBeforeApprovalAndDisablesTheControl() {
        val server = listOf(
            "src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
            "app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
            "../app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
        ).map(::File).first { it.isFile }.readText()
        val route = server.substringAfter("post(\"/inspect/start\")").substringBefore("post(\"/inspect/stop\")")
        assertTrue(route.indexOf("rejectHardenedDevToolsRelay") < route.indexOf("authorizeSensitive"))
        assertTrue(server.contains("devtools-incompatible-with-hardened-mode"))
        val info = File(assetsDir, "info.js").readText()
        assertTrue(info.contains("i18nText('dashboard.inspect.hardened_disabled','Unavailable while Hardened mode is enabled."))
        assertTrue(info.contains("d.status==='hardened-disabled'"))
        assertTrue(info.contains("start.disabled=d.start_allowed===false"))
    }
}
