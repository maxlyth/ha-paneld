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

    @Test fun durableVisibilityCommitPublishesOnlyAfterPersistenceAndRetainsPriorStateOnFailure() {
        val persistenceStarted = CountDownLatch(1)
        val releasePersistence = CountDownLatch(1)
        val persistence = RecordingPersistence(
            persistBlock = {
                persistenceStarted.countDown()
                releasePersistence.await(5, TimeUnit.SECONDS)
            },
            failFirstPersist = true,
        )
        val writer = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(persistence, writer)
            val changed = Collections.synchronizedList(mutableListOf<String>())
            preferences.registerOnSharedPreferenceChangeListener(
                SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changed += key },
            )

            val failed = caller.submit<Boolean> {
                preferences.commitWithDurableVisibility { putString("ownership", "allow") }
            }
            assertTrue(persistenceStarted.await(5, TimeUnit.SECONDS))
            assertFalse(failed.isDone)
            assertFalse(preferences.contains("ownership"))
            assertTrue(changed.isEmpty())

            releasePersistence.countDown()
            assertFalse(failed.get(5, TimeUnit.SECONDS))
            assertFalse(preferences.contains("ownership"))
            assertTrue(changed.isEmpty())

            assertTrue(
                preferences.commitWithDurableVisibility { putString("ownership", "allow") },
            )
            assertEquals("allow", preferences.getString("ownership", null))
            assertEquals(listOf("ownership"), changed)
            assertEquals(listOf("persist:ownership", "replace:ownership"), persistence.events)
        } finally {
            releasePersistence.countDown()
            caller.shutdownNow()
            writer.shutdownNow()
        }
    }

    @Test fun failedDurableVisibilityRemovalLeavesOwnershipVisibleForRetry() {
        val persistence = RecordingPersistence(failPersistCall = 2)
        val writer = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(persistence, writer)
            assertTrue(
                preferences.commitWithDurableVisibility { putString("ownership", "foreground") },
            )

            assertFalse(preferences.commitWithDurableVisibility { remove("ownership") })
            assertEquals("foreground", preferences.getString("ownership", null))

            assertTrue(preferences.commitWithDurableVisibility { remove("ownership") })
            assertFalse(preferences.contains("ownership"))
            assertEquals(
                listOf("persist:ownership", "persist:ownership", "replace:"),
                persistence.events,
            )
        } finally {
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

    @Test fun upgradeMutationAndDowngradeEditSurviveBothDirections() {
        val legacy = RecordingLegacyMirror(
            linkedMapOf(
                "dashboard_url" to "http://old.example",
                "removed_later" to true,
            ),
        )
        val primary = ImportingPersistence(legacy.snapshot())
        val metadata = RecordingBridgeMetadata()
        val writer = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(
                DowngradeCompatibleStatePersistence(primary, legacy, metadata),
                writer,
            )

            assertTrue(
                preferences.edit()
                    .putString("dashboard_url", "https://current.example")
                    .remove("removed_later")
                    .putStringSet("entities", setOf("light.kitchen", "sensor.zone"))
                    .commit(),
            )

            // Simulate the old 0.9.x APK opening and then editing its XML after a deliberate downgrade.
            assertEquals(
                mapOf(
                    "dashboard_url" to "https://current.example",
                    "entities" to setOf("light.kitchen", "sensor.zone"),
                ),
                legacy.snapshot(),
            )
            legacy.persist(
                StateMutation(
                    clear = false,
                    changes = mapOf("dashboard_url" to "http://edited-by-old-build.example"),
                ),
            )

            val returnedPreferences = SqliteStatePreferences(
                DowngradeCompatibleStatePersistence(primary, legacy, metadata),
                writer,
            )
            assertEquals(
                "http://edited-by-old-build.example",
                returnedPreferences.getString("dashboard_url", null),
            )
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun markerlessDivergenceKeepsSqliteActiveAndLegacyUntouched() {
        val legacy = RecordingLegacyMirror(
            mapOf("dashboard_url" to "http://stale.example"),
        )
        val primary = ImportingPersistence(
            mapOf("dashboard_url" to "https://vc236-current.example"),
        )
        val metadata = RecordingBridgeMetadata()
        val writer = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(
                DowngradeCompatibleStatePersistence(primary, legacy, metadata),
                writer,
            )

            assertEquals(
                "https://vc236-current.example",
                preferences.getString("dashboard_url", null),
            )
            assertEquals(
                mapOf("dashboard_url" to "http://stale.example"),
                legacy.snapshot(),
            )
            assertTrue(metadata.readHash() == null)
            assertEquals(
                stateSnapshotHash(primary.snapshot()) to stateSnapshotHash(legacy.snapshot()),
                metadata.conflict,
            )
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun driftedMirrorNeverWipesLiveConfig() {
        val fullConfig: Map<String, Any> = linkedMapOf(
            "panel_id" to "kitchen",
            "ha_url" to "https://ha.example",
            "dashboard_package" to "builtin",
        )
        val primary = ImportingPersistence(fullConfig)
        val legacy = RecordingLegacyMirror(linkedMapOf("panel_id" to "kitchen"))
        val metadata = RecordingBridgeMetadata().also {
            it.writeHash(stateSnapshotHash(fullConfig))
        }

        val snapshot = DowngradeCompatibleStatePersistence(primary, legacy, metadata).initialize()

        assertEquals("https://ha.example", snapshot["ha_url"])
        assertEquals("builtin", snapshot["dashboard_package"])
        assertEquals("kitchen", snapshot["panel_id"])
        assertEquals(fullConfig, primary.snapshot())
    }

    @Test fun matchingLegacyMarkerRecoversRicherLegacySnapshot() {
        val legacy: Map<String, Any> = linkedMapOf(
            "panel_id" to "old-panel-id",
            "ha_url" to "https://ha.example",
            "mqtt_broker" to "tcp://mqtt.example:1883",
        )
        val primary = ImportingPersistence(
            mapOf("panel_id" to "current-panel-id", "config_schema" to 3),
        )
        val mirror = RecordingLegacyMirror(legacy)
        val metadata = RecordingBridgeMetadata().also { it.writeHash(stateSnapshotHash(legacy)) }

        val snapshot = DowngradeCompatibleStatePersistence(primary, mirror, metadata).initialize()

        assertEquals("https://ha.example", snapshot["ha_url"])
        assertEquals("tcp://mqtt.example:1883", snapshot["mqtt_broker"])
        assertEquals("current-panel-id", snapshot["panel_id"])
        assertEquals(3, snapshot["config_schema"])
        assertEquals(snapshot, primary.snapshot())
        assertEquals(snapshot, mirror.snapshot())
    }

    @Test fun firstPublicUpgradeWithEqualStatesEstablishesNormalMarker() {
        val state = mapOf("dashboard_url" to "https://public-093.example")
        val legacy = RecordingLegacyMirror(state)
        val primary = ImportingPersistence(state)
        val metadata = RecordingBridgeMetadata()
        val writer = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(
                DowngradeCompatibleStatePersistence(primary, legacy, metadata),
                writer,
            )

            assertEquals(
                "https://public-093.example",
                preferences.getString("dashboard_url", null),
            )
            assertEquals(stateSnapshotHash(legacy.snapshot()), metadata.readHash())
            assertTrue(metadata.conflict == null)
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun firstMutationAfterMarkerlessConflictReconcilesFullSqliteCandidate() {
        val legacy = RecordingLegacyMirror(mapOf("url" to "stale"))
        val primary = ImportingPersistence(mapOf("url" to "current", "newKey" to "x"))
        val metadata = RecordingBridgeMetadata()
        val writer = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(
                DowngradeCompatibleStatePersistence(primary, legacy, metadata),
                writer,
            )
            assertTrue(preferences.edit().putString("theme", "dark").commit())

            val expected = mapOf("url" to "current", "newKey" to "x", "theme" to "dark")
            assertEquals(expected, preferences.all)
            assertEquals(expected, primary.snapshot())
            assertEquals(expected, legacy.snapshot())
            assertEquals(stateSnapshotHash(expected), metadata.readHash())

            val reopened = SqliteStatePreferences(
                DowngradeCompatibleStatePersistence(primary, legacy, metadata),
                writer,
            )
            assertEquals(expected, reopened.all)
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun shutdownDrainWaitsForBlockedApplyToBecomeDurable() {
        val persistenceStarted = CountDownLatch(1)
        val releasePersistence = CountDownLatch(1)
        val persistence = RecordingPersistence(
            persistBlock = {
                persistenceStarted.countDown()
                releasePersistence.await(5, TimeUnit.SECONDS)
            },
        )
        val writer = Executors.newSingleThreadExecutor()
        val shutdown = Executors.newSingleThreadExecutor()
        try {
            val preferences = SqliteStatePreferences(persistence, writer)
            preferences.edit().putString("mode", "saved-before-replace").apply()
            assertTrue(persistenceStarted.await(5, TimeUnit.SECONDS))

            val drained = shutdown.submit<Boolean> { preferences.flush(5_000) }
            assertFalse(drained.isDone)

            releasePersistence.countDown()
            assertTrue(drained.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("persist:mode", "replace:mode"), persistence.events)
        } finally {
            releasePersistence.countDown()
            shutdown.shutdownNow()
            writer.shutdownNow()
        }
    }

    @Test fun quiesceDefersRacingApplyUntilFailedInstallReopensAdmission() {
        val persistence = RecordingPersistence()
        val writer = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        val admission = StateMutationAdmission()
        val applyStarted = CountDownLatch(1)
        try {
            val preferences = SqliteStatePreferences(persistence, writer, admission)
            val changed = Collections.synchronizedList(mutableListOf<String>())
            preferences.registerOnSharedPreferenceChangeListener(
                SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changed += key },
            )
            val quiescence = quiesceStateWrites(admission) { true }
            assertTrue(quiescence != null)

            val applying = caller.submit<Unit> {
                applyStarted.countDown()
                preferences.edit().putString("racing_apply", "durable").apply()
            }
            assertTrue(applyStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertFalse(applying.isDone)
            assertFalse(preferences.contains("racing_apply"))
            assertTrue(changed.isEmpty())

            quiescence!!.close()
            applying.get(5, TimeUnit.SECONDS)
            assertTrue(preferences.flush(5_000))
            assertEquals("durable", preferences.getString("racing_apply", null))
            assertEquals(listOf("racing_apply"), changed)
            assertEquals(listOf("persist:racing_apply", "replace:racing_apply"), persistence.events)
        } finally {
            caller.shutdownNow()
            writer.shutdownNow()
        }
    }

    @Test fun quiesceFlushExcludesAdmittedWritesUntilTheLeaseCloses() {
        val admission = StateMutationAdmission()
        val flushEntered = CountDownLatch(1)
        val releaseFlush = CountDownLatch(1)
        val frozenWriteEntered = CountDownLatch(1)
        val quiescer = Executors.newSingleThreadExecutor()
        val writer = Executors.newSingleThreadExecutor()
        try {
            val quiescence = quiescer.submit<StateQuiescence?> {
                quiesceStateWrites(admission) {
                    flushEntered.countDown()
                    releaseFlush.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(flushEntered.await(5, TimeUnit.SECONDS))

            val racingWrite = writer.submit<Unit> {
                admission.admit {
                    frozenWriteEntered.countDown()
                }
            }
            assertFalse(frozenWriteEntered.await(100, TimeUnit.MILLISECONDS))

            releaseFlush.countDown()
            val activeQuiescence = quiescence.get(5, TimeUnit.SECONDS)
            assertTrue(activeQuiescence != null)
            assertFalse(frozenWriteEntered.await(100, TimeUnit.MILLISECONDS))

            activeQuiescence!!.close()
            racingWrite.get(5, TimeUnit.SECONDS)
            assertTrue(frozenWriteEntered.await(5, TimeUnit.SECONDS))
        } finally {
            releaseFlush.countDown()
            quiescer.shutdownNow()
            writer.shutdownNow()
        }
    }

    @Test fun concurrentAppliesWaitForQuiescenceThenPreserveCandidatePersistenceAndPublication() {
        val persistence = SnapshotPersistence()
        val writer = Executors.newSingleThreadExecutor()
        val callers = Executors.newFixedThreadPool(2)
        val admission = StateMutationAdmission()
        val start = CountDownLatch(1)
        try {
            val preferences = SqliteStatePreferences(persistence, writer, admission)
            val quiescence = quiesceStateWrites(admission) { true }
            assertTrue(quiescence != null)

            val first = callers.submit<Unit> {
                start.await()
                preferences.edit().putString("first", "one").apply()
            }
            val second = callers.submit<Unit> {
                start.await()
                preferences.edit().putString("second", "two").apply()
            }
            start.countDown()
            Thread.sleep(100)
            assertFalse(first.isDone)
            assertFalse(second.isDone)
            assertTrue(preferences.all.isEmpty())

            quiescence!!.close()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertTrue(preferences.flush(5_000))

            assertEquals(
                mapOf("first" to "one", "second" to "two"),
                preferences.all,
            )
            assertEquals(
                mapOf("first" to "one", "second" to "two"),
                persistence.snapshot(),
            )
        } finally {
            callers.shutdownNow()
            writer.shutdownNow()
        }
    }

    @Test fun failedInstallCanCloseQuiescenceAndRestoreAsyncAdmission() {
        val persistence = RecordingPersistence()
        val writer = Executors.newSingleThreadExecutor()
        val admission = StateMutationAdmission()
        try {
            val preferences = SqliteStatePreferences(persistence, writer, admission)
            assertTrue(preferences.edit().putString("before", "durable").commit())

            val quiescence = quiesceStateWrites(admission) { true }
            assertTrue(quiescence != null)
            quiescence!!.close()
            assertTrue(preferences.edit().putString("after", "accepted").commit())
            assertEquals("accepted", preferences.getString("after", null))
            assertEquals(listOf("persist:before", "persist:after"), persistence.events)
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun failedQuiesceDrainReopensMutationAdmission() {
        val persistence = RecordingPersistence()
        val writer = Executors.newSingleThreadExecutor()
        val admission = StateMutationAdmission()
        try {
            val preferences = SqliteStatePreferences(persistence, writer, admission)

            assertTrue(quiesceStateWrites(admission) { false } == null)
            assertTrue(preferences.edit().putString("retry", "accepted").commit())
            assertEquals("accepted", preferences.getString("retry", null))
            assertEquals(listOf("persist:retry"), persistence.events)
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun throwingQuiesceDrainReopensMutationAdmission() {
        val persistence = RecordingPersistence()
        val writer = Executors.newSingleThreadExecutor()
        val admission = StateMutationAdmission()
        try {
            val preferences = SqliteStatePreferences(persistence, writer, admission)

            assertTrue(quiesceStateWrites(admission) { error("unexpected flush failure") } == null)
            assertTrue(preferences.edit().putString("retry", "accepted").commit())
            assertEquals("accepted", preferences.getString("retry", null))
            assertEquals(listOf("persist:retry"), persistence.events)
        } finally {
            writer.shutdownNow()
        }
    }

    private class RecordingPersistence(
        private val persistBlock: (StateMutation) -> Unit = {},
        private val failFirstPersist: Boolean = false,
        private val failPersistCall: Int? = null,
    ) : StateNamespacePersistence {
        val events = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var replacedSnapshot: Map<String, Any>? = null
        private val persistCalls = AtomicInteger()

        override fun initialize(): Map<String, Any> = emptyMap()

        override fun persist(mutation: StateMutation): Boolean {
            persistBlock(mutation)
            events += "persist:${mutation.changes.keys.joinToString(",")}"
            val call = persistCalls.incrementAndGet()
            return !(failFirstPersist && call == 1) && call != failPersistCall
        }

        override fun replace(snapshot: Map<String, Any>): Boolean {
            replacedSnapshot = snapshot
            events += "replace:${snapshot.keys.joinToString(",")}"
            return true
        }
    }

    private class SnapshotPersistence : StateNamespacePersistence {
        private val values = linkedMapOf<String, Any>()

        override fun initialize(): Map<String, Any> = emptyMap()

        @Synchronized
        override fun persist(mutation: StateMutation): Boolean {
            if (mutation.clear) values.clear()
            mutation.changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            return true
        }

        @Synchronized
        override fun replace(snapshot: Map<String, Any>): Boolean {
            values.clear()
            values.putAll(snapshot)
            return true
        }

        @Synchronized
        fun snapshot(): Map<String, Any> = values.toMap()
    }

    private class ImportingPersistence(
        imported: Map<String, Any>,
    ) : StateNamespacePersistence {
        private val values = imported.toMutableMap()

        override fun initialize(): Map<String, Any> = values.toMap()

        override fun persist(mutation: StateMutation): Boolean {
            if (mutation.clear) values.clear()
            mutation.changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            return true
        }

        override fun replace(snapshot: Map<String, Any>): Boolean {
            values.clear()
            values.putAll(snapshot)
            return true
        }

        fun snapshot(): Map<String, Any> = values.toMap()
    }

    private class RecordingLegacyMirror(
        initial: Map<String, Any>,
    ) : LegacyStateMirror {
        private val values = initial.toMutableMap()

        override fun snapshot(): Map<String, Any> = values.toMap()

        override fun persist(mutation: StateMutation): Boolean {
            if (mutation.clear) values.clear()
            mutation.changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            return true
        }

        override fun replace(snapshot: Map<String, Any>): Boolean {
            values.clear()
            values.putAll(snapshot)
            return true
        }
    }

    private class RecordingBridgeMetadata : BridgeMetadata {
        private var hash: String? = null
        var conflict: Pair<String, String>? = null
            private set

        override fun readHash(): String? = hash

        override fun writeHash(hash: String): Boolean {
            this.hash = hash
            conflict = null
            return true
        }

        override fun writeConflict(sqliteHash: String, legacyHash: String): Boolean {
            conflict = sqliteHash to legacyHash
            return true
        }
    }
}
