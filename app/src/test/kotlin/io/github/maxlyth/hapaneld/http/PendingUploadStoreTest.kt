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

    @Test fun newerUploadCannotMakeAnOlderInspectionTokenClaimItsFile() {
        var id = 0
        val store = PendingUploadStore { "token-${++id}" }.apply { open() }
        val lease = store.begin()!!
        val first = file("first.apk")
        val second = file("second.apk")
        val firstEntry = store.stage(lease, first)!!
        val secondEntry = store.stage(lease, second)!!

        assertFalse(first.exists())
        assertNull(store.claim(firstEntry.token))
        assertEquals(second, store.claim(secondEntry.token)?.file)
    }

    @Test fun busyClaimRestoresOnlyWhenNoNewerUploadOwnsTheSlot() {
        var id = 0
        val store = PendingUploadStore { "token-${++id}" }.apply { open() }
        val lease = store.begin()!!
        val first = store.stage(lease, file("claimed.apk"))!!
        val claim = store.claim(first.token)!!

        assertTrue(store.restore(claim))
        assertEquals(claim.file, store.claim(first.token)?.file)

        val claimedAgain = store.stage(lease, file("old.apk"))!!.let { store.claim(it.token)!! }
        val newer = store.stage(lease, file("new.apk"))!!
        assertFalse(store.restore(claimedAgain))
        assertFalse(claimedAgain.file.exists())
        assertEquals(newer.file, store.claim(newer.token)?.file)
    }

    @Test fun closeInvalidatesSlowUploadsAndDeletesCurrentAndFutureFiles() {
        val store = PendingUploadStore { "token" }.apply { open() }
        val lease = store.begin()!!
        val active = file("active.apk")
        store.stage(lease, active)
        store.close()

        assertFalse(active.exists())
        assertNull(store.begin())
        val late = file("late.apk")
        assertNull(store.stage(lease, late))
        assertFalse(late.exists())

        store.open()
        assertTrue(store.begin() != null)
        val fromPreviousLifetime = file("previous-lifetime.apk")
        assertNull(store.stage(lease, fromPreviousLifetime))
        assertFalse(fromPreviousLifetime.exists())
    }
}
