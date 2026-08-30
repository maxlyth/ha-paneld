package io.github.maxlyth.hapaneld.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera light's shape and movement, asserted where they are decided rather than in a photograph.
 *
 * Both camera panels centre the lens on the top bezel, so the indicator is the lowest third of a circle
 * whose centre sits *above* the screen edge — on screen it reads as an arc curving under the lens. The
 * numbers below are the ones a person can see, so they are pinned: get the centre offset wrong and the
 * arc becomes a half-disc or a sliver, and nothing else in the suite would notice.
 */
class CameraIndicatorGeometryTest {

    @Test fun theCircleIsThreeTabBarsLessTheMargin() {
        assertEquals(56, CameraIndicatorGeometry.TAB_BAR_PX)
        assertEquals(8, CameraIndicatorGeometry.MARGIN_PX)
        assertEquals(
            "the diameter is three tab bars minus the margin",
            56 * 3 - 8,
            CameraIndicatorGeometry.DIAMETER_PX,
        )
        assertEquals(160, CameraIndicatorGeometry.DIAMETER_PX)
    }

    @Test fun theWindowIsAsWideAsTheCircleAndAsTallAsTheVisibleBand() {
        assertEquals(
            "the window spans the circle's full width, or the arc's ends would be clipped",
            CameraIndicatorGeometry.DIAMETER_PX,
            CameraIndicatorGeometry.windowWidthPx,
        )
        assertEquals(
            "the window is exactly the visible band, so the arc meets the screen edge",
            Math.round(160 * 0.33f),
            CameraIndicatorGeometry.windowHeightPx,
        )
        assertEquals(53, CameraIndicatorGeometry.windowHeightPx)
    }

    @Test fun theCircleCentreSitsAboveTheScreenEdgeSoOnlyTheBottomThirdShows() {
        val r = CameraIndicatorGeometry.radiusPx
        val cy = CameraIndicatorGeometry.centreY()
        assertTrue("the centre must be above the screen edge, not on or below it: $cy", cy < 0f)

        // What the room sees: from the window's top edge (y = 0) down to the bottom of the circle.
        val visible = (cy + r) - 0f
        assertEquals(
            "the visible band is the bottom third of the circle",
            CameraIndicatorGeometry.DIAMETER_PX * CameraIndicatorGeometry.VISIBLE_FRACTION,
            visible,
            0.5f,
        )
        // A half-disc is the shape this deliberately is NOT; that would put the centre exactly on 0.
        assertTrue("the bottom third is shorter than a half-disc would be", visible < r)
    }

    @Test fun theArcIsCentredAndInsetByTheStrokeSoTheOutlineIsNotClipped() {
        assertEquals(80f, CameraIndicatorGeometry.centreX(160), 0.01f)
        val stroke = 4f
        assertEquals(
            "the radius is inset by half the stroke, so the white ring stays inside the window",
            CameraIndicatorGeometry.radiusPx - stroke / 2f,
            CameraIndicatorGeometry.radius(stroke),
            0.01f,
        )
        assertTrue(CameraIndicatorGeometry.radius(stroke) < CameraIndicatorGeometry.radiusPx)
    }

    /**
     * The pulse is two stepped levels once a second. Cost is why: a stepped pulse is two layer updates
     * a second, while an animator on alpha redraws at the display rate — about 41% of a core, charged
     * for as long as the camera is open. These assertions pin the cheap shape, not the look.
     */
    @Test fun thePulseIsTwoSteppedLevelsASecond() {
        assertEquals(1_000L, CameraIndicatorPulse.PERIOD_MS)
        assertEquals(
            "two steps per period, so the light changes twice a second and no more",
            500L,
            CameraIndicatorPulse.STEP_MS,
        )
        assertEquals(2L, CameraIndicatorPulse.PERIOD_MS / CameraIndicatorPulse.STEP_MS)
    }

    @Test fun theDimLevelIsStillVisibleBecauseABlinkWouldNotBe() {
        assertEquals(1.0f, CameraIndicatorPulse.BRIGHT, 0.001f)
        assertTrue(
            "the dim level must stay visible: an indication that disappears is not an indication",
            CameraIndicatorPulse.DIM > 0.2f,
        )
        assertTrue("the dim level must be visibly dimmer than bright", CameraIndicatorPulse.DIM < 0.8f)
    }

    @Test fun theStepSequenceAlternatesAndStartsBright() {
        assertEquals(CameraIndicatorPulse.BRIGHT, CameraIndicatorPulse.alphaAtStep(0), 0.001f)
        assertEquals(CameraIndicatorPulse.DIM, CameraIndicatorPulse.alphaAtStep(1), 0.001f)
        assertEquals(CameraIndicatorPulse.BRIGHT, CameraIndicatorPulse.alphaAtStep(2), 0.001f)
        // It must keep alternating far from zero, not drift or saturate as the session runs on.
        assertEquals(CameraIndicatorPulse.BRIGHT, CameraIndicatorPulse.alphaAtStep(86_400), 0.001f)
        assertEquals(CameraIndicatorPulse.DIM, CameraIndicatorPulse.alphaAtStep(86_401), 0.001f)
    }
}
