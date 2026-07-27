package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MINIMAL = "io.homeassistant.companion.android.minimal"
private const val FULL = "io.homeassistant.companion.android"
private const val OWN = "io.github.maxlyth.hapaneld"

/**
 * Differential coverage for the one renderer-target owner. Each policy is asserted across the full
 * configured/available matrix that the three former rule homes (SystemController.resolveDashboard,
 * resolveAdminDashboardTarget, PaneldService.dashboardTarget) each implemented separately, plus
 * automatic built-in selection and explicit foreign-renderer preservation.
 */
class RendererResolverTest {
    // --- shared: legacy cleanup set + built-in selection ---------------------------------------------

    @Test fun `legacy Companion package set is minimal-first and complete`() {
        assertEquals(listOf(MINIMAL, FULL), RendererResolver.LEGACY_COMPANION_PACKAGES)
        assertEquals(setOf(MINIMAL, FULL), RendererResolver.LEGACY_COMPANION_PACKAGE_SET)
    }

    @Test fun `built-in selection is the sentinel or the own-package alias`() {
        assertTrue(RendererResolver.isBuiltinSelection("builtin", OWN))
        assertTrue(RendererResolver.isBuiltinSelection(OWN, OWN))
        assertEquals(false, RendererResolver.isBuiltinSelection(FULL, OWN))
        assertTrue(RendererResolver.isBuiltinSelection("", OWN))
    }

    // --- control resolution (what SystemController acts on) -----------------------------------------

    @Test fun `control passes the builtin sentinel through unchanged`() {
        assertEquals("builtin", RendererResolver.resolveControlPackage("builtin") { false })
    }

    @Test fun `control retains a valid explicit foreign package even when uninstalled`() {
        assertEquals("com.example.renderer", RendererResolver.resolveControlPackage("com.example.renderer") { false })
    }

    @Test fun `control rejects a structurally invalid non-blank selection`() {
        assertEquals("", RendererResolver.resolveControlPackage("not a package!") { true })
    }

    @Test fun `control auto selects builtin even when a Companion is installed`() {
        val probed = mutableListOf<String>()
        val resolved = RendererResolver.resolveControlPackage("") { probed += it; it == FULL }
        assertEquals("builtin", resolved)
        assertTrue(probed.isEmpty())
    }

    @Test fun `control auto resolves to builtin without probing packages`() {
        assertEquals("builtin", RendererResolver.resolveControlPackage("") { false })
    }

    // --- launchable resolution (what Main/Admin can open now) ---------------------------------------

    @Test fun `launchable ready builtin is available without a Companion`() {
        assertEquals(RendererTarget.Builtin, RendererResolver.resolveLaunchable("builtin", builtinReady = true) { false })
    }

    @Test fun `launchable unready builtin does not fall back to a Companion`() {
        assertNull(RendererResolver.resolveLaunchable("builtin", builtinReady = false) { true })
    }

    @Test fun `launchable foreign renderer is selected when launchable`() {
        assertEquals(
            RendererTarget.Foreign("com.example.renderer"),
            RendererResolver.resolveLaunchable("com.example.renderer", builtinReady = false) { it == "com.example.renderer" },
        )
    }

    @Test fun `launchable missing explicit foreign never falls back silently`() {
        assertNull(RendererResolver.resolveLaunchable("com.example.renderer", builtinReady = false) { it == MINIMAL })
    }

    @Test fun `launchable blank selects ready builtin even when a Companion is launchable`() {
        val probed = mutableListOf<String>()
        assertEquals(RendererTarget.Builtin, RendererResolver.resolveLaunchable("", builtinReady = true) { probed += it; true })
        assertTrue(probed.isEmpty())
    }

    @Test fun `launchable blank selects ready builtin`() {
        assertEquals(RendererTarget.Builtin, RendererResolver.resolveLaunchable("", builtinReady = true) { false })
    }

    @Test fun `launchable blank suppresses the tile without a ready builtin`() {
        assertNull(RendererResolver.resolveLaunchable("", builtinReady = false) { false })
    }

    // --- perf attribution (derived from the resolved control package) -------------------------------

    @Test fun `attribution maps the builtin sentinel to the own package`() {
        val target = RendererResolver.resolveControlTarget("builtin") { false }
        assertEquals(OWN, RendererResolver.attributionOf(target, OWN))
    }

    @Test fun `attribution passes an explicit foreign package through`() {
        val target = RendererResolver.resolveControlTarget("com.example.renderer") { false }
        assertEquals("com.example.renderer", RendererResolver.attributionOf(target, OWN))
    }

    @Test fun `attribution of automatic selection is the built-in renderer`() {
        val target = RendererResolver.resolveControlTarget("") { it == MINIMAL }
        assertEquals(OWN, RendererResolver.attributionOf(target, OWN))
    }

    @Test fun `attribution of auto builtin fallback maps to the own package`() {
        val target = RendererResolver.resolveControlTarget("") { false }
        assertEquals(OWN, RendererResolver.attributionOf(target, OWN))
    }
}
