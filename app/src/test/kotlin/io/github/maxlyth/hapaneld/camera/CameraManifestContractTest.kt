package io.github.maxlyth.hapaneld.camera

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest half of the camera trial's privacy contract. The permission set is what a user reads in
 * the store listing, so it must describe the feature that exists and nothing more: a video-only trial
 * declares no microphone access, and the camera-typed foreground service is a dedicated one so the
 * boot-started agent never inherits a type Android would refuse it.
 */
class CameraManifestContractTest {

    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private fun declares(permission: String): Boolean =
        Regex("""<uses-permission\s+android:name="android\.permission\.$permission"""").containsMatchIn(manifest)

    @Test fun theCameraPermissionsAreDeclaredAndTheMicrophoneOnesAreNot() {
        assertTrue(declares("CAMERA"))
        assertTrue(declares("FOREGROUND_SERVICE_CAMERA"))
        // Match the declaration, not the word: the manifest comment explains why these are absent.
        assertFalse("a video-only trial must not advertise microphone access", declares("RECORD_AUDIO"))
        assertFalse(declares("FOREGROUND_SERVICE_MICROPHONE"))
    }

    @Test fun everyFeatureTheCameraPermissionImpliesIsDeclaredOptional() {
        // The CAMERA permission implies BOTH android.hardware.camera and android.hardware.camera.autofocus
        // as required hardware unless each is declared optional; either alone would make Android refuse
        // the install on every panel without one, which is most of them.
        listOf("android.hardware.camera", "android.hardware.camera.any", "android.hardware.camera.autofocus").forEach { feature ->
            assertTrue(
                "$feature must be declared optional",
                Regex("""<uses-feature\s+android:name="${Regex.escape(feature)}"\s+android:required="false"""")
                    .containsMatchIn(manifest),
            )
        }
    }

    @Test fun theCameraTypeLivesOnADedicatedServiceNotTheBootStartedAgent() {
        val paneld = Regex("""<service\s+android:name="\.PaneldService"[^>]*foregroundServiceType="([^"]+)"""")
            .find(manifest)
        assertEquals("specialUse", requireNotNull(paneld).groupValues[1])
        val cameraService = Regex("""<service\s+android:name="\.camera\.CameraForegroundService"[^>]*foregroundServiceType="([^"]+)"""")
            .find(manifest)
        assertEquals("camera", requireNotNull(cameraService).groupValues[1])
        assertTrue(manifest.contains("""android:name=".camera.CameraForegroundService"
            android:exported="false""""))
    }

    @Test fun theServiceNeverAsksAndroidToResurrectASession() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/camera/CameraForegroundService.kt").readText()
        assertTrue(source.contains("return START_NOT_STICKY"))
        assertFalse(source.contains("START_STICKY\n"))
    }
}
