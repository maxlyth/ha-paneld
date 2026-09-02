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
            "last_frame_age_ms", "consecutive_failures", "indication", "live",
            "stream_clients", "stream_port", "encoder", "encode_width", "encode_height", "encode_fps", "encode_kbps",
            "delivered_fps", "delivered_kbps", "summary", "action",
        ).forEach { assertTrue("missing $it", j.has(it)) }
        assertEquals("absent", j.getString("state"))
        assertEquals("none", j.getString("fault"))
        assertTrue(j.isNull("fault_detail"))
        assertTrue(j.isNull("last_frame_age_ms"))
        assertFalse(j.getBoolean("live"))
        assertEquals(0, j.getInt("stream_clients"))
        listOf("stream_port", "encoder", "encode_width", "encode_height", "encode_fps", "encode_kbps", "delivered_fps", "delivered_kbps")
            .forEach { assertTrue("$it is null while nothing streams", j.isNull(it)) }
    }

    @Test fun streamFactsAreCarriedAndTheDeliveredRateIsRoundedToOneDecimal() {
        val p = CameraPresentation.absent().copy(
            state = CameraState.LIVE, clients = 2, streamClients = 1, streamPort = 8554, encoder = "OMX.rk.video_encoder.avc",
            encodeWidth = 1280, encodeHeight = 720, encodeFps = 15, encodeKbps = 2_000, deliveredFps = 14.96, deliveredKbps = 1_870,
        )
        val j = JSONObject(p.statusJson())
        // Presence first, as assertions: a field that stops being emitted must fail this test by
        // assertion, not by the JSON accessor throwing.
        listOf("stream_clients", "stream_port", "encoder", "encode_width", "encode_height", "encode_fps", "encode_kbps", "delivered_fps", "delivered_kbps")
            .forEach { assertTrue("missing $it", j.has(it)) }
        assertEquals(1, j.getInt("stream_clients"))
        assertEquals(8554, j.getInt("stream_port"))
        assertEquals("OMX.rk.video_encoder.avc", j.getString("encoder"))
        assertEquals(1280, j.getInt("encode_width"))
        assertEquals(720, j.getInt("encode_height"))
        assertEquals(15, j.getInt("encode_fps"))
        assertEquals(2_000, j.getInt("encode_kbps"))
        assertEquals(15.0, j.getDouble("delivered_fps"), 0.0)
        assertEquals(1_870, j.getInt("delivered_kbps"))
        val line = p.diagnosticLine()
        assertTrue(line, line.contains(" stream_clients=1 stream_port=8554 encoder=OMX.rk.video_encoder.avc encode=1280x720@15/2000kbps delivered=15.0fps/1870kbps"))
        assertFalse("the dump carries the port but never an address or URL", line.contains("rtsp://"))
    }

    @Test fun permissionNeededCarriesTheListeningPortSoAUserSeesTheStreamIsWaitingOnThem() {
        assertEquals(8554, CameraPresentation.permissionNeeded(streamPort = 8554).streamPort)
        assertNull(CameraPresentation.permissionNeeded().streamPort)
        assertNull("off means not listening", CameraPresentation.disabled().streamPort)
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
        assertTrue(line.contains("stream_port=off"))
        assertTrue(line.contains("encoder=none encode=none delivered=none"))
    }

    @Test fun diagnosticLineFormatsAKnownLastFrameAge() {
        val line = CameraPresentation.absent().copy(state = CameraState.LIVE, lastFrameAgeMs = 65_000L).diagnosticLine()
        assertFalse(line.contains("\n"))
        assertTrue(line.contains("last_frame=1m5s"))
    }

    // --- CameraOutcome: the reset at the master switch ----------------------------------------------
    //
    // The decision alone; `CameraSessionStateTest` drives the real session that supplies its argument.

    /**
     * The trial residual (2026-09-01): off, then on, with nobody watching and nothing else refusing.
     * Status must say so before any frame arrives, without claiming one.
     */
    @Test fun disableThenEnableBeforeAnyViewerReadsOkAndClaimsNoFrame() {
        val outcome = CameraOutcome.onEnable(CameraRefusal.DISABLED.token, retained = null)
        assertEquals(CameraOutcome.OK, outcome)
        val p = CameraPresentation.absent().copy(state = CameraState.IDLE, outcome = outcome, lastFrameAgeMs = null)
        val j = JSONObject(p.statusJson())
        assertEquals("idle", j.getString("state"))
        assertEquals("ok", j.getString("outcome"))
        assertTrue("no frame is fabricated by the reset", j.isNull("last_frame_age_ms"))
        assertFalse(j.getBoolean("live"))
    }

    /** An enable that is not an edge — any camera key change reaches the owner — must be a no-op. */
    @Test fun enableIsIdempotentOnAnOutcomeThatIsAlreadyOk() {
        assertEquals(CameraOutcome.OK, CameraOutcome.onEnable(CameraOutcome.OK, retained = null))
        assertEquals(CameraOutcome.OK, CameraOutcome.onEnable(CameraOutcome.OK, retained = CameraRefusal.FAILED))
    }

    /**
     * Off, then on, with the Android permission still missing. The reset does not stamp the permission
     * refusal — that is the presentation's short-circuit and the lease gate's job — and what a consumer
     * sees is the permission state with its own token, not `ok` and not `camera-disabled`.
     */
    @Test fun disableThenEnableWithMissingPermissionShowsThePermissionStateNotTheSwitch() {
        assertEquals(
            "the reset never invents a permission refusal; the gate stamps it when a consumer asks",
            CameraOutcome.OK,
            CameraOutcome.onEnable(CameraRefusal.DISABLED.token, retained = null),
        )
        val p = CameraPresentation.permissionNeeded()
        assertEquals(CameraState.PERMISSION_NEEDED, p.state)
        assertEquals(CameraRefusal.PERMISSION.token, p.outcome)
        assertTrue(p.diagnosticLine().contains("state=permission_needed outcome=camera-permission-needed"))
    }

    /** Every refusal the switch did not cause stands, whatever the session retains. */
    @Test fun enablePreservesEveryRefusalTheSwitchDidNotCause() {
        CameraRefusal.entries.filter { it != CameraRefusal.DISABLED }.forEach { refusal ->
            assertEquals(refusal.name, refusal.token, CameraOutcome.onEnable(refusal.token, retained = null))
            assertEquals("$refusal, still refusing", refusal.token, CameraOutcome.onEnable(refusal.token, CameraRefusal.FAILED))
        }
    }

    /** A refusal that still stands is restated in place of the switch's, never cleared. */
    @Test fun enableRestatesWhateverTheSessionStillRefuses() {
        CameraRefusal.entries.forEach { retained ->
            assertEquals(
                "$retained still refuses, so the switch may not report a clear camera",
                retained.token,
                CameraOutcome.onEnable(CameraRefusal.DISABLED.token, retained),
            )
        }
    }

    /** Status and the diag line are one projection: the reset cannot show in one and not the other. */
    @Test fun statusAndDiagnosticLineAgreeOnTheResetOutcome() {
        listOf(
            CameraOutcome.onEnable(CameraRefusal.DISABLED.token, retained = null),
            CameraOutcome.onEnable(CameraRefusal.DISABLED.token, CameraRefusal.STREAM_ENCODER),
            CameraOutcome.onEnable(CameraRefusal.STARVED.token, retained = null),
        ).forEach { outcome ->
            val p = CameraPresentation.absent().copy(state = CameraState.IDLE, outcome = outcome)
            assertEquals(outcome, JSONObject(p.statusJson()).getString("outcome"))
            assertTrue(p.diagnosticLine(), p.diagnosticLine().contains(" outcome=$outcome "))
        }
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
