package io.github.maxlyth.hapaneld.camera

import io.github.maxlyth.hapaneld.testsupport.TestSources
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stream's standing commitments, read off the sources so they cannot be undone quietly: the trial
 * is video only and the transport stack adds no dependency; the encoder is fed from the paced frame
 * path, never a second capture surface, so the frame-rate ceiling stays a property of the code; the
 * transport is drained before the camera owner; and the service binds the
 * stream port through the owner's switch, never unconditionally.
 */
class CameraStreamContractTest {
    private val cameraDir = TestSources.appDir("src/main/kotlin/io/github/maxlyth/hapaneld/camera")
    private val service by lazy { TestSources.kotlin("PaneldService.kt").readText() }
    private val owner by lazy { TestSources.kotlin("camera/CameraSessionOwner.kt").readText() }
    private val encoder by lazy { TestSources.kotlin("camera/H264Encoder.kt").readText() }

    private fun cameraSources(): List<File> = cameraDir.listFiles { f -> f.name.endsWith(".kt") }.orEmpty().sortedBy { it.name }

    @Test fun theCameraPackageNeverTouchesTheMicrophoneOrAnAudioTrack() {
        assertTrue(cameraSources().isNotEmpty())
        cameraSources().forEach { file ->
            val text = file.readText()
            listOf("AudioRecord", "MediaRecorder", "MicrophoneSource", "RECORD_AUDIO", "MIMETYPE_AUDIO", "m=audio").forEach { forbidden ->
                assertFalse("${file.name} mentions $forbidden", text.contains(forbidden))
            }
        }
    }

    @Test fun theTransportIsHandWrittenOnTheAlreadyPinnedStackNotAStreamingLibrary() {
        val build = TestSources.appFile("build.gradle.kts").readText()
        val catalog = TestSources.repoFile("gradle/libs.versions.toml").readText()
        val settings = TestSources.repoFile("settings.gradle.kts").readText()
        listOf(build, catalog).forEach { text ->
            assertFalse(text.contains("pedroSG94"))
            assertFalse(text.contains("RootEncoder", ignoreCase = true))
            assertFalse(text.contains("rtsp", ignoreCase = true))
        }
        assertFalse("no new repository was admitted for the transport", settings.contains("jitpack", ignoreCase = true))
        cameraSources().forEach { file ->
            assertFalse("${file.name} imports a third-party streaming package", Regex("^import com\\.pedro", RegexOption.MULTILINE).containsMatchIn(file.readText()))
        }
    }

    @Test fun theEncoderIsFedFromThePacedFramePathNotASecondCaptureSurface() {
        assertTrue(owner.contains("camera.createCaptureSession(listOf(r.surface)"))
        assertFalse("no encoder input surface joins the capture session", owner.contains("createInputSurface"))
        assertFalse(encoder.contains("createInputSurface()"))
        assertTrue("frames enter the encoder through the owner's pacer", owner.contains("encoder?.takeIf { encoderPacer?.admit(now) == true }"))
        assertTrue(encoder.contains("COLOR_FormatYUV420Flexible"))
    }

    @Test fun theServiceDrainsTheTransportBeforeTheCameraAndTheOwnerAloneTurnsListeningOn() {
        val transportStop = service.indexOf("closeOwner(\"camera stream\") { cameraStream.stop() }")
        val cameraStop = service.indexOf("closeOwnerResult(\"camera\")")
        assertTrue("both drains exist", transportStop > 0 && cameraStop > 0)
        assertTrue("the transport is drained before the camera owner", transportStop < cameraStop)
        assertFalse("listening is the owner's decision, made from the switch", service.contains("cameraStream.setListening"))
        assertTrue(owner.contains("transport.setListening(hasCamera && enabled())"))
        assertEquals("the transport only ever learns the camera through the field", 1, Regex("source = \\{ camera \\}").findAll(service).count())
    }

    @Test fun theStreamUrlNeverEntersTheDiagnosticDumpButTheStatusSummaryMayCarryIt() {
        val presentation = TestSources.kotlin("camera/CameraPresentation.kt").readText()
        val diag = presentation.substringAfter("fun diagnosticLine()").substringBefore("companion object")
        assertFalse(diag.contains("rtsp://"))
        assertFalse(diag.contains("summary"))
        assertTrue(owner.contains("\"stream at rtsp://\$address:\${facts.port}\$STREAM_PATH (not for this panel's own dashboard)\""))
        val api = TestSources.repoFile("docs/api.md").readText()
        assertTrue("the self-render warning sits beside the URL where a person copies it", api.contains("Do not put this panel's own camera card on this panel's dashboard"))
    }
}
