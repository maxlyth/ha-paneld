package io.github.maxlyth.hapaneld.camera

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure behaviour of the projection that `GET /api/v1/status` and `/api/v1/diag` both render.
 * `CameraSurfaceContractTest` binds it to those two surfaces and to the OpenAPI document; this file
 * proves the projection itself, without an Activity, a camera device or a running server.
 */
class CameraPresentationTest {

    @Test fun absentReportsTheAbsentStateAndItsToken() {
        val p = CameraPresentation.absent()
        assertEquals(CameraState.ABSENT, p.state)
        assertEquals(CameraRefusal.ABSENT.token, p.outcome)
        assertEquals(CameraFault.NONE, p.fault)
        assertNull(p.faultDetail)
        assertEquals(0, p.clients)
        assertNull(p.lastFrameAgeMs)
        assertEquals(CameraIndication.NONE, p.indication)
        assertTrue(p.summary.contains("no camera"))
        assertEquals("none", p.action)
    }

    @Test fun disabledReportsTheDisabledStateAndItsToken() {
        val p = CameraPresentation.disabled()
        assertEquals(CameraState.DISABLED, p.state)
        assertEquals(CameraRefusal.DISABLED.token, p.outcome)
        assertEquals(CameraFault.NONE, p.fault)
        assertTrue("must guide the user to the setting", p.action.contains("camera setting"))
    }

    @Test fun permissionNeededReportsTheClassifiedPermissionFault() {
        val p = CameraPresentation.permissionNeeded()
        assertEquals(CameraState.PERMISSION_NEEDED, p.state)
        assertEquals(CameraRefusal.PERMISSION.token, p.outcome)
        assertEquals(CameraFault.PERMISSION, p.fault)
        assertTrue(p.action.contains("permission"))
    }

    @Test fun statusJsonParsesAndCarriesEveryField() {
        val j = JSONObject(CameraPresentation.absent().statusJson())
        listOf(
            "state", "outcome", "fault", "fault_detail", "recovery", "clients",
            "last_frame_age_ms", "consecutive_failures", "indication", "live", "summary", "action",
        ).forEach { assertTrue("missing $it", j.has(it)) }
        assertEquals("absent", j.getString("state"))
        assertEquals("none", j.getString("fault"))
        assertTrue(j.isNull("fault_detail"))
        assertTrue(j.isNull("last_frame_age_ms"))
        assertFalse(j.getBoolean("live"))
    }

    @Test fun liveIsTrueOnlyForTheLiveState() {
        CameraState.entries.forEach { state ->
            val json = JSONObject(CameraPresentation.absent().copy(state = state).statusJson())
            assertEquals("$state", state == CameraState.LIVE, json.getBoolean("live"))
        }
    }

    @Test fun diagnosticLineIsOneLineAndSelfLabellingAndCarriesNoNewline() {
        val line = CameraPresentation.disabled().diagnosticLine()
        assertTrue(line.startsWith("[camera]"))
        assertFalse(line.contains("\n"))
        assertTrue(line.contains("state=disabled"))
        assertTrue(line.contains("outcome=camera-disabled"))
        assertTrue(line.contains("last_frame=never"))
        assertTrue(line.contains("indication=none"))
    }

    @Test fun diagnosticLineFormatsAKnownLastFrameAge() {
        val line = CameraPresentation.absent().copy(state = CameraState.LIVE, lastFrameAgeMs = 65_000L).diagnosticLine()
        assertFalse(line.contains("\n"))
        assertTrue(line.contains("last_frame=1m5s"))
    }

    // --- CameraResolution -----------------------------------------------------------------------

    @Test fun parseRejectsAnyValueOutsideTheClosedVocabulary() {
        assertNull(CameraResolution.parse(null))
        assertNull(CameraResolution.parse(""))
        assertNull(CameraResolution.parse("4k"))
        assertNull(CameraResolution.parse("720P"))
        assertNull(CameraResolution.parse(" 720p"))
        assertEquals(CameraResolution.P480, CameraResolution.parse("480p"))
        assertEquals(CameraResolution.P720, CameraResolution.parse("720p"))
        assertEquals(CameraResolution.P1080, CameraResolution.parse("1080p"))
    }

    @Test fun clampNeverExceedsTheCapAndNeverRaisesBelowTheRequest() {
        assertEquals(CameraResolution.P480, CameraResolution.clamp(CameraResolution.P1080, CameraResolution.P480))
        assertEquals(CameraResolution.P720, CameraResolution.clamp(CameraResolution.P720, CameraResolution.P1080))
        assertEquals(CameraResolution.P1080, CameraResolution.clamp(CameraResolution.P1080, CameraResolution.P1080))
        // A request for less than the cap is honoured, never raised to the ceiling.
        assertEquals(CameraResolution.P480, CameraResolution.clamp(CameraResolution.P480, CameraResolution.P1080))
    }

    // --- fmtAge -----------------------------------------------------------------------------------

    @Test fun ageFormattingCoversSecondsMinutesAndHours() {
        assertEquals("0s", CameraPresentation.fmtAge(0L))
        assertEquals("23s", CameraPresentation.fmtAge(23_000L))
        assertEquals("59s", CameraPresentation.fmtAge(59_000L))
        assertEquals("1m0s", CameraPresentation.fmtAge(60_000L))
        assertEquals("1m5s", CameraPresentation.fmtAge(65_000L))
        assertEquals("47m59s", CameraPresentation.fmtAge((47 * 60 + 59) * 1000L))
        assertEquals("1h0m", CameraPresentation.fmtAge(3_600_000L))
        assertEquals("5h12m", CameraPresentation.fmtAge((5 * 3600 + 12 * 60) * 1000L))
    }
}
