package io.github.maxlyth.hapaneld.persistence

import android.content.SharedPreferences
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStateConcurrencyTest {
    @Test fun concurrentFactoryAccessConstructsOneValue() {
        val cache = AtomicFactoryCache<String, Any>()
        val constructed = AtomicInteger()
        val start = CountDownLatch(1)
        val callers = Executors.newFixedThreadPool(12)
        try {
            val results = (1..48).map {
                callers.submit<Any> {
                    start.await()
                    cache.getOrCreate("namespace") {
                        constructed.incrementAndGet()
                        Thread.sleep(5)
                        Any()
                    }
                }
            }
            start.countDown()
            val first = results.first().get(5, TimeUnit.SECONDS)
            results.drop(1).forEach { assertSame(first, it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, constructed.get())
        } finally {
            callers.shutdownNow()
        }
    }

    @Test fun applyPublishesMemoryAndListenersBeforeBackgroundPersistenceCompletes() {
        val persistenceStarted = CountDownLatch(1)
        val releasePersistence = CountDownLatch(1)
        val persistence = RecordingPersistence(
            persistBlock = {
                persistenceStarted.countDown()
                releasePersistence.await(5, TimeUnit.SECONDS)
            },
        )
        val writer = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(persistence, writer)
            val changed = Collections.synchronizedList(mutableListOf<String>())
            preferences.registerOnSharedPreferenceChangeListener(
                SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changed += key },
            )

            preferences.edit().putString("mode", "ready").apply()

            assertEquals("ready", preferences.getString("mode", null))
            assertEquals(listOf("mode"), changed)
            assertTrue(persistenceStarted.await(5, TimeUnit.SECONDS))
            assertTrue(persistence.events.isEmpty())
        } finally {
            releasePersistence.countDown()
            writer.shutdown()
            assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertEquals(listOf("persist:mode"), persistence.events)
    }

    @Test fun commitWaitsBehindEarlierApplyAndPreservesWriteOrder() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val persistence = RecordingPersistence(
            persistBlock = { mutation ->
                if ("first" in mutation.changes) {
                    firstStarted.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                }
            },
        )
        val writer = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(persistence, writer)
            val secondVisible = CountDownLatch(1)
            preferences.registerOnSharedPreferenceChangeListener(
                SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == "second") secondVisible.countDown()
                },
            )
            preferences.edit().putInt("first", 1).apply()
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))

            val committed = caller.submit<Boolean> {
                preferences.edit().putInt("second", 2).commit()
            }
            assertTrue(secondVisible.await(5, TimeUnit.SECONDS))
            assertFalse(committed.isDone)
            assertEquals(2, preferences.getInt("second", 0))

            releaseFirst.countDown()
            assertTrue(committed.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("persist:first", "persist:second"), persistence.events)
        } finally {
            releaseFirst.countDown()
            caller.shutdownNow()
            writer.shutdownNow()
        }
    }

    @Test fun nextWriteReconcilesCompleteSnapshotAfterFailedApply() {
        val persistence = RecordingPersistence(failFirstPersist = true)
        val writer = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(persistence, writer)
            preferences.edit().putString("first", "one").apply()

            assertTrue(preferences.edit().putString("second", "two").commit())

            assertEquals(listOf("persist:first", "replace:first,second"), persistence.events)
            assertEquals(mapOf("first" to "one", "second" to "two"), persistence.replacedSnapshot)
        } finally {
            writer.shutdownNow()
        }
    }

    private class RecordingPersistence(
        private val persistBlock: (StateMutation) -> Unit = {},
        private val failFirstPersist: Boolean = false,
    ) : StateNamespacePersistence {
        val events = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var replacedSnapshot: Map<String, Any>? = null
        private val persistCalls = AtomicInteger()

        override fun initialize(): Map<String, Any> = emptyMap()

        override fun persist(mutation: StateMutation): Boolean {
            persistBlock(mutation)
            events += "persist:${mutation.changes.keys.joinToString(",")}"
            return !(failFirstPersist && persistCalls.getAndIncrement() == 0)
        }

        override fun replace(snapshot: Map<String, Any>): Boolean {
            replacedSnapshot = snapshot
            events += "replace:${snapshot.keys.joinToString(",")}"
            return true
        }
    }
}
