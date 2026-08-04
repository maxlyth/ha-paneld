package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            val lease = granted(store.begin(panelWork = true))
            val abort = io.github.maxlyth.hapaneld.util.DownloadAbort()
            store.attachPanelWork(lease, "req", abort)
            return store to abort
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
        val lease = granted(store.begin(panelWork = true))
        store.attachPanelWork(lease, "mine", io.github.maxlyth.hapaneld.util.DownloadAbort())

        assertTrue(store.cancelPanelWork("mine"))
        assertTrue("the refusal must be attributable to the cancel", store.isCancelled(lease))

        val completed = file("arrived-anyway.apk")
        assertNull("a cancelled reservation must never mint a token", store.stage(lease, completed))
        assertFalse("and its bytes must not survive", completed.exists())
    }

    /** A cancel may only stop the request that started the work, never a replacement. */
    @Test fun panelWorkIsCancellableOnlyByTheRequestThatOwnsIt() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val lease = granted(store.begin(panelWork = true))
        val abort = io.github.maxlyth.hapaneld.util.DownloadAbort()
        store.attachPanelWork(lease, "mine", abort)

        assertFalse("a foreign request must not cancel", store.cancelPanelWork("someone-else"))
        assertFalse(abort.isAborted)
        assertTrue(store.cancelPanelWork("mine"))
        assertTrue(abort.isAborted)
    }

    /** Work admitted against a reservation that has already moved on must never start. */
    @Test fun panelWorkAttachedToASupersededReservationIsStoppedImmediately() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val stale = granted(store.begin(panelWork = true))
        store.begin()
        val abort = io.github.maxlyth.hapaneld.util.DownloadAbort()

        store.attachPanelWork(stale, "stale", abort)

        assertTrue("work for a superseded reservation must not run", abort.isAborted)
        assertFalse("and it must not become cancellable under that owner", store.cancelPanelWork("stale"))
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

    private fun granted(result: PendingUploadStore.BeginResult): PendingUploadStore.Lease {
        assertTrue(result is PendingUploadStore.BeginResult.Granted)
        return (result as PendingUploadStore.BeginResult.Granted).lease
    }
}
