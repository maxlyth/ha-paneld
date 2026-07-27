package io.github.maxlyth.hapaneld

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityNavigationContractTest {
    private val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/MainActivity.kt").readText()

    @Test fun standingScreenConfigurationLinksFollowTheSetupJourney() {
        // Both surfaces — the scannable URL and the on-panel Configure button — must land on the SAME
        // page, and that page is guided setup until this panel has completed setup once. Splitting them
        // (QR to the wizard, button to the settings wall) would give two people commissioning one panel
        // two different truths.
        assertTrue(
            source.contains(
                "LocalAdminEndpoint.externalUrl(localIpv4(), localIpv6(), config.httpPort, adminPath())",
            ),
        )
        assertTrue(
            source.contains(
                "Intent(this, ConfigActivity::class.java).putExtra(\"path\", adminPath())",
            ),
        )
        assertTrue(source.contains("private fun adminPath(): String = if (config.setupEverCompleted) \"/configure\" else \"/setup\""))
        assertTrue(source.contains("qrBitmap(url, dp(qrDp))"))
    }

    @Test fun builtinDashboardIntentRequiresAnAuthenticatedBuiltInRenderer() {
        assertTrue(source.contains("config.builtInRendererReady()"))
        assertTrue(
            source.contains(
                "RendererResolver.resolveLaunchable(\n" +
                    "            configuredPackage = config.dashboardPackage,\n" +
                    "            builtinReady = config.builtInRendererReady()",
            ),
        )
        assertTrue(source.contains("RendererTarget.Builtin -> Intent(this, DashboardActivity::class.java)"))
    }
}
