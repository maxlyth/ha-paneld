package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererConfigEffectsTest {
    @Test fun dashboardSwitchDominatesReloadAndRelaunchRequests() {
        val effects = RendererConfigEffects.coalesce(
            dashboardChanged = true,
            credentialChanged = true,
            zoomChanged = true,
            fullscreenChanged = true,
            overscrollChanged = true,
            darkMode = true,
        )

        assertTrue(effects.dashboardChanged)
        assertFalse(effects.reloadBuiltin)
        assertFalse(effects.relaunchBuiltin)
    }

    @Test fun reloadDominatesForegroundRelaunch() {
        val effects = RendererConfigEffects.coalesce(
            dashboardChanged = false,
            credentialChanged = false,
            zoomChanged = true,
            fullscreenChanged = true,
            overscrollChanged = false,
            darkMode = null,
        )

        assertTrue(effects.reloadBuiltin)
        assertFalse(effects.relaunchBuiltin)
    }

    @Test fun fullscreenOnlyChangeNeedsForegroundRelaunch() {
        val effects = RendererConfigEffects.coalesce(
            dashboardChanged = false,
            credentialChanged = false,
            zoomChanged = false,
            fullscreenChanged = true,
            overscrollChanged = false,
            darkMode = null,
        )

        assertFalse(effects.reloadBuiltin)
        assertTrue(effects.relaunchBuiltin)
    }

    @Test fun importedExplicitAccessTokenReloadsWhenItDropsAnOldRefreshSession() {
        val effects = RendererConfigEffects.between(
            previous = mapOf("ha_token" to "same-access", "ha_refresh_token" to "old-refresh"),
            accepted = mapOf("ha_token" to "same-access"),
        )

        assertTrue(effects.reloadBuiltin)
    }

    @Test fun trailingSlashOnlyHaUrlDifferenceDoesNotReload() {
        val effects = RendererConfigEffects.between(
            previous = mapOf("ha_url" to "http://ha:8123"),
            accepted = mapOf("ha_url" to "http://ha:8123/"),
        )

        assertFalse(effects.reloadBuiltin)
    }
}
