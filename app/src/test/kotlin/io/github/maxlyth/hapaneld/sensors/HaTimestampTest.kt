package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaTimestampTest {
    @Test fun `parses Home Assistant UTC offset form on API 27 compatible path`() {
        assertEquals(1_754_044_530_123L, parseHaTimestampEpochMs("2025-08-01T10:35:30.123456+00:00"))
    }

    @Test fun `parses Z and nonzero offset forms to the same instant`() {
        val expected = parseHaTimestampEpochMs("2026-07-16T09:42:00Z")

        assertEquals(expected, parseHaTimestampEpochMs("2026-07-16T09:42:00+00:00"))
        assertEquals(expected, parseHaTimestampEpochMs("2026-07-16T10:42:00+01:00"))
    }

    @Test fun `rejects missing and malformed timestamps`() {
        assertNull(parseHaTimestampEpochMs(""))
        assertNull(parseHaTimestampEpochMs("2026-07-16 09:42:00"))
    }

    @Test fun `production code cannot bypass the API 27 compatible timestamp parser`() {
        val offenders = TestSources.appDir("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { "Instant.parse(" in it.readText() }
            .map { it.relativeTo(TestSources.appDir("src/main/kotlin")).invariantSeparatorsPath }
            .toList()

        assertTrue("Production Instant.parse bypasses shared offset parser: $offenders", offenders.isEmpty())
    }
}
