package io.github.maxlyth.hapaneld.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reclamation admission policy: when a bounded freelist pass may run at all.
 *
 * Reclamation costs space before it returns any — relocated pages become WAL frames and the file
 * only shrinks at the checkpoint afterwards — so the interesting cases are the refusals.
 */
class ReclamationAdmissionTest {
    private val pageSize = 4_096L
    private val maxPages = 5_120L
    private val margin = 64L * 1024L * 1024L

    /** One pass's own transient cost plus the margin: the exact bar [reclamationAdmitted] sets. */
    private val required = maxPages * pageSize + margin

    private fun admitted(
        severity: StorageHealthSeverity = StorageHealthSeverity.HEALTHY,
        usableBytes: Long = required,
        pageSizeBytes: Long = pageSize,
    ) = reclamationAdmitted(severity, usableBytes, pageSizeBytes, maxPages, margin)

    @Test fun ampleHeadroomAdmitsThePass() {
        assertTrue(admitted(usableBytes = required * 4))
    }

    @Test fun exactlyEnoughHeadroomAdmitsThePass() {
        assertTrue("the bar is inclusive; one byte of slack is not required", admitted(usableBytes = required))
    }

    @Test fun oneByteBelowTheBarRefusesThePass() {
        assertFalse(admitted(usableBytes = required - 1L))
    }

    @Test fun aPassIsRefusedWhenItsOwnTransientCostAloneWouldNotFit() {
        assertFalse("the slice budget itself must fit before the margin is even considered",
            admitted(usableBytes = maxPages * pageSize - 1L))
    }

    @Test fun criticalPressureRefusesEvenWithVastHeadroom() {
        // Free bytes are not the only way to be in trouble: WAL growth and a percentage ceiling also
        // raise CRITICAL, and none of them is a good moment to start writing relocation frames.
        assertFalse(admitted(severity = StorageHealthSeverity.CRITICAL, usableBytes = Long.MAX_VALUE))
    }

    @Test fun aLatchedDatabaseFailureRefusesEvenWithVastHeadroom() {
        assertFalse(
            admitted(severity = StorageHealthSeverity.DATABASE_FAILURE, usableBytes = Long.MAX_VALUE),
        )
    }

    @Test fun warningPressureStillAdmitsWhenTheSpaceIsThere() {
        // WARNING is where reclamation is most useful; refusing here would leave the panel to drift
        // into CRITICAL with the freelist it could have returned.
        assertTrue(admitted(severity = StorageHealthSeverity.WARNING, usableBytes = required))
    }

    @Test fun warningPressureWithoutTheSpaceIsStillRefused() {
        assertFalse(admitted(severity = StorageHealthSeverity.WARNING, usableBytes = required - 1L))
    }

    @Test fun anUnavailableCapacityReadingAdmitsThePass() {
        // Deliberate fail-open, and the only one: a probe that could not read the filesystem is not
        // evidence the filesystem is full, and the pass is bounded either way.
        assertTrue(admitted(usableBytes = 0L))
    }

    @Test fun anUnavailableCapacityReadingDoesNotOverrideCriticalPressure() {
        assertFalse(admitted(severity = StorageHealthSeverity.CRITICAL, usableBytes = 0L))
    }

    @Test fun anUnknownPageSizeAdmitsThePassRatherThanComputingANonsenseBar() {
        assertTrue(admitted(pageSizeBytes = 0L))
        assertTrue(admitted(pageSizeBytes = -1L))
    }

    @Test fun anAbsurdPageSizeCannotOverflowIntoAdmission() {
        // A saturating computation, not a wrapping one: a bar that overflowed to a negative number
        // would admit every pass precisely when the arithmetic said it should refuse.
        assertFalse(
            reclamationAdmitted(
                StorageHealthSeverity.HEALTHY,
                usableBytes = Long.MAX_VALUE - 1L,
                pageSizeBytes = Long.MAX_VALUE,
                maxPagesPerPass = maxPages,
                marginBytes = margin,
            ),
        )
    }

    @Test fun uncheckedAndHealthyBothAdmitWhenTheSpaceIsThere() {
        assertTrue(admitted(severity = StorageHealthSeverity.UNCHECKED))
        assertTrue(admitted(severity = StorageHealthSeverity.HEALTHY))
    }
}
