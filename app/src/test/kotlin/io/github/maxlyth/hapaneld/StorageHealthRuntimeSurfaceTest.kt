package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.storage.StorageDatabaseFailureKind
import io.github.maxlyth.hapaneld.storage.StorageHealthObservation
import io.github.maxlyth.hapaneld.storage.StorageHealthSeverity
import io.github.maxlyth.hapaneld.storage.StorageHealthSnapshot
import io.github.maxlyth.hapaneld.storage.StorageQuickCheck
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageHealthRuntimeSurfaceTest {
    @Test fun dailyObservationQualityRetriesFailedAndIncompleteEvidence() {
        assertEquals(
            StorageHealthObservationQuality.COMPLETE,
            storageHealthObservationQuality(observation()),
        )
        assertEquals(
            StorageHealthObservationQuality.FAILED,
            storageHealthObservationQuality(observation().copy(quickCheck = StorageQuickCheck.FAILED)),
        )
        assertFalse(storageHealthObservationNeedsRetry(observation()))
        assertFalse(storageHealthObservationNeedsRetry(observation().copy(quickCheck = StorageQuickCheck.FAILED)))
        val incomplete = listOf(
            observation().copy(checkedAtMillis = 0L),
            observation().copy(totalBytes = 0L, usableBytes = 0L),
            observation().copy(mainDatabaseBytes = 0L),
            observation().copy(pageSizeBytes = 0L),
            observation().copy(pageCount = 0L, freelistCount = 0L),
            observation().copy(schemaVersion = 0),
            observation().copy(quickCheck = StorageQuickCheck.NOT_RUN),
        )
        incomplete.forEach {
            assertEquals(StorageHealthObservationQuality.INCOMPLETE, storageHealthObservationQuality(it))
            assertTrue(storageHealthObservationNeedsRetry(it))
        }
    }

    @Test fun promptRecoveryOnlyCompletesForFinishedOrStoppedObservations() {
        assertTrue(storageHealthRecoveryAttemptComplete(StorageHealthObservationAttempt.Complete))
        assertTrue(storageHealthRecoveryAttemptComplete(StorageHealthObservationAttempt.Stopped))
        assertFalse(storageHealthRecoveryAttemptComplete(StorageHealthObservationAttempt.Retry))
    }

    @Test fun notificationPolicyKeepsUncheckedCancelsNonCriticalAndShowsActionableFaults() {
        assertEquals(
            StorageHealthNotificationDecision.KeepExisting,
            storageHealthNotificationDecision(StorageHealthSnapshot.UNCHECKED),
        )
        for (severity in listOf(StorageHealthSeverity.HEALTHY, StorageHealthSeverity.WARNING)) {
            assertEquals(
                StorageHealthNotificationDecision.Cancel,
                storageHealthNotificationDecision(snapshot(severity)),
            )
        }

        val critical = storageHealthNotificationDecision(
            snapshot(StorageHealthSeverity.CRITICAL, usableBytes = 42L * 1024L * 1024L),
        ) as StorageHealthNotificationDecision.Show
        assertEquals("Panel storage/database pressure critical", critical.title)
        assertTrue(critical.body.contains("42 MiB filesystem free"))
        assertTrue(critical.body.contains("WAL growth"))
        val unknownCapacity = storageHealthNotificationDecision(
            snapshot(StorageHealthSeverity.CRITICAL).copy(usableBytes = 0L, totalBytes = 0L, usedPercent = null),
        ) as StorageHealthNotificationDecision.Show
        assertFalse(unknownCapacity.body.contains("MiB filesystem free"))
        assertTrue(unknownCapacity.body.contains("WAL growth"))

        for (kind in StorageDatabaseFailureKind.entries) {
            val decision = storageHealthNotificationDecision(
                snapshot(StorageHealthSeverity.DATABASE_FAILURE, failureKind = kind)
                    .copy(databaseFailureOperation = "/data/user/0/private.db write"),
            ) as StorageHealthNotificationDecision.Show
            assertEquals("Panel database needs attention", decision.title)
            assertTrue(decision.body.contains("ha-paneld"))
            assertFalse(decision.body.contains("/data/"))
            assertFalse(decision.body.contains("private.db"))
        }
    }

    @Test fun mqttAttributesAreTypedSanitizedAndPreserveUnderlyingPressure() {
        val payload = storageHealthMqttAttributes(
            snapshot(
                severity = StorageHealthSeverity.DATABASE_FAILURE,
                pressure = StorageHealthSeverity.CRITICAL,
                usableBytes = 123L,
                failureKind = StorageDatabaseFailureKind.STORAGE_FULL,
            ).copy(databaseFailureOperation = "/data/private.db insert secret"),
        )
        val json = JSONObject(payload)

        assertEquals("critical", json.getString("storage_pressure"))
        assertEquals("storage_full", json.getString("failure_category"))
        assertEquals(123L, json.getLong("usable_bytes"))
        assertEquals(1_000L, json.getLong("total_bytes"))
        assertEquals(877L, json.getLong("database_files_bytes"))
        assertTrue(json.get("used_percent") is Number)
        assertTrue(json.get("page_count") is Number)
        assertFalse(payload.contains("/data/"))
        assertFalse(payload.contains("secret"))
        assertFalse(payload.contains("databaseFailureOperation"))
    }

    @Test fun uncheckedMqttAttributesUseExplicitNullsAndValidVocabulary() {
        val json = JSONObject(storageHealthMqttAttributes(StorageHealthSnapshot.UNCHECKED))
        assertEquals("unchecked", json.getString("storage_pressure"))
        assertTrue(json.isNull("failure_category"))
        assertTrue(json.isNull("usable_bytes"))
        assertTrue(json.isNull("total_bytes"))
        assertTrue(json.isNull("main_database_bytes"))
        assertTrue(json.isNull("checked_at_epoch_seconds"))
        assertEquals("not_run", json.getString("quick_check"))

        val sqliteUnknown = JSONObject(
            storageHealthMqttAttributes(
                snapshot(StorageHealthSeverity.HEALTHY).copy(pageCount = 0L, freelistCount = 0L),
            ),
        )
        assertTrue(sqliteUnknown.isNull("page_size_bytes"))
        assertTrue(sqliteUnknown.isNull("page_count"))
        assertTrue(sqliteUnknown.isNull("freelist_count"))
        assertTrue(sqliteUnknown.isNull("schema_version"))
        assertEquals(
            setOf("unchecked", "healthy", "warning", "critical", "database_failure"),
            StorageHealthSeverity.entries.mapTo(linkedSetOf()) { it.name.lowercase() },
        )
    }

    @Test fun completedUnknownCapacityRetainsDatabaseEvidenceInMqttAttributes() {
        val json = JSONObject(
            storageHealthMqttAttributes(
                snapshot(StorageHealthSeverity.UNCHECKED).copy(
                    pressureSeverity = StorageHealthSeverity.UNCHECKED,
                    usableBytes = 0L,
                    totalBytes = 0L,
                    usedPercent = null,
                ),
            ),
        )
        assertTrue(json.isNull("usable_bytes"))
        assertTrue(json.isNull("total_bytes"))
        assertTrue(json.isNull("used_percent"))
        assertEquals(800L, json.getLong("main_database_bytes"))
        assertEquals(44L, json.getLong("wal_bytes"))
        assertEquals(4_096L, json.getLong("page_size_bytes"))
        assertEquals(100L, json.getLong("page_count"))
        assertEquals(14, json.getInt("schema_version"))
        assertEquals("ok", json.getString("quick_check"))
        assertEquals(1_700_000_000L, json.getLong("checked_at_epoch_seconds"))
    }

    @Test fun discoveryAndLifecycleWiringUseTheSharedAuthority() {
        val panel = "test"
        val topic = "homeassistant/sensor/${panel}_storage_health/config"
        assertTrue(topic in mqttKnownConfigTopics(panel))
        assertTrue(
            mqttStalePanelCleanup("old", panel).any {
                it.topic == "homeassistant/sensor/old_storage_health/config" &&
                    it.payload.isEmpty() && it.retain
            },
        )

        val mqtt = source("MqttBridge.kt")
        val service = source("PaneldService.kt")
        assertTrue(mqtt.contains("\"sensor\", \"\${panel}_storage_health\""))
        assertTrue(mqtt.contains("\"entity_category\":\"diagnostic\""))
        assertTrue(mqtt.contains("stateConverger.reconcile(\"storage_health\", force = true)"))
        assertTrue(service.contains("storageHealth = StorageHealthRuntime::snapshot"))
        assertTrue(service.contains("StorageHealthRuntime.subscribe(::onStorageHealthSnapshot)"))
        assertTrue(service.contains("StorageHealthRuntime::subscribeDatabaseFailures"))
        assertTrue(service.contains("StorageHealthRecoveryLifecycle("))
        assertTrue(service.contains("delaysMs = STORAGE_HEALTH_RECOVERY_DELAYS_MS"))
        assertTrue(service.contains("longArrayOf(5_000L, 15_000L, 30_000L)"))
        val serviceStart = service.substring(
            service.indexOf("override fun onStartCommand"),
            service.indexOf("private fun startStorageHealthChecks"),
        )
        assertTrue(serviceStart.contains("startStorageHealthChecks()"))
        val recoverySetup = service.substring(
            service.indexOf("private fun startStorageHealthChecks"),
            service.indexOf("/** One bounded observation attempt"),
        )
        assertEquals(2, Regex("runQueuedStorageHealthObservation\\(\\)").findAll(recoverySetup).count())
        assertTrue(
            recoverySetup.contains(
                "storageHealthRecoveryAttemptComplete(runQueuedStorageHealthObservation())",
            ),
        )
        assertTrue(service.contains("intervalMs = STORAGE_HEALTH_CHECK_MS"))
        assertTrue(service.contains("initialDelayMs = 0L"))
        assertTrue(service.contains("repeat(STORAGE_HEALTH_CHECK_ATTEMPTS)"))
        assertTrue(service.contains("private const val STORAGE_HEALTH_CHECK_ATTEMPTS = 3"))
        assertTrue(service.contains("private const val STORAGE_HEALTH_RETRY_MS = 5_000L"))
        assertTrue(service.contains("kotlinx.coroutines.delay(STORAGE_HEALTH_RETRY_MS)"))
        val observationTokenAt = service.indexOf("val observationToken = StorageHealthRuntime.beginObservation()")
        val observationReadAt = service.indexOf("entityLearning.storageHealthObservation(cancellationSignal)")
        assertTrue(observationTokenAt >= 0)
        assertTrue(observationReadAt >= 0)
        assertTrue(observationTokenAt < observationReadAt)
        assertTrue(service.contains("StorageHealthRuntime.refresh(observation, observationToken)"))
        assertTrue(service.contains("storage health check failed (\${error.javaClass.simpleName})"))
        assertFalse(service.contains("Log.w(TAG, \"storage health check failed\", error)"))
        assertFalse(service.contains("failure.message"))
        assertFalse(service.contains("Log.w(TAG, \"storage health check failed\", failure)"))
        assertFalse(service.contains("nightly", ignoreCase = true))
        val storageSubscriptionCloseAt = service.indexOf("storageHealthSubscription?.close()")
        val scopeCancelAt = service.indexOf("scope.cancel()")
        assertTrue(storageSubscriptionCloseAt >= 0)
        assertTrue(scopeCancelAt >= 0)
        assertTrue(storageSubscriptionCloseAt < scopeCancelAt)
        assertTrue(service.indexOf("recoveryLifecycle?.close()") < scopeCancelAt)
        assertTrue(service.contains("synchronized(storageHealthLifecycleLock)"))
        val recoveryCloseAt = service.indexOf("recoveryLifecycle?.close()")
        val activeCheckCancelAt = service.indexOf("storageHealthObservationQueue.close()")
        assertTrue(recoveryCloseAt >= 0)
        assertTrue(activeCheckCancelAt >= 0)
        assertTrue(recoveryCloseAt < activeCheckCancelAt)
        val storageRuntime = source("storage/StorageHealth.kt")
        val runtimeStart = storageRuntime.indexOf("object StorageHealthRuntime")
        val failureRecorderStart = storageRuntime.indexOf(
            "fun recordDatabaseFailure(operation: String, throwable: Throwable)",
            runtimeStart,
        )
        val failureRecorder = storageRuntime.substring(
            failureRecorderStart,
            storageRuntime.indexOf("fun recordDatabaseWriteSuccess()", failureRecorderStart),
        )
        assertTrue(failureRecorder.contains("failureHub.recordDatabaseFailure"))
        assertTrue(activeCheckCancelAt < scopeCancelAt)
        assertTrue(service.contains("NotificationManager.IMPORTANCE_HIGH"))
        assertTrue(service.contains(".setOnlyAlertOnce(true)"))
        assertTrue(service.contains(".setSilent(true)"))
        val storageHealthNotification = service.substring(
            service.indexOf("private fun updateStorageHealthNotification"),
            service.indexOf("private fun notificationChannel"),
        )
        assertTrue(
            storageHealthNotification.contains(
                "storageHealthDestination.setClass(this, ConfigActivity::class.java)",
            ),
        )
        assertTrue(storageHealthNotification.contains("PendingIntent.FLAG_IMMUTABLE"))
        assertTrue(storageHealthNotification.contains("storageHealthDestination,"))
        assertTrue(service.contains("NotificationChannel(channelId, \"ha-paneld\", NotificationManager.IMPORTANCE_MIN)"))
        assertTrue(service.contains("private const val NOTIF_ID = 1"))
        assertTrue(service.contains("private const val STORAGE_HEALTH_NOTIF_ID = 2"))

        val server = source("http/PaneldServer.kt")
        val invalidatorStart = server.indexOf("internal fun invalidateStorageHealthDiagnostics()")
        assertTrue(invalidatorStart >= 0)
        val invalidatorEnd = server.indexOf("\n    }", invalidatorStart)
        val invalidator = server.substring(invalidatorStart, invalidatorEnd)
        assertTrue(invalidator.contains("diagCache.invalidate()"))
        assertFalse(invalidator.contains("snapCache.invalidate()"))
        assertFalse(invalidator.contains("densityCache.invalidate()"))
        val callbackStart = service.indexOf("private fun onStorageHealthSnapshot")
        val invalidateAt = service.indexOf("server.invalidateStorageHealthDiagnostics()", callbackStart)
        val notificationAt = service.indexOf("updateStorageHealthNotification(snapshot)", callbackStart)
        val mqttAt = service.indexOf("publishStorageHealth()", callbackStart)
        assertTrue(invalidateAt in (callbackStart + 1) until notificationAt)
        assertTrue(notificationAt < mqttAt)
    }

    private fun snapshot(
        severity: StorageHealthSeverity,
        pressure: StorageHealthSeverity = severity,
        usableBytes: Long = 900L,
        failureKind: StorageDatabaseFailureKind? = null,
    ): StorageHealthSnapshot = StorageHealthSnapshot(
        severity = severity,
        pressureSeverity = pressure,
        checkedAtMillis = 1_700_000_000_000L,
        usableBytes = usableBytes,
        totalBytes = 1_000L,
        usedPercent = (1_000L - usableBytes).toDouble() / 10.0,
        mainDatabaseBytes = 800L,
        walBytes = 44L,
        sidecarBytes = 33L,
        pageSizeBytes = 4_096L,
        pageCount = 100L,
        freelistCount = 4L,
        schemaVersion = 14,
        quickCheck = StorageQuickCheck.OK,
        databaseFailureKind = failureKind,
    )

    private fun observation(): StorageHealthObservation = StorageHealthObservation(
        checkedAtMillis = 1_700_000_000_000L,
        usableBytes = 900L,
        totalBytes = 1_000L,
        mainDatabaseBytes = 800L,
        walBytes = 44L,
        sidecarBytes = 33L,
        pageSizeBytes = 4_096L,
        pageCount = 100L,
        freelistCount = 4L,
        schemaVersion = 14,
        quickCheck = StorageQuickCheck.OK,
    )

    private fun source(name: String): String {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            File(working, "app/src/main/kotlin/io/github/maxlyth/hapaneld/$name"),
            File(working, "src/main/kotlin/io/github/maxlyth/hapaneld/$name"),
        ).first(File::isFile).readText()
    }
}
