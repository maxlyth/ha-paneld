package io.github.maxlyth.hapaneld.http

import java.io.File
import io.github.maxlyth.hapaneld.util.CompanionInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherSurfaceContractTest {
    private val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
    private val service = File("src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt").readText()
    private val companionSource =
        File("src/main/kotlin/io/github/maxlyth/hapaneld/util/CompanionInstaller.kt").readText()

    @Test fun `launcher picker explicitly exposes panel admin`() {
        val appsJson = source.substringAfter("private fun launchableAppsJson()")
            .substringBefore("private fun packagesJson()")
        assertTrue(appsJson.contains("Panel admin (ha-paneld)"))
        assertFalse(appsJson.contains("filter { it.packageName != appContext.packageName }"))
    }

    @Test fun `renderer inventory comes from the authoritative installed Companion catalogue`() {
        val appsJson = source.substringAfter("private fun launchableAppsJson()")
            .substringBefore("private fun packagesJson()")
        val launchableFallback = appsJson.indexOf("}.getOrDefault(emptyList())")
        val rendererLookup = appsJson.indexOf("CompanionInstaller.installedPackages(appContext)")
        assertTrue("renderer lookup survives launchable-app query failure", launchableFallback in 0 until rendererLookup)
        assertTrue(appsJson.contains("CompanionInstaller.installedPackages(appContext)"))
        assertTrue(appsJson.contains("CompanionInstaller.rendererChoices("))
        assertTrue(companionSource.contains("fun installedPackages(context: Context): Set<String> = installedPackages"))
        assertTrue(companionSource.contains("context.packageManager.getPackageInfo(packageName, 0)"))
        assertTrue(companionSource.contains("runCatching { requireInstalled(packageName) }.isSuccess"))
        assertTrue(appsJson.contains("configureAppInventoryJson(apps, rendererChoices)"))
    }

    @Test fun `launchable app query failure cannot suppress an installed Companion renderer`() {
        val rendererChoices = CompanionInstaller.rendererChoices(setOf(CompanionInstaller.MINIMAL_PKG))
        assertEquals(
            "{\"apps\":[],\"renderers\":[{\"pkg\":\"io.homeassistant.companion.android.minimal\"," +
                "\"label\":\"Home Assistant Companion (minimal)\"}]}",
            configureAppInventoryJson(emptyList(), rendererChoices),
        )
    }

    @Test fun `info launcher control is suppressed when panel admin is selected`() {
        val controls = source.substringAfter("val hasDistinctLauncher")
            .substringBefore("private fun infoJson()")
        assertTrue(controls.contains("resolvedLauncher(config.launcherPackage)?.let { it != appContext.packageName }"))
    }

    @Test fun `service enforces explicit panel admin home while still launching dashboard`() {
        assertTrue(service.contains("system.applyLauncherHomePolicy("))
        assertTrue(service.contains("system.launchHome(config.dashboardPackage)"))
        assertTrue(service.contains("name = \"never-blank\""))
        assertTrue(service.contains("repairAdminHomeFromCapturedRoute()"))
        assertTrue(service.contains("server.lastPrivilegeObservation()"))
        assertTrue(service.contains("launcherHomeFailedGeneration.set(generation)"))
        assertFalse(service.contains("name = \"launcher-home\""))
        assertFalse(service.contains("LAUNCHER_HOME_REASSERT_MS"))
    }
}
