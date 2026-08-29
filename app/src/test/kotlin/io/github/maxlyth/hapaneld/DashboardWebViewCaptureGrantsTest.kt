package io.github.maxlyth.hapaneld

import android.Manifest
import android.webkit.PermissionRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * The dashboard page may use the panel's camera freely, but it shares one microphone with
 * ha-paneld's own features and one Android capture client between them.
 */
class DashboardWebViewCaptureGrantsTest {

    private val allPermissionsHeld: (String) -> Boolean = { true }
    private val noPermissionsHeld: (String) -> Boolean = { false }

    @Test
    fun audioCaptureIsGrantedWhileTheSharedMicrophoneIsIdle() {
        assertArrayEquals(
            arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
            webViewCaptureGrants(
                requested = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
                permissionHeld = allPermissionsHeld,
                microphoneIdle = { true },
            ),
        )
    }

    @Test
    fun audioCaptureIsRefusedWhileSomethingHoldsTheMicrophone() {
        assertArrayEquals(
            "a page must not take the microphone away from a lease",
            emptyArray<String>(),
            webViewCaptureGrants(
                requested = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
                permissionHeld = allPermissionsHeld,
                microphoneIdle = { false },
            ),
        )
    }

    @Test
    fun audioCaptureIsRefusedWithoutTheAndroidPermission() {
        assertArrayEquals(
            emptyArray<String>(),
            webViewCaptureGrants(
                requested = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
                permissionHeld = { it != Manifest.permission.RECORD_AUDIO },
                microphoneIdle = { true },
            ),
        )
    }

    @Test
    fun videoCaptureIsRefusedOutright() {
        assertArrayEquals(
            "the camera has one owner and its HAL cannot share with a second",
            emptyArray<String>(),
            webViewCaptureGrants(
                requested = arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE),
                permissionHeld = allPermissionsHeld,
                microphoneIdle = { true },
            ),
        )
    }

    @Test
    fun aMixedRequestKeepsOnlyWhatIsAllowed() {
        assertArrayEquals(
            arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
            webViewCaptureGrants(
                requested = arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_AUDIO_CAPTURE),
                permissionHeld = allPermissionsHeld,
                microphoneIdle = { true },
            ),
        )
    }

    @Test
    fun everythingIsRefusedWithoutPermissions() {
        assertArrayEquals(
            emptyArray<String>(),
            webViewCaptureGrants(
                requested = arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_AUDIO_CAPTURE),
                permissionHeld = noPermissionsHeld,
                microphoneIdle = { true },
            ),
        )
    }

    @Test
    fun anUnknownResourceIsNeverGranted() {
        assertArrayEquals(
            emptyArray<String>(),
            webViewCaptureGrants(
                requested = arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID),
                permissionHeld = allPermissionsHeld,
                microphoneIdle = { true },
            ),
        )
    }
}
