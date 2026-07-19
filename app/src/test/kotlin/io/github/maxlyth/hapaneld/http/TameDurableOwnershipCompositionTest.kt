package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.TameDesiredStateReconciler
import io.github.maxlyth.hapaneld.control.TamePackageObservation
import io.github.maxlyth.hapaneld.control.TamePackagePresence
import io.github.maxlyth.hapaneld.control.TamePackageSafety
import io.github.maxlyth.hapaneld.control.TameStatePolicy
import io.github.maxlyth.hapaneld.persistence.SqliteStatePreferences
import io.github.maxlyth.hapaneld.persistence.StateMutation
import io.github.maxlyth.hapaneld.persistence.StateNamespacePersistence
import io.github.maxlyth.hapaneld.persistence.commitWithDurableVisibility
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TameDurableOwnershipCompositionTest {
    @Test fun `failed backend marker creation stays invisible and automatic retry precedes mutation`() {
        val failedWrite = CountDownLatch(1)
        val retryPaused = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val mutated = CountDownLatch(1)
        val persistence = FailOncePersistence(emptyMap(), failedWrite)
        val writer = Executors.newSingleThreadExecutor()
        val state = SqliteStatePreferences(persistence, writer)
        val pkg = "com.vendor.one"
        val key = TameStatePolicy.markerKey(pkg)
        val externalMutations = AtomicInteger()
        val reconciler = TameDesiredStateReconciler(
            readOwned = { TameStatePolicy.parseOwnedMarkers(state.all) },
            observePackages = ::presentAndSafe,
            reassert = {
                val markerExists = state.contains(key)
                TameStatePolicy.reassertOwnership(
                    markerExists = markerExists,
                    markerMode = state.getString(key, null),
                    captureMode = { "allow" },
                    persistMarker = { mode ->
                        state.commitWithDurableVisibility { putString(key, mode) }
                    },
                    mutate = {
                        assertEquals("allow", persistence.snapshot()[key])
                        externalMutations.incrementAndGet()
                        mutated.countDown()
                        true
                    },
                )
            },
            restore = { false },
            clearAbsent = { false },
        )
        val owner = TameReconcileAuthority(
            readDesired = { setOf(pkg) },
            reconcile = reconciler::reconcile,
            stopping = { false },
            retryDelayMs = 1,
            sleep = {
                retryPaused.countDown()
                releaseRetry.await(10, TimeUnit.SECONDS)
            },
        )
        try {
            owner.request()
            assertTrue(failedWrite.await(5, TimeUnit.SECONDS))
            assertTrue(retryPaused.await(5, TimeUnit.SECONDS))
            assertFalse(state.contains(key))
            assertFalse(key in persistence.snapshot())
            assertEquals(0, externalMutations.get())

            releaseRetry.countDown()
            assertTrue(mutated.await(5, TimeUnit.SECONDS))
            assertTrue(owner.closeAndJoin(2_000))
            assertEquals("allow", state.getString(key, null))
            assertEquals("allow", persistence.snapshot()[key])
            assertEquals(1, externalMutations.get())
        } finally {
            releaseRetry.countDown()
            owner.closeAndJoin(2_000)
            writer.shutdownNow()
        }
    }

    @Test fun `failed backend marker removal stays visible and automatic retry restores again`() {
        val pkg = "com.vendor.one"
        val key = TameStatePolicy.markerKey(pkg)
        val failedWrite = CountDownLatch(1)
        val retryPaused = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val restored = CountDownLatch(1)
        val persistence = FailOncePersistence(mapOf(key to "foreground"), failedWrite)
        val writer = Executors.newSingleThreadExecutor()
        val state = SqliteStatePreferences(persistence, writer)
        val restoreAttempts = AtomicInteger()
        val reconciler = TameDesiredStateReconciler(
            readOwned = { TameStatePolicy.parseOwnedMarkers(state.all) },
            observePackages = ::presentAndSafe,
            reassert = { false },
            restore = { marker ->
                restoreAttempts.incrementAndGet()
                TameStatePolicy.restoreOwnership(
                    markerMode = marker.overlayMode,
                    enable = { true },
                    restoreOverlay = { it == "foreground" },
                    removeMarker = {
                        state.commitWithDurableVisibility { remove(key) }.also { removed ->
                            if (removed) restored.countDown()
                        }
                    },
                )
            },
            clearAbsent = { false },
        )
        val owner = TameReconcileAuthority(
            readDesired = { emptySet() },
            reconcile = reconciler::reconcile,
            stopping = { false },
            retryDelayMs = 1,
            sleep = {
                retryPaused.countDown()
                releaseRetry.await(10, TimeUnit.SECONDS)
            },
        )
        try {
            owner.request()
            assertTrue(failedWrite.await(5, TimeUnit.SECONDS))
            assertTrue(retryPaused.await(5, TimeUnit.SECONDS))
            assertEquals("foreground", state.getString(key, null))
            assertEquals("foreground", persistence.snapshot()[key])
            assertEquals(1, restoreAttempts.get())

            releaseRetry.countDown()
            assertTrue(restored.await(5, TimeUnit.SECONDS))
            assertTrue(owner.closeAndJoin(2_000))
            assertFalse(state.contains(key))
            assertFalse(key in persistence.snapshot())
            assertEquals(2, restoreAttempts.get())
        } finally {
            releaseRetry.countDown()
            owner.closeAndJoin(2_000)
            writer.shutdownNow()
        }
    }

    private fun presentAndSafe(packages: Set<String>): Map<String, TamePackageObservation> =
        packages.associateWith {
            TamePackageObservation(TamePackagePresence.PRESENT, TamePackageSafety.SAFE)
        }

    private class FailOncePersistence(
        initial: Map<String, Any>,
        private val failedWrite: CountDownLatch,
    ) : StateNamespacePersistence {
        private val values = initial.toMutableMap()
        private var failuresRemaining = 1

        @Synchronized
        override fun initialize(): Map<String, Any> = values.toMap()

        @Synchronized
        override fun persist(mutation: StateMutation): Boolean = write {
            if (mutation.clear) values.clear()
            mutation.changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
        }

        @Synchronized
        override fun replace(snapshot: Map<String, Any>): Boolean = write {
            values.clear()
            values.putAll(snapshot)
        }

        @Synchronized
        fun snapshot(): Map<String, Any> = values.toMap()

        private fun write(mutation: () -> Unit): Boolean {
            if (failuresRemaining > 0) {
                failuresRemaining--
                failedWrite.countDown()
                return false
            }
            mutation()
            return true
        }
    }
}
