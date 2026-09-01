package io.github.maxlyth.hapaneld.camera

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's frame delivery is Android-bound, so how it paces is pinned by source text. The defect this
 * guards against was measured, not imagined: a bare "at least one interval since the last delivery"
 * comparison ahead of the pacer delivered ten frames of a fifteen cap and twenty of a thirty cap on both
 * camera boards, because a 33.3 ms sensor frame landing a millisecond early failed it and the next one
 * came a whole sensor frame later. `FramePacerTest` proves the pacer holds the cap on that cadence; this
 * test proves the owner actually uses it, and uses it once.
 */
class CameraDeliveryPacingContractTest {

    private val owner by lazy { TestSources.kotlin("camera/CameraSessionOwner.kt").readText() }

    private fun onFrame(): String {
        val start = owner.indexOf("private fun onFrame(")
        assertTrue("onFrame is present", start >= 0)
        return owner.substring(start, owner.indexOf("\n    }\n", start))
    }

    @Test fun deliveryIsPacedByTheFramePacerNotABareIntervalComparison() {
        val body = onFrame()
        assertTrue("delivery goes through the pacer", "deliveryPacer.admit(now)" in body)
        assertFalse("no bare interval comparison remains ahead of it", "lastDeliveredAtMs" in owner)
        assertFalse("no bare interval comparison remains ahead of it", "< interval" in body)
    }

    @Test fun theDeliveryPacerIsBoundToTheSessionsRateWhenTheSessionOpens() {
        val bind = owner.indexOf("deliveryPacer = FramePacer(boundFps)")
        val open = owner.indexOf("private fun beginOpenLocked(")
        assertTrue("the pacer is rebuilt for the bound rate", bind > open && open >= 0)
        assertTrue("inside beginOpenLocked", bind < owner.indexOf("\n    }\n", open))
    }

    @Test fun theEncoderPacesItselfOnlyWhenItsStreamRunsSlowerThanTheSession() {
        assertTrue("a second pacer at the session's own rate would drop frames the first had already spaced",
            "encoderPacer = if (fps < boundFps) FramePacer(fps) else null" in owner)
        assertTrue("an encoder without its own pacer is fed every delivered frame",
            "encoder?.takeIf { encoderPacer?.admit(now) ?: true }" in onFrame())
    }
}
