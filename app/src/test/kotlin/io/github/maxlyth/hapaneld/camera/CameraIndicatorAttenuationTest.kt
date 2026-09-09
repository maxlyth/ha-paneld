package io.github.maxlyth.hapaneld.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera light's schedule, asserted where it is decided rather than watched for on a panel.
 *
 * This is the privacy-critical half of the indicator. The geometry test pins what the arc looks like;
 * this pins what it *does* over a session that keeps running, and above all that the flash it settles
 * into is still a real flash. At rest the blip IS the indication - the gap between blips is transparent
 * - so the two things that must never drift toward nothing are its length and its opacity. Several
 * assertions below exist only to make an implementation that shortened or faded the blip fail loudly.
 */
class CameraIndicatorAttenuationTest {

    private val A = CameraIndicatorAttenuation

    /** End of the ramp: the point past which nothing changes again. */
    private val settled = A.PROMINENT_MS + A.RAMP_MS

    /** A spread of session ages covering both phases and well past the end of the schedule. */
    private fun ages(): List<Long> =
        (0..600).map { it * 1_000L } + listOf(1L, A.PROMINENT_MS - 1, A.PROMINENT_MS + 1, settled - 1, settled + 1, 86_400_000L)

    // ---- the first half-minute is exactly the light that shipped before -----------------------------

    @Test fun theFirstThirtySecondsAreTheLightWeAlreadyHad() {
        // Pinned as literals, not as comparisons against the constants they come from: a derived
        // assertion is a tautology that cannot fail when the constant moves.
        assertEquals("full prominence lasts half a minute", 30_000L, A.PROMINENT_MS)
        listOf(0L, 1L, 15_000L, A.PROMINENT_MS).forEach { age ->
            assertEquals("the flash is fully opaque while prominent", 1.0f, A.alphaAt(age, lit = true), 0.0001f)
            assertEquals("the level between flashes is the one we shipped", 0.42f, A.alphaAt(age, lit = false), 0.0001f)
            assertEquals("one flash a second while prominent", 1_000L, A.periodMsAt(age))
            assertEquals(500L, A.gapMsAt(age))
        }
    }

    @Test fun theScheduleFailsProminentRatherThanFadedIfTheClockMisbehaves() {
        // A clock that runs backwards, or a reset that lands a moment late, must not present as a
        // session that has already faded. Nothing here may be more attenuated than the start.
        listOf(-1L, -1_000L, Long.MIN_VALUE / 2).forEach { age ->
            assertEquals(0f, A.progressAt(age), 0.0001f)
            assertEquals(1.0f, A.alphaAt(age, lit = true), 0.0001f)
            assertEquals(0.42f, A.alphaAt(age, lit = false), 0.0001f)
        }
    }

    // ---- the floor: the clause everything else rests on ---------------------------------------------

    @Test fun theFlashIsNeverTransparentAtAnyPointInAnySession() {
        // At rest the blip is the entire indication, so this is the assertion the feature stands on.
        assertEquals("the floor the flash settles to is pinned", 0.62f, A.LIT_FLOOR, 0.0001f)
        assertTrue("a flash that fades toward nothing is not an indication", A.LIT_FLOOR > 0.5f)
        ages().forEach { age ->
            val flash = A.alphaAt(age, lit = true)
            assertTrue("at ${age}ms the flash had faded to $flash, below its floor", flash >= A.LIT_FLOOR - 0.0001f)
            assertTrue("at ${age}ms the flash was transparent", flash > 0f)
        }
    }

    @Test fun theGapIsAllowedToReachTransparentBecauseTheFlashIsWhatIndicates() {
        // Deliberate, and the opposite of the flash rule: holding a dim arc for fifty-nine of every
        // sixty seconds is the standing prominence this feature exists to remove.
        assertEquals("the gap fades out completely", 0.0f, A.GAP_FLOOR, 0.0001f)
        assertEquals(0.0f, A.alphaAt(settled, lit = false), 0.0001f)
        // It still opens at the shipped never-blank level; only the settled state goes to nothing.
        assertEquals(0.42f, A.alphaAt(0L, lit = false), 0.0001f)
        assertTrue("the gap must fade rather than blanking the moment attenuation starts",
            A.alphaAt(A.PROMINENT_MS + A.RAMP_MS / 2, lit = false) > 0.15f)
    }

    @Test fun theFloorIsReachedAndThenNothingChangesAgain() {
        assertEquals(A.GAP_FLOOR, A.alphaAt(settled, lit = false), 0.0001f)
        assertEquals(A.LIT_FLOOR, A.alphaAt(settled, lit = true), 0.0001f)
        listOf(settled + 1_000L, settled + 3_600_000L, 86_400_000L).forEach { age ->
            assertEquals("the schedule settles rather than continuing to fade", A.GAP_FLOOR, A.alphaAt(age, lit = false), 0.0001f)
            assertEquals("the flash must not keep dimming after the ramp ends", A.LIT_FLOOR, A.alphaAt(age, lit = true), 0.0001f)
            assertEquals(A.MAX_GAP_MS, A.gapMsAt(age))
        }
    }

    @Test fun theSettledFlashIsStillVisiblyAFlashAgainstWhatSurroundsIt() {
        assertTrue(
            "a flash that settles to the same level as the gap is not a flash any more",
            A.LIT_FLOOR - A.GAP_FLOOR > 0.5f,
        )
        // Both floors descend from the pulse's own two levels, which is where they are asserted.
        assertTrue("the gap floor must stay below the level it descends from", A.GAP_FLOOR < CameraIndicatorPulse.DIM)
        assertTrue("the flash floor must stay below the level it descends from", A.LIT_FLOOR < CameraIndicatorPulse.BRIGHT)
    }

    // ---- the asymmetry: the lit part keeps its length, only the gap stretches ------------------------

    @Test fun theLitPartOfTheCycleNeverShortens() {
        assertEquals("the flash is half a second, at every age", 500L, A.LIT_MS)
        ages().forEach { age ->
            assertEquals(
                "at ${age}ms the flash had shortened; a shorter flash eventually reads as nothing",
                A.LIT_MS,
                A.periodMsAt(age) - A.gapMsAt(age),
            )
            assertEquals(A.LIT_MS, A.stepAfter(age, wasLit = false).holdMs)
        }
    }

    @Test fun theGapStretchesFromOneASecondToOneAMinute() {
        assertEquals("the gap starts at half a second", 500L, A.MIN_GAP_MS)
        assertEquals("with the flash, the settled cycle is exactly one minute", 59_500L, A.MAX_GAP_MS)
        assertEquals("one flash a minute once it settles", 60_000L, A.periodMsAt(settled))
        assertEquals(1_000L, A.periodMsAt(0))
    }

    @Test fun theGapOnlyEverGrowsAndStaysInsideItsBounds() {
        var previous = 0L
        ages().sorted().forEach { age ->
            val gap = A.gapMsAt(age)
            assertTrue("at ${age}ms the gap shrank from $previous to $gap", gap >= previous)
            assertTrue("at ${age}ms the gap was below its floor", gap >= A.MIN_GAP_MS)
            assertTrue("at ${age}ms the gap exceeded one minute", gap <= A.MAX_GAP_MS)
            previous = gap
        }
    }

    @Test fun bothLevelsOnlyEverDescend() {
        var lastLit = Float.MAX_VALUE
        var lastGap = Float.MAX_VALUE
        ages().sorted().forEach { age ->
            val litAlpha = A.alphaAt(age, lit = true)
            val gapAlpha = A.alphaAt(age, lit = false)
            assertTrue("at ${age}ms the flash brightened again", litAlpha <= lastLit + 0.0001f)
            assertTrue("at ${age}ms the gap brightened again", gapAlpha <= lastGap + 0.0001f)
            assertTrue("the flash must always outshine the gap", litAlpha > gapAlpha)
            lastLit = litAlpha
            lastGap = gapAlpha
        }
    }

    @Test fun theGapBacksOffGeometricallySoItStaysFrequentWhileSomebodyMightStillBeReacting() {
        val halfway = A.PROMINENT_MS + A.RAMP_MS / 2
        val linearMidpoint = (A.MIN_GAP_MS + A.MAX_GAP_MS) / 2
        assertTrue(
            "halfway through the ramp the light must still be flashing often, not already half a minute apart",
            A.gapMsAt(halfway) < linearMidpoint / 4,
        )
        // A minute into the session the light is still flashing several times a minute.
        assertTrue("a minute in, the light should still be frequent", A.periodMsAt(60_000L) < 10_000L)
        assertNotEquals("the ramp must actually move by halfway", A.MIN_GAP_MS, A.gapMsAt(halfway))
    }

    @Test fun progressIsZeroThroughProminenceAndOneFromTheEndOfTheRamp() {
        assertEquals(120_000L, A.RAMP_MS)
        assertEquals(0f, A.progressAt(A.PROMINENT_MS), 0.0001f)
        assertTrue("the ramp must start moving immediately after prominence", A.progressAt(A.PROMINENT_MS + 1) > 0f)
        assertEquals(0.5f, A.progressAt(A.PROMINENT_MS + A.RAMP_MS / 2), 0.0001f)
        assertEquals(1f, A.progressAt(settled), 0.0001f)
        assertEquals("progress is capped, so nothing keeps fading past the floor", 1f, A.progressAt(settled * 10), 0.0001f)
    }

    // ---- the cycle itself, folded out as the timeline a person would watch --------------------------

    @Test fun theCycleAlternatesAndStartsWithAFullyOpaqueFlash() {
        val first = A.stepAfter(0L, wasLit = false)
        assertTrue("a session must open lit, not on the gap level", first.lit)
        assertEquals(1.0f, first.alpha, 0.0001f)
        assertEquals(A.LIT_MS, first.holdMs)

        val second = A.stepAfter(A.LIT_MS, wasLit = true)
        assertFalse(second.lit)
        assertEquals(0.42f, second.alpha, 0.0001f)
        assertEquals(A.MIN_GAP_MS, second.holdMs)
    }

    /**
     * The whole visible session, folded from the pure step. This is what the panel actually shows, so
     * the properties that matter to a person in the room are asserted against it directly rather than
     * against the functions underneath it.
     */
    private fun timeline(durationMs: Long): List<Pair<Long, CameraIndicatorAttenuation.Step>> {
        val out = mutableListOf<Pair<Long, CameraIndicatorAttenuation.Step>>()
        var at = 0L
        var lit = false
        while (at <= durationMs) {
            val step = A.stepAfter(at, lit)
            out += at to step
            lit = step.lit
            at += step.holdMs
        }
        return out
    }

    @Test fun aTenMinuteSessionOpensUnmistakableAndSettlesIntoADimArcThatFlashesAboutOnceAMinute() {
        val session = timeline(600_000L)

        val firstMinute = session.filter { it.first < 60_000L }
        val flashesInTheFirstThirtySeconds = session.count { it.first < A.PROMINENT_MS && it.second.lit }
        assertTrue("the first half-minute must be unmistakable: about thirty flashes", flashesInTheFirstThirtySeconds >= 25)
        assertTrue(
            "every flash in the prominent phase is fully opaque",
            firstMinute.filter { it.first <= A.PROMINENT_MS && it.second.lit }.all { it.second.alpha == 1.0f },
        )

        val lastTwoMinutes = session.filter { it.first > 480_000L }
        val settledFlashes = lastTwoMinutes.count { it.second.lit }
        assertTrue("a settled session should flash about twice in two minutes, not constantly", settledFlashes in 1..3)
        assertTrue(
            "every settled flash must still arrive at its full floor opacity",
            lastTwoMinutes.filter { it.second.lit }.all { it.second.alpha == A.LIT_FLOOR },
        )
        assertTrue(
            "between settled flashes the screen carries no indicator at all, by design",
            lastTwoMinutes.filter { !it.second.lit }.all { it.second.alpha == 0.0f },
        )
    }

    @Test fun theScheduleGetsCheaperAsItRunsRatherThanCostingTheSameForHours() {
        // The cost argument in the KDoc is a commitment, not a remark: a session that has settled must
        // issue dramatically fewer layer updates per minute than one that just opened.
        val session = timeline(600_000L)
        val updatesInTheFirstMinute = session.count { it.first < 60_000L }
        val updatesInTheTenthMinute = session.count { it.first in 540_000L until 600_000L }
        assertTrue("a prominent session updates about twice a second", updatesInTheFirstMinute >= 50)
        assertTrue("a settled session must cost almost nothing", updatesInTheTenthMinute <= 6)
    }

    // ---- what restores full prominence ---------------------------------------------------------------

    @Test fun theDisplayComingBackRestoresFullProminenceAndNothingElseAboutTheScreenDoes() {
        assertTrue("the room has seen nothing while the screen was dark", A.restartsForScreen(wasDark = true, nowDark = false))
        assertFalse("going dark is not a discontinuity: the LED indicates then", A.restartsForScreen(wasDark = false, nowDark = true))
        assertFalse("a screen that stayed lit has been showing the light all along", A.restartsForScreen(wasDark = false, nowDark = false))
        assertFalse("a screen that is still dark has not come back yet", A.restartsForScreen(wasDark = true, nowDark = true))
    }

    @Test fun everyOpeningOfTheCameraRestoresFullProminenceIncludingOneInsideTheHold() {
        assertTrue("a new hardware session is unmistakable", A.restartsForOpen(alreadyAttached = false))
        assertTrue(
            "a reopen inside the post-close hold is still a new opening of the camera, and so is a reopen after a fault",
            A.restartsForOpen(alreadyAttached = true),
        )
    }
}
