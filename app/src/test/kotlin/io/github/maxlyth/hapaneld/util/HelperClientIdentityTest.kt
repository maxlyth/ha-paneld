package io.github.maxlyth.hapaneld.util

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Test

class HelperClientIdentityTest {
    @Test
    fun parsesCurrentAndForwardCompatibleIdentity() {
        assertEquals(
            HelperIdentity(version = "1.0.0", protocolMajor = 1, protocolMinor = 0),
            parseHelperIdentity("HELPER version=1.0.0 proto=1.0"),
        )
        assertEquals(
            HelperIdentity(version = "12.34.56", protocolMajor = 1, protocolMinor = 9),
            parseHelperIdentity("HELPER version=12.34.56 proto=1.9 build=0123456789ab"),
        )
    }

    @Test
    fun rejectsMalformedOrAmbiguousIdentity() {
        val malformed = listOf(
            "",
            "HELPER",
            "helper version=1.0.0 proto=1.0",
            "HELPER  version=1.0.0 proto=1.0",
            "HELPER version=1.0 proto=1.0",
            "HELPER version=01.0.0 proto=1.0",
            "HELPER version=1.0.0",
            "HELPER version=1.0.0 proto=1",
            "HELPER version=1.0.0 proto=1.0 proto=1.1",
            "HELPER version=1.0.0 proto=99999999999999999999.0",
            "HELPER version=1.0.0 proto=1.0\n",
            "X".repeat(129),
        )
        malformed.forEach { assertNull(parseHelperIdentity(it), it) }
    }

    @Test
    fun classifiesCurrentHelperAsCompatibleWithoutPingFallback() {
        val requests = mutableListOf<String>()

        val status = probeHelperIdentity { command ->
            requests += command
            "HELPER version=1.0.0 proto=1.7"
        }

        assertEquals(
            HelperIdentityStatus.Compatible(HelperIdentity("1.0.0", 1, 7)),
            status,
        )
        assertEquals(listOf("VERSION"), requests)
    }

    @Test
    fun classifiesPingOnlyHelperAsReachableUnverified() {
        val requests = mutableListOf<String>()

        val status = probeHelperIdentity { command ->
            requests += command
            when (command) {
                "VERSION" -> "ERR"
                "PING" -> "OK"
                else -> null
            }
        }

        assertEquals(HelperIdentityStatus.ReachableUnverified, status)
        assertEquals(listOf("VERSION", "PING"), requests)
    }

    @Test
    fun distinguishesMissingMalformedAndUnsupportedHelpers() {
        assertEquals(HelperIdentityStatus.Missing, probeHelperIdentity { null })

        val malformed = assertIs<HelperIdentityStatus.Incompatible>(probeHelperIdentity { "HELLO" })
        assertEquals(HelperIdentityIssue.MALFORMED_IDENTITY, malformed.issue)
        assertNull(malformed.identity)

        val unsupported = assertIs<HelperIdentityStatus.Incompatible>(
            probeHelperIdentity { "HELPER version=2.0.0 proto=2.0" },
        )
        assertEquals(HelperIdentityIssue.UNSUPPORTED_PROTOCOL, unsupported.issue)
        assertEquals(2, unsupported.identity?.protocolMajor)
    }

    @Test
    fun legacyErrWithoutSuccessfulPingIsMissing() {
        assertEquals(
            HelperIdentityStatus.Missing,
            probeHelperIdentity { command -> if (command == "VERSION") "ERR" else null },
        )
    }
}
