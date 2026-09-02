package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.storage.StorageAutoVacuumMode
import io.github.maxlyth.hapaneld.storage.StorageDatabaseFailureKind
import io.github.maxlyth.hapaneld.storage.StorageHealthSeverity
import io.github.maxlyth.hapaneld.storage.StorageHealthSnapshot
import io.github.maxlyth.hapaneld.storage.StorageQuickCheck
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A latched `database_failure` must say which operation failed.
 *
 * The `database_failure=unknown` events reported against Issue #91 named an outcome and nothing
 * else. The operation was in fact captured and sanitized all along — it simply reached no surface,
 * so a report of one could not distinguish a maintenance pass from a settings write, and neither
 * could anyone reading it back. These tests cover the rendering; the sibling privacy assertions in
 * `StorageHealthHttpProjectionTest` and `StorageHealthRuntimeSurfaceTest` cover what must never
 * reach a surface at all.
 */
class StorageFailureAttributionTest {
    private fun snapshot(
        kind: StorageDatabaseFailureKind? = StorageDatabaseFailureKind.UNKNOWN,
        operation: String? = "catalog-maintenance",
        autoVacuum: StorageAutoVacuumMode = StorageAutoVacuumMode.INCREMENTAL,
        severity: StorageHealthSeverity = StorageHealthSeverity.DATABASE_FAILURE,
    ) = StorageHealthSnapshot(
        severity = severity,
        pressureSeverity = StorageHealthSeverity.HEALTHY,
        checkedAtMillis = 1_700_000_000_000L,
        usableBytes = 900L * 1024L * 1024L,
        totalBytes = 4L * 1024L * 1024L * 1024L,
        usedPercent = 40.0,
        mainDatabaseBytes = 20L * 1024L * 1024L,
        walBytes = 512L * 1024L,
        sidecarBytes = 32L * 1024L,
        pageSizeBytes = 4_096L,
        pageCount = 4_739L,
        freelistCount = 2_354L,
        schemaVersion = 14,
        quickCheck = StorageQuickCheck.OK,
        autoVacuumMode = autoVacuum,
        databaseFailureKind = kind,
        databaseFailureOperation = operation,
    )

    @Test fun theFailingOperationReachesTheStatusJsonAndTheDiagnosticLine() {
        val presentation = HealthAudit.storage(snapshot())
        val json = JSONObject(presentation.statusJson())

        assertEquals("unknown", json.getString("failure"))
        assertEquals(
            "an unknown outcome is exactly when the operation is the only usable detail",
            "catalog-maintenance",
            json.getString("failure_operation"),
        )
        assertTrue(presentation.diagnosticLine().contains("failure_operation=catalog-maintenance"))
    }

    @Test fun theFailingOperationIsNamedInTheOperatorFacingSummary() {
        // The machine fields serve a diagnostic dump; the summary is what someone actually pastes.
        assertTrue(
            HealthAudit.storage(snapshot()).summary.contains("during catalog-maintenance"),
        )
        assertTrue(
            HealthAudit.storage(snapshot(kind = StorageDatabaseFailureKind.BUSY))
                .summary.contains("during catalog-maintenance"),
        )
    }

    @Test fun everyFailureKindNamesItsOperation() {
        for (kind in StorageDatabaseFailureKind.entries) {
            val presentation = HealthAudit.storage(snapshot(kind = kind, operation = "app-state-write"))
            assertTrue(
                "$kind must name its operation in the summary",
                presentation.summary.contains("app-state-write"),
            )
            assertEquals(
                "$kind must name its operation in the status JSON",
                "app-state-write",
                JSONObject(presentation.statusJson()).getString("failure_operation"),
            )
        }
    }

    @Test fun anOperationOutsideTheClosedVocabularyIsReducedRatherThanRendered() {
        // Capture sanitizes, but `copy` reaches the field directly, so the render boundary reduces
        // again. Anything unrecognized becomes the generic label — a path or a query fragment can
        // never reach a user-visible string by construction.
        val presentation = HealthAudit.storage(
            snapshot(operation = "SELECT secret FROM /data/user/0/private.db"),
        )
        val rendered = presentation.statusJson() + presentation.summary + presentation.diagnosticLine()

        assertEquals("database", JSONObject(presentation.statusJson()).getString("failure_operation"))
        assertFalse(rendered.contains("secret"))
        assertFalse(rendered.contains("/data/"))
        assertFalse(rendered.contains("SELECT"))
    }

    @Test fun aFailureWithNoRecordedOperationSaysSoRatherThanInventingOne() {
        val presentation = HealthAudit.storage(snapshot(operation = null))

        assertTrue(presentation.diagnosticLine().contains("failure_operation=none"))
        assertFalse(
            "an absent operation must not produce a dangling 'during'",
            presentation.summary.contains("during"),
        )
        assertFalse(JSONObject(presentation.statusJson()).has("failure_operation"))
    }

    @Test fun aBlankOperationIsTreatedAsAbsentRatherThanRenderedEmpty() {
        val presentation = HealthAudit.storage(snapshot(operation = "   "))

        assertTrue(presentation.diagnosticLine().contains("failure_operation=none"))
        assertFalse(presentation.summary.contains("during"))
    }

    @Test fun theAutoVacuumModeIsAlwaysReportedSoALargeFreelistCanBeInterpreted() {
        // Without it, a big freelist_count is ambiguous: reclamation lagging, or a database on which
        // bounded reclamation can never run at all.
        for (mode in StorageAutoVacuumMode.entries) {
            val presentation = HealthAudit.storage(
                snapshot(autoVacuum = mode, severity = StorageHealthSeverity.HEALTHY, kind = null),
            )
            val expected = mode.name.lowercase()
            assertEquals(expected, JSONObject(presentation.statusJson()).getString("auto_vacuum"))
            assertTrue(presentation.diagnosticLine().contains("auto_vacuum=$expected"))
        }
    }

    @Test fun aHealthySnapshotCarriesNoFailureOperationField() {
        val presentation = HealthAudit.storage(
            snapshot(severity = StorageHealthSeverity.HEALTHY, kind = null, operation = null),
        )
        val json = JSONObject(presentation.statusJson())

        assertFalse(json.has("failure"))
        assertFalse(json.has("failure_operation"))
        assertTrue(presentation.diagnosticLine().contains("failure=none"))
    }
}
