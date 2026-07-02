package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DensityController over both privileged paths — su (via RootShell) and daemon (via Daemon) — with no
 * device. Covers density read/parse, bounds enforcement, and font-scale, on the two transports.
 */
class DensityControllerTest {

    // --- su path (canSu = true) ---
    private fun su(outputs: Map<String, String> = emptyMap()): Pair<DensityController, FakeRootShell> {
        val root = FakeRootShell(outputs)
        return DensityController(canSu = true, root = root, daemon = FakeDaemon(available = false)) to root
    }

    @Test fun suReadsPhysicalAndOverrideDensity() {
        val (d, _) = su(mapOf("wm density" to "Physical density: 320\nOverride density: 240"))
        assertEquals(320, d.native())
        assertEquals(240, d.current())
        assertTrue(d.available())
    }

    @Test fun suCurrentFallsBackToPhysicalWhenNoOverride() {
        val (d, _) = su(mapOf("wm density" to "Physical density: 320"))
        assertEquals(320, d.current())
    }

    @Test fun suSetWritesWmDensityWithinBounds() {
        val (d, root) = su()
        assertTrue(d.set(240))
        assertTrue(root.ran.contains("wm density 240"))
    }

    @Test fun suSetRejectsOutOfRange() {
        val (d, root) = su()
        assertFalse(d.set(DensityController.MIN_DPI - 1))
        assertFalse(d.set(DensityController.MAX_DPI + 1))
        assertTrue(root.ran.none { it.startsWith("wm density ") })
    }

    @Test fun suResetWritesReset() {
        val (d, root) = su()
        assertTrue(d.reset())
        assertTrue(root.ran.contains("wm density reset"))
    }

    @Test fun suFontScaleReadsAndBounds() {
        val (d, root) = su(mapOf("font_scale" to "1.25"))
        assertEquals(1.25f, d.fontScale(), 0.001f)
        assertFalse(d.setFontScale(DensityController.MIN_FONT - 0.1f))
        assertTrue(d.setFontScale(1.2f))
        assertTrue(root.ran.any { it.contains("font_scale 1.2") })
    }

    // --- daemon path (canSu = false) ---
    private fun daemon(replies: Map<String, String>): DensityController =
        DensityController(canSu = false, root = FakeRootShell(available = false), daemon = FakeDaemon(replies))

    @Test fun daemonReadsDensityFields() {
        val d = daemon(mapOf("DENSITY" to "PHYS=320 OVER=240"))
        assertEquals(320, d.native())
        assertEquals(240, d.current())
    }

    @Test fun daemonSetSendsCommand() {
        assertTrue(daemon(mapOf("DENSITY 240" to "OK")).set(240))
    }

    @Test fun daemonFontScaleParsesReply() {
        assertEquals(1.25f, daemon(mapOf("FONTSCALE" to "SCALE=1.25")).fontScale(), 0.001f)
    }
}
