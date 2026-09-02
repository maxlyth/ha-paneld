package io.github.maxlyth.hapaneld.camera

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two owner properties that no JVM test can drive, because the session owner needs a camera
 * device, a Looper and Android permissions: that the master switch's reset is taken under the same
 * lock that stores it, and that a lease's gate is observed and applied without a gap.
 *
 * Everything else about the reset is behavioural and lives where it can be executed —
 * `CameraSessionStateTest` drives the real state machine for what a session still refuses, and
 * `CameraPresentationTest` proves the decision. This file is deliberately small: source text is
 * secondary evidence and is used only where the alternative is no evidence at all.
 *
 * The interleaving it exists for was found in review of the first revision: the gate was read in one
 * critical section and applied in another, so a consumer that had already seen the switch as off could
 * store `camera-disabled` over the enable's reset, putting the refusal back on a camera that was by
 * then on.
 */
class CameraOutcomeResetContractTest {

    private val owner by lazy { TestSources.kotlin("camera/CameraSessionOwner.kt").readText() }

    private fun body(name: String): String {
        val start = owner.indexOf(name)
        assertTrue("$name is present", start >= 0)
        val open = owner.indexOf('{', start)
        var depth = 0
        for (i in open until owner.length) {
            when (owner[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return owner.substring(open, i + 1)
            }
        }
        error("unbalanced $name")
    }

    /** The switch is re-read under the storing lock, and the session is asked worst-first. */
    @Test fun theEnableTakesItsResetUnderTheSameLockThatStoresIt() {
        val enable = body("fun onEnabledChanged")
        val guarded = enable.indexOf("synchronized(lock) {\n                if (enabled()) {")
        assertTrue("the switch is re-read inside the lock that stores the outcome", guarded >= 0)
        val retained = enable.indexOf("state.retainedRefusal(now, LeaseKind.SNAPSHOT)")
        val fallback = enable.indexOf("?: state.retainedRefusal(now, LeaseKind.STREAM)")
        assertTrue("the session decides what still refuses, not a copy of its rules", retained > guarded)
        assertTrue("worst first: a snapshot blocker outranks a stream-only one", fallback > retained)
        assertTrue("and the decision is applied to the stored outcome", enable.indexOf("outcome = CameraOutcome.onEnable(outcome, retained)") > fallback)
        assertTrue("the disable branch still stamps the switch's own refusal", "if (disabled) outcome = CameraRefusal.DISABLED.token" in enable)
        assertFalse("the enable branch never claims a frame", "lastFrameAtMs" in enable)
    }

    /**
     * One critical section for the gate and its application. Two of them is the defect: an enable
     * landing in the gap lets a stale `camera-disabled` be stored over the reset.
     */
    @Test fun aLeasesGateIsObservedAndAppliedInOneCriticalSection() {
        val acquire = body("private fun acquireLease")
        // The FIRST critical section, taken whole. A later one legitimately exists to release a lease
        // whose open was refused, after the wait; that one is not the gate and must not mask this.
        val section = balanced(acquire, acquire.indexOf("synchronized(lock) {"))
        val switch = section.indexOf("!enabled() -> CameraRefusal.DISABLED")
        val apply = section.indexOf("state.acquire(gate, nowMs(), kind, binding)")
        assertTrue("the switch is read inside the first critical section", switch >= 0)
        assertTrue("and applied inside that same one, with no gap for an enable to land in", apply > switch)
        assertFalse("the gate is never computed in a section of its own", "val gate = synchronized(lock)" in acquire)
    }

    /** Ending the session must not erase a hold which still refuses the next consumer. */
    @Test fun closingTheLastLeaseRestatesAnyRetainedRefusal() {
        val close = body("override fun close")
        val release = close.indexOf("release = state.release(id)")
        val snapshot = close.indexOf("state.retainedRefusal(now, LeaseKind.SNAPSHOT)")
        val stream = close.indexOf("?: state.retainedRefusal(now, LeaseKind.STREAM)")
        val store = close.indexOf("outcome = retained?.token ?: CameraOutcome.OK")
        assertTrue("release happens first", release >= 0)
        assertTrue("snapshot-wide refusal is checked after release", snapshot > release)
        assertTrue("stream-only refusal is the fallback", stream > snapshot)
        assertTrue("the retained refusal is stored instead of unconditional ok", store > stream)
    }

    /** The balanced `{...}` block starting at [from], so a later section cannot be read as this one. */
    private fun balanced(source: String, from: Int): String {
        val open = source.indexOf('{', from)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced block")
    }

    /** The other reset boundary: a session opening for a client earns `ok` outright. */
    @Test fun aSuccessfulOpenResetsTheOutcomeAndTheFault() {
        assertTrue(
            "the open, not the frame, is what clears a refusal for an admitted viewer",
            "if (became) { outcome = \"ok\"; fault = CameraFault.NONE; faultDetail = null; recovery = \"none\" }" in body("private fun configure("),
        )
        assertTrue(
            "the last lease leaves the active retained refusal, or ok when nothing remains",
            "outcome = retained?.token ?: CameraOutcome.OK" in body("inner class Lease"),
        )
    }

    /** A missing permission wins over the stored outcome, so the reset can never present as granted. */
    @Test fun thePresentationOrdersTheSwitchThenThePermissionBeforeTheStoredOutcome() {
        val presentation = body("override fun presentation()")
        val disabled = presentation.indexOf("!enabled() -> CameraPresentation.disabled()")
        val permission = presentation.indexOf("!permissionGranted() -> CameraPresentation.permissionNeeded(streamPort = facts.port)")
        val current = presentation.indexOf("else -> current(facts, address)")
        assertTrue("off is the switch's state whatever the outcome says", disabled >= 0)
        assertTrue("then the permission, whose token the reset never stamps", permission > disabled)
        assertTrue("and only then the stored outcome", current > permission)
    }
}
