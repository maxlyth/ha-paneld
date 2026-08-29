package io.github.maxlyth.hapaneld.camera

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The two small rules the Android-bound indicator and activity lean on, pinned without Android. */
class CameraIndicationAndPromptTest {

    @Before fun resetPrompt() = CameraPermissionPrompt.reset()
    @After fun clearPrompt() = CameraPermissionPrompt.reset()

    @Test fun theOverlayAloneIsPositiveOnlyWhileTheScreenIsLit() {
        assertTrue(CameraIndicationPolicy.positive(overlayAttached = true, screenOff = false, ledLit = false))
        assertFalse("a dark screen with no LED is not telling the room", CameraIndicationPolicy.positive(overlayAttached = true, screenOff = true, ledLit = false))
        assertTrue(CameraIndicationPolicy.positive(overlayAttached = true, screenOff = true, ledLit = true))
        assertFalse("no overlay is never positive, lit LED or not", CameraIndicationPolicy.positive(overlayAttached = false, screenOff = false, ledLit = true))
    }

    @Test fun theReportedRouteNamesWhatIsActuallyLit() {
        assertEquals(CameraIndication.NONE, CameraIndicationPolicy.route(overlayAttached = false, ledLit = false))
        assertEquals(CameraIndication.OVERLAY, CameraIndicationPolicy.route(overlayAttached = true, ledLit = false))
        assertEquals(CameraIndication.LED, CameraIndicationPolicy.route(overlayAttached = true, ledLit = true))
    }

    @Test fun thePromptIsAskedOnlyWhenTheOwnerWantsItAndNotWhileInFlight() {
        assertFalse(CameraPermissionPrompt.shouldAsk())
        CameraPermissionPrompt.publish(wantsPermission = true, freshEnable = false)
        assertTrue(CameraPermissionPrompt.shouldAsk())
        CameraPermissionPrompt.asking()
        assertFalse("the dialog is up; do not ask again", CameraPermissionPrompt.shouldAsk())
    }

    @Test fun aDenialIsRememberedUntilAFreshEnable() {
        CameraPermissionPrompt.publish(wantsPermission = true, freshEnable = false)
        CameraPermissionPrompt.asking()
        CameraPermissionPrompt.answered(granted = false)
        assertFalse("denied: no re-ask on the next resume or recreation", CameraPermissionPrompt.shouldAsk())
        CameraPermissionPrompt.publish(wantsPermission = true, freshEnable = false)
        assertFalse("still denied after a republish that is not a fresh enable", CameraPermissionPrompt.shouldAsk())
        CameraPermissionPrompt.publish(wantsPermission = true, freshEnable = true)
        assertTrue("a fresh enable is the one event that asks again", CameraPermissionPrompt.shouldAsk())
    }

    @Test fun aGrantEndsTheWantAndACameralessOwnerNeverWants() {
        CameraPermissionPrompt.publish(wantsPermission = true, freshEnable = false)
        CameraPermissionPrompt.asking()
        CameraPermissionPrompt.answered(granted = true)
        assertFalse(CameraPermissionPrompt.shouldAsk())
        CameraPermissionPrompt.publish(wantsPermission = false, freshEnable = true)
        assertFalse("the owner of a camera-less profile publishes no want", CameraPermissionPrompt.shouldAsk())
    }
}
