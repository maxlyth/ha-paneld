package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingUploadStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun file(name: String) = temporary.newFile(name).apply { writeText(name) }

    @Test fun beginExclusivelyReservesReceiveAndStagedSlotsUntilAbortOrClaim() {
        var id = 0
        val store = PendingUploadStore { "token-${++id}" }.apply { open() }
        val firstLease = granted(store.begin())
        assertEquals(PendingUploadStore.BeginResult.Busy, store.begin())

        store.abort(firstLease)
        store.abort(firstLease)
        val secondLease = granted(store.begin())
        val staged = store.stage(secondLease, file("staged.apk"))!!
        assertEquals(PendingUploadStore.BeginResult.Busy, store.begin())

        assertEquals(staged.file, store.claim(staged.token)?.file)
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
