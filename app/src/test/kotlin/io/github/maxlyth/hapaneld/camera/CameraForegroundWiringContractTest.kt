package io.github.maxlyth.hapaneld.camera

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android side of the foreground promotion bookkeeping cannot run on the JVM, so its wiring is
 * pinned by source text, the same treatment the capture callback and processing wiring have. Each
 * assertion is one way the process died, or would have, on an Android 14 panel on 2026-09-01. The
 * interleavings themselves are decided in `CameraForegroundPromotionsTest`; this proves the service
 * and the gate route every Android call through that registry, and that no teardown in the session
 * owner can release standing without naming the attempt whose standing it is.
 */
class CameraForegroundWiringContractTest {

    private val service by lazy { TestSources.kotlin("camera/CameraForegroundService.kt").readText() }
    private val owner by lazy { TestSources.kotlin("camera/CameraSessionOwner.kt").readText() }

    private fun body(source: String, name: String): String {
        val start = source.indexOf(name)
        assertTrue("$name is present", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced $name")
    }

    private fun serviceBody(name: String) = body(service, name)

    @Test fun everyStartCallsStartForegroundBeforeItCanStopItself() {
        val start = serviceBody("override fun onStartCommand")
        val foreground = start.indexOf("startForeground(")
        val stop = start.indexOf("stopSelfResult(startId)")
        assertTrue("startForeground is called on every start", foreground >= 0)
        assertTrue("a start that stops itself does so only after startForeground", stop < 0 || stop > foreground)
        assertFalse("no stop ignores its startId: a newer start would be brought down unanswered", "stopSelf()" in service)
        assertTrue("the instance records that it served a start before anything else", start.indexOf("served = true") in 0 until foreground)
    }

    @Test fun theStartAnswersThePromotionsAndStaysOnlyWhenTheyKeepIt() {
        val start = serviceBody("override fun onStartCommand")
        assertTrue("the outcome of startForeground is what answers the promotion", "promotions.started(outcome.isSuccess)" in start)
        assertTrue("a start nobody wants stops itself, naming its own startId", "if (!keep) stopSelfResult(startId)" in start)
    }

    @Test fun aDestroyReportsWhetherTheInstanceServedAStart() {
        assertTrue("promotions.destroyed(served)" in serviceBody("override fun onDestroy"))
    }

    @Test fun aDemoteReleasesInItsOwnersNameFromInsideTheRelease() {
        val demote = serviceBody("override fun demote(owner: Long)")
        val release = demote.indexOf("promotions.release(owner)")
        val stop = demote.indexOf("stopService()")
        assertTrue("the release names the owner whose standing it is", release >= 0)
        assertTrue("the stop is the release's action, under the registry lock", stop > release)
        assertTrue("stopping outright is the only unconditional release", "promotions.releaseAll" in serviceBody("override fun demoteAll"))
    }

    @Test fun aPromoteIssuesItsStartInsideTheRequestAndWaitsThroughTheRegistry() {
        val promote = serviceBody("override fun promote(owner: Long, timeoutMs: Long)")
        val request = promote.indexOf("promotions.request(owner) {")
        val start = promote.indexOf("startForegroundService(")
        assertTrue("the start is the request's action, under the registry lock and in the owner's name", request >= 0 && start > request)
        assertTrue("the wait and its timeout are the registry's decision", "promotions.await(promotion, timeoutMs)" in promote)
        assertFalse("the gate never reads the promotion's answer around the registry", "future" in promote)
        assertFalse("the gate never stops the service from promote", "stopService(" in promote)
    }

    /**
     * The revision-3 hold: `finishAttempt` demoted without saying whose standing it was releasing, so
     * an ended session tearing down after the next one had promoted took the live session's service
     * down with it. Standing is now released through the attempt that promoted it, which no call site
     * can get wrong, and the one unconditional release is the subsystem stopping outright.
     */
    @Test fun everySessionTeardownReleasesStandingInItsOwnAttemptsName() {
        assertFalse("no teardown releases standing without naming an owner", "foreground.demote()" in owner)
        assertEquals(
            "the attempt is the only thing that releases a session's standing",
            1,
            Regex("foreground\\.demote\\(").findAll(owner).count(),
        )
        assertTrue("and it releases its own", "fun releaseStanding() = foreground.demote(id)" in body(owner, "private inner class Attempt"))
        assertTrue("standing is asked for in the attempt's name", "foreground.promote(attempt.id, FOREGROUND_WAIT_MS)" in owner)
        assertEquals(
            "only one path releases standing unconditionally",
            1,
            Regex("foreground\\.demoteAll\\(\\)").findAll(owner).count(),
        )
        assertTrue(
            "and it is the subsystem stopping, not a session ending",
            "if (stopping) foreground.demoteAll() else attempt?.releaseStanding()" in body(owner, "private fun finishAttempt"),
        )
    }
}
