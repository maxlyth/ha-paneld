package io.github.maxlyth.hapaneld.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ABSENT` and `FAILED` deliberately share the wire token `camera-unavailable`, because a consumer
 * reading an HTTP body has no use for the difference between a board with no camera and a board whose
 * camera would not open. The panel does: one is a settled fact about the hardware and the other is a
 * recoverable fault. Recovering the identifier from the token therefore needs the capability as its
 * second input, and a scan of declaration order — which is what the owner used to do — silently
 * answers `ABSENT` for every stored `camera-unavailable`, telling a panel that has a camera it has none.
 */
class CameraRefusalTokenTest {

    @Test fun theSharedTokenIsAFailureOnAPanelThatHasACamera() {
        assertEquals(
            "a camera-bearing panel storing camera-unavailable has a fault, not absent hardware",
            CameraRefusal.FAILED,
            CameraRefusal.fromToken(CameraRefusal.FAILED.token, cameraPresent = true),
        )
    }

    @Test fun theSharedTokenIsAbsenceOnAPanelWithNoCamera() {
        assertEquals(
            CameraRefusal.ABSENT,
            CameraRefusal.fromToken(CameraRefusal.ABSENT.token, cameraPresent = false),
        )
    }

    @Test fun everyOtherTokenResolvesToItselfWhateverTheCapabilitySays() {
        val shared = setOf(CameraRefusal.ABSENT, CameraRefusal.FAILED)
        CameraRefusal.entries.filterNot { it in shared }.forEach { refusal ->
            assertEquals(refusal, CameraRefusal.fromToken(refusal.token, cameraPresent = true))
            assertEquals(refusal, CameraRefusal.fromToken(refusal.token, cameraPresent = false))
        }
    }

    @Test fun anUnrecognisedTokenIsAFailureRatherThanAbsentHardware() {
        assertEquals(CameraRefusal.FAILED, CameraRefusal.fromToken("camera-something-new", cameraPresent = true))
        assertEquals(CameraRefusal.FAILED, CameraRefusal.fromToken("", cameraPresent = false))
    }

    @Test fun onlyAbsentHardwareAnswersNotFoundAndEveryFaultAnswersServiceUnavailable() {
        assertEquals(404, CameraRefusal.snapshotStatusCode(CameraRefusal.ABSENT))
        CameraRefusal.entries.filterNot { it == CameraRefusal.ABSENT }.forEach { refusal ->
            assertEquals(
                "${refusal.name} is a refusal by a panel that has a camera",
                503,
                CameraRefusal.snapshotStatusCode(refusal),
            )
        }
    }
}
