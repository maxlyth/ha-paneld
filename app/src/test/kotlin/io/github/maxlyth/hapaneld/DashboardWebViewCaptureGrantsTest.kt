package io.github.maxlyth.hapaneld

import android.Manifest
import android.webkit.PermissionRequest
import io.github.maxlyth.hapaneld.audio.MicrophoneAdmission
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dashboard page gets neither capture device by default, for two different reasons.
 *
 * The camera is refused outright: one owner holds it and the panel HALs cannot share it with a
 * second. The microphone is shared with ha-paneld's own features and recording is something the
 * panel's owner has to have asked for — and since provisioning grants `RECORD_AUDIO` to every
 * panel, "we hold the permission" says nothing about intent. These tests pin the consequence: audio
 * capture is refused unless an explicit admission says otherwise, and that admission is refused by
 * default.
 */
class DashboardWebViewCaptureGrantsTest {

    private val allPermissionsHeld: (String) -> Boolean = { true }
    private val noPermissionsHeld: (String) -> Boolean = { false }
    private val audio = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
    private val video = arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)

    @After
    fun restoreAdmissionDefaults() {
        MicrophoneAdmission.reset()
    }

    private fun grants(
        requested: Array<String>,
        permissionHeld: (String) -> Boolean = allPermissionsHeld,
        idle: Boolean = true,
        allowed: Boolean = true,
    ) = webViewCaptureGrants(requested, permissionHeld, { idle }, { allowed })

    // ---- the opt-in is the gate, and it is shut ---------------------------------------------------

    @Test
    fun audioCaptureIsRefusedWithoutAnExplicitOptIn() {
        assertArrayEquals(
            "a granted Android permission is not a decision anybody made about this page",
            emptyArray<String>(),
            grants(audio, allowed = false),
        )
    }

    @Test
    fun theShippedAdmissionRefusesWebViewCapture() {
        MicrophoneAdmission.reset()
        assertFalse(
            "no feature owns a microphone opt-in yet, so the default must be a refusal",
            MicrophoneAdmission.webViewCaptureAllowed(),
        )
        assertArrayEquals(
            "the default admission denies audio capture even on an idle, fully permitted panel",
            emptyArray<String>(),
            webViewCaptureGrants(
                audio,
                allPermissionsHeld,
                MicrophoneAdmission.isIdle,
                MicrophoneAdmission.webViewCaptureAllowed,
            ),
        )
    }

    @Test
    fun anOptInAloneIsNotEnoughWhileTheMicrophoneIsHeld() {
        assertArrayEquals(
            "a page must not take the microphone away from a lease, opt-in or not",
            emptyArray<String>(),
            grants(audio, idle = false),
        )
    }

    @Test
    fun anOptInAloneIsNotEnoughWithoutTheAndroidPermission() {
        assertArrayEquals(
            emptyArray<String>(),
            grants(audio, permissionHeld = { it != Manifest.permission.RECORD_AUDIO }),
        )
    }

    @Test
    fun audioCaptureIsGrantedOnlyWithOptInPermissionAndAnIdleMicrophone() {
        assertArrayEquals(audio, grants(audio))
    }

    // ---- the camera is refused for its own reason ------------------------------------------------

    @Test
    fun videoCaptureIsRefusedOutrightHoweverPermittedThePageIs() {
        assertArrayEquals(
            "one owner holds the camera and the panel HALs cannot share it with a second",
            emptyArray<String>(),
            grants(video),
        )
    }

    @Test
    fun aMixedRequestKeepsOnlyTheAdmittedMicrophone() {
        assertArrayEquals(
            "the camera is dropped for its own reason, the microphone kept on its own terms",
            audio,
            grants(video + audio),
        )
    }

    @Test
    fun everythingIsRefusedWithoutPermissions() {
        assertArrayEquals(emptyArray<String>(), grants(video + audio, permissionHeld = noPermissionsHeld))
    }

    @Test
    fun anUnknownResourceIsNeverGranted() {
        assertArrayEquals(
            emptyArray<String>(),
            grants(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)),
        )
    }

    // ---- there is exactly one place a capture grant can be made -----------------------------------

    private val dashboard = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt"),
    ).first { it.isFile }.readText()

    /**
     * The admission above is worth only as much as the number of places that can bypass it. This is
     * a source-structure property — the handler needs a live WebView and cannot be exercised in a
     * JVM test — pinned the same way as `PanelHaSignInReachabilityTest`.
     */
    @Test
    fun everyCaptureGrantGoesThroughTheAdmittedSet() {
        val grantCalls = Regex("""\brequest\.grant\(""").findAll(dashboard).count()
        assertEquals("a second grant site would not be covered by any test here", 1, grantCalls)

        val declarations = Regex("""internal fun webViewCaptureGrants\(""").findAll(dashboard).count()
        val mentions = Regex("""\bwebViewCaptureGrants\(""").findAll(dashboard).count()
        assertEquals("one decision function", 1, declarations)
        assertEquals("declared once and called once: the grant site is the only caller", 2, mentions)

        val handler = dashboard.substringAfter("override fun onPermissionRequest(").substringBefore("\n        }")
        assertTrue(
            "the handler must decide through webViewCaptureGrants, not inline: $handler",
            handler.contains("webViewCaptureGrants("),
        )
        assertTrue(
            "the handler must consult the WebView opt-in admission",
            handler.contains("MicrophoneAdmission.webViewCaptureAllowed"),
        )
        assertTrue(
            "the handler must consult the shared-microphone idleness admission",
            handler.contains("MicrophoneAdmission.isIdle"),
        )
        assertFalse(
            "the handler must not grant anything it did not put through the decision",
            handler.contains("request.grant(arrayOf") || handler.contains("request.grant(request.resources"),
        )
    }
}
