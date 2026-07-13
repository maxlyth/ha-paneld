package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Density/font-scale parsing and live helper↔su routing, with no device or privileged process. */
class DensityControllerTest {
    private data class Harness(
        val density: DensityController,
        val root: FakeRootShell,
        val daemon: FakeDaemon,
    )

    private fun controller(
        canSu: Boolean = true,
        rootOutputs: Map<String, String> = emptyMap(),
        rootResult: Boolean = true,
        daemonReplies: Map<String, String> = emptyMap(),
    ): Harness {
        val root = FakeRootShell(outputs = rootOutputs, runResult = rootResult)
        val daemon = FakeDaemon(replies = daemonReplies)
        return Harness(DensityController(canSu = canSu, root = root, daemon = daemon), root, daemon)
    }

    @Test fun suReadsPhysicalAndOverrideDensityWithoutHelper() {
        val h = controller(rootOutputs = mapOf("wm density" to "Physical density: 320\nOverride density: 240"))
        assertEquals(320, h.density.native())
        assertEquals(240, h.density.current())
        assertTrue(h.density.available())
        assertTrue(h.daemon.sent.isEmpty())
    }

    @Test fun currentUsesOneSnapshotAndFallsBackToPhysical() {
        val h = controller(rootOutputs = mapOf("wm density" to "Physical density: 320"))
        assertEquals(320, h.density.current())
        assertEquals(1, h.root.outputRan.count { it.startsWith("wm density") })
    }

    @Test fun suPreferredDensityReadFallsThroughMalformedOutput() {
        val h = controller(
            rootOutputs = mapOf("wm density" to "unexpected"),
            daemonReplies = mapOf("DENSITY" to "PHYS=320 OVER=240"),
        )
        assertEquals(240, h.density.current())
        assertEquals(listOf("DENSITY"), h.daemon.sent)
    }

    @Test fun helperPreferredDensityReadFallsThroughMalformedReply() {
        val h = controller(
            canSu = false,
            rootOutputs = mapOf("wm density" to "Physical density: 320\nOverride density: 240"),
            daemonReplies = mapOf("DENSITY" to "ERR"),
        )
        assertEquals(240, h.density.current())
        assertTrue(h.root.outputRan.any { it.startsWith("wm density") })
    }

    @Test fun helperDensityNoOverrideIsAcceptedState() {
        val h = controller(canSu = false, daemonReplies = mapOf("DENSITY" to "PHYS=320 OVER=-"))
        assertEquals(320, h.density.current())
        assertTrue("accepted helper state short-circuits su", h.root.outputRan.isEmpty())
    }

    @Test fun densityReadFailsWhenNeitherReplyParses() {
        val h = controller(
            canSu = false,
            rootOutputs = mapOf("wm density" to "unexpected"),
            daemonReplies = mapOf("DENSITY" to "PHYS=320 OVER=- trailing"),
        )
        assertNull(h.density.native())
        assertEquals(listOf("DENSITY"), h.daemon.sent)
        assertTrue(h.root.outputRan.any { it.startsWith("wm density") })
    }

    @Test fun densitySetUsesPreferredSuAndEnforcesBounds() {
        val h = controller()
        assertTrue(h.density.set(240))
        assertTrue(h.root.ran.contains("wm density 240"))
        assertTrue(h.daemon.sent.isEmpty())
        assertFalse(h.density.set(DensityController.MIN_DPI - 1))
        assertFalse(h.density.set(DensityController.MAX_DPI + 1))
        assertEquals(1, h.root.ran.size)
    }

    @Test fun densitySetFallsThroughInBothDirections() {
        val suFirst = controller(
            rootResult = false,
            daemonReplies = mapOf("DENSITY 240" to "OK"),
        )
        assertTrue(suFirst.density.set(240))
        assertTrue(suFirst.root.ran.isNotEmpty())
        assertEquals(listOf("DENSITY 240"), suFirst.daemon.sent)

        val helperFirst = controller(
            canSu = false,
            daemonReplies = mapOf("DENSITY 240" to "ERR"),
        )
        assertTrue(helperFirst.density.set(240))
        assertEquals(listOf("DENSITY 240"), helperFirst.daemon.sent)
        assertTrue(helperFirst.root.ran.contains("wm density 240"))
    }

    @Test fun densityResetAndAllRouteFailureAreTruthful() {
        val ok = controller(canSu = false, daemonReplies = mapOf("DENSITY reset" to "OK"))
        assertTrue(ok.density.reset())
        assertTrue(ok.root.ran.isEmpty())

        val failed = controller(
            canSu = false,
            rootResult = false,
            daemonReplies = mapOf("DENSITY reset" to "ERR"),
        )
        assertFalse(failed.density.reset())
        assertEquals(listOf("DENSITY reset"), failed.daemon.sent)
        assertTrue(failed.root.ran.contains("wm density reset"))
    }

    @Test fun fontScaleReadsThroughBothRoutes() {
        val suFirst = controller(
            rootOutputs = mapOf("font_scale" to "bad"),
            daemonReplies = mapOf("FONTSCALE" to "SCALE=1.25"),
        )
        assertEquals(1.25f, suFirst.density.fontScale(), 0.001f)

        val helperFirst = controller(
            canSu = false,
            rootOutputs = mapOf("font_scale" to "1.2"),
            daemonReplies = mapOf("FONTSCALE" to "ERR"),
        )
        assertEquals(1.2f, helperFirst.density.fontScale(), 0.001f)
        assertTrue(helperFirst.root.outputRan.any { it.contains("font_scale") })
    }

    @Test fun unsetFontScaleIsAcceptedDefaultNotFailure() {
        val helper = controller(canSu = false, daemonReplies = mapOf("FONTSCALE" to "SCALE=null"))
        assertEquals(1.0f, helper.density.fontScale(), 0.001f)
        assertTrue(helper.root.outputRan.isEmpty())

        val root = controller(rootOutputs = mapOf("font_scale" to "null"))
        assertEquals(1.0f, root.density.fontScale(), 0.001f)
        assertTrue(root.daemon.sent.isEmpty())
    }

    @Test fun fontScaleDefaultsOnlyAfterBothReadsFail() {
        val h = controller(
            canSu = false,
            rootOutputs = mapOf("font_scale" to "NaN"),
            daemonReplies = mapOf("FONTSCALE" to "SCALE=oops"),
        )
        assertEquals(1.0f, h.density.fontScale(), 0.001f)
        assertEquals(listOf("FONTSCALE"), h.daemon.sent)
        assertTrue(h.root.outputRan.any { it.contains("font_scale") })
    }

    @Test fun fontScaleWriteFallsThroughAndRejectsNonFiniteValues() {
        val h = controller(
            canSu = false,
            daemonReplies = mapOf("FONTSCALE 1.2" to "ERR"),
        )
        assertTrue(h.density.setFontScale(1.2f))
        assertEquals(listOf("FONTSCALE 1.2"), h.daemon.sent)
        assertTrue(h.root.ran.any { it.contains("font_scale 1.2") })

        val calls = h.root.ran.size to h.daemon.sent.size
        assertFalse(h.density.setFontScale(Float.NaN))
        assertFalse(h.density.setFontScale(Float.POSITIVE_INFINITY))
        assertFalse(h.density.setFontScale(DensityController.MIN_FONT - 0.1f))
        assertFalse(h.density.setFontScale(DensityController.MAX_FONT + 0.1f))
        assertEquals(calls, h.root.ran.size to h.daemon.sent.size)
    }

    @Test fun fontScaleResetReportsAllRouteFailure() {
        val h = controller(
            rootResult = false,
            daemonReplies = mapOf("FONTSCALE reset" to "ERR"),
        )
        assertFalse(h.density.resetFontScale())
        assertTrue(h.root.ran.any { it.contains("delete system font_scale") })
        assertEquals(listOf("FONTSCALE reset"), h.daemon.sent)
    }

    @Test fun compositeDisplayResultRequiresEveryRequestedEffect() {
        assertTrue(DensityController.allApplied(true))
        assertTrue(DensityController.allApplied(true, null))
        assertTrue(DensityController.allApplied(true, true))
        assertFalse(DensityController.allApplied())
        assertFalse(DensityController.allApplied(null, null))
        assertFalse(DensityController.allApplied(true, false))
        assertFalse(DensityController.allApplied(false, true))
    }
}
