package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingUploadStoreTest {
    @Test
    fun stagedUploadExpiresBeforeCommit() {
        var now = 1_000L
        val store = PendingUploadStore(monotonicMs = { now }, ttlMs = 100L, newToken = { "token" }).apply { open() }
        val lease = granted(store.begin())
        val staged = file("expired.apk")
        store.stage(lease, staged)
        now += 100L

        assertNull(store.claim("token"))
        assertFalse(staged.exists())
        assertTrue(store.begin() is PendingUploadStore.BeginResult.Granted)
    }

    @Test
    fun backwardClockExpiresStagedUploadAndDeletesIt() {
        var now = 1_000L
        val store = PendingUploadStore(monotonicMs = { now }, ttlMs = 100L, newToken = { "token" }).apply { open() }
        val lease = granted(store.begin())
        val staged = file("backward-clock.apk")
        store.stage(lease, staged)

        now = 999L

        assertNull(store.peek("token"))
        assertFalse(staged.exists())
        assertTrue(store.begin() is PendingUploadStore.BeginResult.Granted)
    }

    @get:Rule val temporary = TemporaryFolder()

    private fun file(name: String) = temporary.newFile(name).apply { writeText(name) }

    /** A reservation may supersede the operator's own previous intent, but never a body still arriving
     *  from a client — the panel cannot retract someone else's upload stream. A superseded entry must
     *  be genuinely dead: its token stops resolving and its file is deleted, so nothing can later be
     *  installed under it. */
    /** Panel-side work is bound to the reservation that started it, so every way a reservation ends
     *  also ends the work. Before this was folded into the store, a disable, a shutdown and a replacing
     *  request each left a download running against a panel that had already moved on. */
    @Test fun everyWayAReservationEndsAlsoStopsThePanelWorkBoundToIt() {
        fun freshStoreWithWork(): Pair<PendingUploadStore, io.github.maxlyth.hapaneld.util.DownloadAbort> {
            val store = PendingUploadStore { "token" }.apply { open() }
            val admitted = store.begin("req") as PendingUploadStore.BeginResult.Granted
            return store to admitted.abort!!
        }

        val (disabled, disabledWork) = freshStoreWithWork()
        disabled.clear()
        assertTrue("disabling the capability must stop an in-flight fetch", disabledWork.isAborted)

        val (stopped, stoppedWork) = freshStoreWithWork()
        stopped.close()
        assertTrue("shutting down must stop an in-flight fetch", stoppedWork.isAborted)

        val (restarted, restartedWork) = freshStoreWithWork()
        restarted.open()
        assertTrue("a restarted server must not leave the old fetch running", restartedWork.isAborted)

        val (replaced, replacedWork) = freshStoreWithWork()
        replaced.begin()
        assertTrue("a replacing request must retire the fetch it supersedes", replacedWork.isAborted)

        val (released, releasedWork) = freshStoreWithWork()
        released.abort(granted(released.begin()))
        assertTrue("releasing the reservation must stop its work", releasedWork.isAborted)
    }

    /** The race that matters: bytes can finish arriving while the operator is cancelling. Aborting the
     *  transfer is then too late, so cancellation has to decide the outcome — no token may exist, at
     *  any timing, once the operator has said stop. */
    @Test fun aCancelledReservationCannotStageEvenIfItsBytesAlreadyArrived() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val lease = granted(store.begin("mine"))

        assertTrue(store.cancelPanelWork("mine"))
        assertTrue("the refusal must be attributable to the cancel", store.isCancelled(lease))

        val completed = file("arrived-anyway.apk")
        assertNull("a cancelled reservation must never mint a token", store.stage(lease, completed))
        assertFalse("and its bytes must not survive", completed.exists())
    }

    /** A cancel may only stop the request that started the work, never a replacement. */
    @Test fun panelWorkIsCancellableOnlyByTheRequestThatOwnsIt() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val admitted = store.begin("mine") as PendingUploadStore.BeginResult.Granted
        val abort = admitted.abort!!

        assertFalse("a foreign request must not cancel", store.cancelPanelWork("someone-else"))
        assertFalse(abort.isAborted)
        assertTrue(store.cancelPanelWork("mine"))
        assertTrue(abort.isAborted)
    }

    /** The race the third review found: the owner used to be recorded AFTER the slot was taken, so a
     *  cancel arriving in between found nobody, reported that it had cancelled nothing, and the fetch
     *  went on to complete and publish a token. There is deliberately no attachment step to race with
     *  now — the very first thing a caller can do after reserving is cancel, and it must stick. */
    @Test fun aCancelImmediatelyAfterReservationIsHonouredWithNoAttachmentStepToRace() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val admitted = store.begin("mine") as PendingUploadStore.BeginResult.Granted

        assertNotNull("panel-side work must own an abort from the instant it is admitted", admitted.abort)
        assertTrue("a cancel with no intervening step must be recognised", store.cancelPanelWork("mine"))
        assertTrue("and must actually stop the transfer", admitted.abort!!.isAborted)

        val completed = file("raced.apk")
        assertNull("nothing may be staged for a reservation cancelled this early", store.stage(admitted.lease, completed))
        assertFalse(completed.exists())
    }

    @Test fun beginRefusesAnArrivingBodyButSupersedesTheOperatorsPreviousStagedIntent() {
        var id = 0
        val store = PendingUploadStore { "token-${++id}" }.apply { open() }
        val firstLease = granted(store.begin())
        assertEquals(PendingUploadStore.BeginResult.Busy, store.begin())

        store.abort(firstLease)
        store.abort(firstLease)
        val secondLease = granted(store.begin())
        val staged = store.stage(secondLease, file("staged.apk"))!!

        // A staged entry is the last thing the operator asked for, not a lock held until its TTL.
        val thirdLease = granted(store.begin())
        assertNull("a superseded token must stop resolving", store.peek(staged.token))
        assertNull("a superseded token must not be claimable", store.claim(staged.token))
        assertFalse("a superseded staging file must be deleted", staged.file.exists())

        val replacement = store.stage(thirdLease, file("replacement.apk"))!!
        assertEquals(replacement.file, store.claim(replacement.token)?.file)
        assertTrue(store.begin() is PendingUploadStore.BeginResult.Granted)
    }

    @Test fun abortAfterStageDeletesOnlyTheEntryOwnedByThatLease() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val lease = granted(store.begin())
        val staged = store.stage(lease, file("aborted.apk"))!!

        store.abort(lease)
        store.abort(lease)

        assertFalse(staged.file.exists())
        assertNull(store.claim(staged.token))
        assertTrue(store.begin() is PendingUploadStore.BeginResult.Granted)
    }

    @Test fun busyClaimRestoresOnlyWhenNoNewerUploadOwnsTheSlot() {
        var id = 0
        val store = PendingUploadStore { "token-${++id}" }.apply { open() }
        val lease = granted(store.begin())
        val first = store.stage(lease, file("claimed.apk"))!!
        val claim = store.claim(first.token)!!

        assertTrue(store.restore(claim))
        val claimedAgain = store.claim(first.token)!!
        assertEquals(claim.file, claimedAgain.file)
        val newerLease = granted(store.begin())
        val newerFile = file("new.apk")
        assertFalse(store.restore(claimedAgain))
        assertFalse(claimedAgain.file.exists())
        val newer = store.stage(newerLease, newerFile)!!
        assertEquals(newer.file, store.claim(newer.token)?.file)
    }

    @Test fun closeInvalidatesSlowUploadsAndDeletesCurrentAndFutureFiles() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val lease = granted(store.begin())
        val active = file("active.apk")
        store.stage(lease, active)
        store.close()

        assertFalse(active.exists())
        assertEquals(PendingUploadStore.BeginResult.Closed, store.begin())
        val late = file("late.apk")
        assertNull(store.stage(lease, late))
        assertFalse(late.exists())

        store.open()
        assertTrue(store.begin() is PendingUploadStore.BeginResult.Granted)
        val fromPreviousLifetime = file("previous-lifetime.apk")
        assertNull(store.stage(lease, fromPreviousLifetime))
        assertFalse(fromPreviousLifetime.exists())
    }

    /** The Issue #96 defect class: an inspected upload the operator walked away from. A discard must
     *  retire exactly that entry, deleting its bytes and freeing the slot immediately rather than
     *  after the TTL or the next supersession. */
    @Test fun discardRetiresTheInspectedEntryAndDeletesItsBytes() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val staged = store.stage(granted(store.begin()), file("discarded.apk"))!!

        assertEquals(PendingUploadStore.DiscardResult.DISCARDED, store.discard(staged.token))
        assertFalse("a discarded file must not survive", staged.file.exists())
        assertNull("a discarded token must stop resolving", store.peek(staged.token))
        assertTrue("the slot must be free again", store.begin() is PendingUploadStore.BeginResult.Granted)
    }

    /** After a reload the browser holds no token; the recovery discard must still work — and stay
     *  honest when it finds nothing. */
    @Test fun tokenFreeDiscardIsTheRecoveryPathForABrowserThatLostItsToken() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val staged = store.stage(granted(store.begin()), file("reloaded.apk"))!!

        assertEquals(PendingUploadStore.DiscardResult.DISCARDED, store.discard(null))
        assertFalse(staged.file.exists())
        assertEquals(PendingUploadStore.DiscardResult.NOTHING_PENDING, store.discard(null))
        assertEquals(PendingUploadStore.DiscardResult.NOTHING_PENDING, store.discard("token"))
    }

    /** A stray click on a stale preview must never remove an upload someone staged in its place. */
    @Test fun aTokenScopedDiscardNeverRemovesAnEntryItDidNotInspect() {
        var id = 0
        val store = PendingUploadStore { "token-${++id}" }.apply { open() }
        val superseded = store.stage(granted(store.begin()), file("superseded.apk"))!!
        val current = store.stage(granted(store.begin()), file("current.apk"))!!

        assertEquals(PendingUploadStore.DiscardResult.DIFFERENT_PENDING, store.discard(superseded.token))
        assertTrue("the newer entry must be untouched", current.file.exists())
        assertNotNull("and must still be committable", store.peek(current.token))
    }

    /** A committed upload has left the slot, so a discard arriving during the install finds nothing
     *  and the install's bytes are untouchable. The busy-race restore stays honest: once the claim is
     *  put back the entry genuinely reappears, discardable again. */
    @Test fun discardCannotReachAClaimedEntryAndTheBusyRestoreRaceStaysHonest() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val staged = store.stage(granted(store.begin()), file("claimed.apk"))!!
        val claim = store.claim(staged.token)!!

        assertEquals(PendingUploadStore.DiscardResult.NOTHING_PENDING, store.discard(staged.token))
        assertTrue("a running install's bytes must be untouched", claim.file.exists())

        assertTrue(store.restore(claim))
        assertEquals(PendingUploadStore.DiscardResult.DISCARDED, store.discard(staged.token))
        assertFalse(claim.file.exists())
    }

    /** Discard owns only the slot's PENDING occupant: a body still arriving is someone's upload
     *  stream the panel cannot retract, and in-flight panel work has its own owner-scoped cancel. */
    @Test fun discardNeverTouchesAnArrivingBodyOrInFlightPanelWork() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val receiving = granted(store.begin())
        assertEquals(PendingUploadStore.DiscardResult.NOTHING_PENDING, store.discard(null))
        assertNotNull("the receive must go on to stage after a discard", store.stage(receiving, file("still-arriving.apk")))

        val fetching = PendingUploadStore { "token" }.apply { open() }
        val admitted = fetching.begin("req") as PendingUploadStore.BeginResult.Granted
        assertEquals(PendingUploadStore.DiscardResult.NOTHING_PENDING, fetching.discard(null))
        assertFalse("panel work must not be aborted by a discard", admitted.abort!!.isAborted)
        assertNotNull("and must still be able to stage", fetching.stage(admitted.lease, file("still-fetching.apk")))
    }

    /** Expiry, disable and shutdown each already delete the staged file; a discard arriving after any
     *  of them must find nothing rather than invent a second deletion path. */
    @Test fun expiryDisableAndShutdownAllLeaveNothingForDiscardToFind() {
        var now = 1_000L
        val expired = PendingUploadStore(monotonicMs = { now }, ttlMs = 100L, newToken = { "token" }).apply { open() }
        val expiredEntry = expired.stage(granted(expired.begin()), file("expired-then-discarded.apk"))!!
        now += 100L
        assertEquals(PendingUploadStore.DiscardResult.NOTHING_PENDING, expired.discard(expiredEntry.token))
        assertFalse("expiry itself must have deleted the file", expiredEntry.file.exists())

        val disabled = PendingUploadStore { "token" }.apply { open() }
        val disabledEntry = disabled.stage(granted(disabled.begin()), file("disabled-then-discarded.apk"))!!
        disabled.clear()
        assertEquals(PendingUploadStore.DiscardResult.NOTHING_PENDING, disabled.discard(disabledEntry.token))
        assertFalse(disabledEntry.file.exists())

        val stopped = PendingUploadStore { "token" }.apply { open() }
        val stoppedEntry = stopped.stage(granted(stopped.begin()), file("stopped-then-discarded.apk"))!!
        stopped.close()
        assertEquals(PendingUploadStore.DiscardResult.NOTHING_PENDING, stopped.discard(stoppedEntry.token))
        assertFalse(stoppedEntry.file.exists())
    }

    /** No older request may stage after its ownership is invalidated — even within one server
     *  lifetime, where the epoch cannot tell a released lease from the reservation that replaced it. */
    @Test fun aReleasedLeaseCannotStageOverItsReplacement() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val released = granted(store.begin())
        store.abort(released)
        val current = granted(store.begin())

        val stale = file("stale-lease.apk")
        assertNull("a released lease must stay dead", store.stage(released, stale))
        assertFalse("and its bytes must not survive", stale.exists())
        assertNotNull("while the live reservation still stages", store.stage(current, file("live-lease.apk")))
    }

    /** The probe that drives the recovery UI answers any LAN client, so it describes what is pending
     *  — existence and inspected identity — and nothing more. It reports only genuinely discardable
     *  state: a claimed, expired or vanished entry is not worth advertising. */
    @Test fun pendingSummaryDescribesOnlyADiscardableEntry() {
        var now = 1_000L
        val store = PendingUploadStore(monotonicMs = { now }, ttlMs = 100L, newToken = { "token" }).apply { open() }
        assertNull(store.pendingSummary())

        val identity = UploadedApkIdentity("example.panel", "1.2.3", "ab".repeat(32))
        val staged = store.stage(granted(store.begin()), file("probed.apk"), identity)!!
        assertEquals(identity, store.pendingSummary()?.identity)

        assertNotNull(store.claim(staged.token))
        assertNull("a claimed entry is being installed, not pending", store.pendingSummary())
        assertTrue(store.restore(staged))
        assertNotNull("a restored claim is pending again", store.pendingSummary())

        staged.file.delete()
        assertNull("a vanished file is not discardable state", store.pendingSummary())
    }

    @Test fun pendingSummaryGoesQuietOnExpiry() {
        var now = 1_000L
        val store = PendingUploadStore(monotonicMs = { now }, ttlMs = 100L, newToken = { "token" }).apply { open() }
        val staged = store.stage(granted(store.begin()), file("expiring-probe.apk"), UploadedApkIdentity("p", "1", null))!!
        assertNotNull(store.pendingSummary())
        now += 100L
        assertNull(store.pendingSummary())
        assertFalse("the expiry the probe triggered must delete the file", staged.file.exists())
    }

    private fun granted(result: PendingUploadStore.BeginResult): PendingUploadStore.Lease {
        assertTrue(result is PendingUploadStore.BeginResult.Granted)
        return (result as PendingUploadStore.BeginResult.Granted).lease
    }
}
