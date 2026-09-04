package io.github.maxlyth.hapaneld.camera

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the camera capability is wired, pinned by source text because none of it can run on the JVM: the
 * probe calls into Android's `CameraManager`, and the sites that consume it are a Service and a bridge.
 * The decision itself is decided in `CameraCapabilityPolicyTest`; this proves the decision is the one
 * every camera surface actually asks, that the probe agrees with the code that opens the camera, and
 * that asking never enumerates on the main thread or under the owner's lock.
 */
class CameraCapabilitySourceContractTest {

    private val service by lazy { TestSources.kotlin("PaneldService.kt").readText() }
    private val owner by lazy { TestSources.kotlin("camera/CameraSessionOwner.kt").readText() }
    private val bridge by lazy { TestSources.kotlin("MqttBridge.kt").readText() }

    private fun body(source: String, name: String): String {
        val start = source.indexOf(name)
        assertTrue("$name is present", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced $name")
    }

    /**
     * The probe must accept exactly what the open path accepts. `chooseCamera` takes any enumerated
     * camera — it prefers a front-facing lens but explicitly never filters on it — so presence is a
     * non-empty list and nothing narrower. A stricter probe would hide a camera that opens fine; a
     * looser one would offer a camera the session then refuses as `no_camera_id`.
     */
    @Test fun theProbeAcceptsExactlyWhatTheOpenPathAccepts() {
        val choose = body(owner, "private fun chooseCamera")
        assertTrue("the open path accepts any enumerated camera", "cameraIdList.takeIf { it.isNotEmpty() } ?: return null" in choose)
        assertTrue("facing is a preference, never a filter", "?: described.first()" in choose)
        assertTrue(
            "so the probe asks the same question of the same list",
            "getSystemService(CameraManager::class.java)?.cameraIdList?.isNotEmpty()" in service,
        )
    }

    /**
     * Camera hardware does not appear or vanish while the panel runs, so an empty list is an answer and
     * only a throw is worth retrying. A probe that re-enumerated on every negative would poll Android's
     * camera service forever on the many panels that have no camera at all.
     */
    @Test fun presenceIsProbedOnceAndRemembered() {
        val probe = service.indexOf("private val cameraPresence = SuccessStickyProbe")
        assertTrue("the probe is sticky in both directions", probe >= 0)
        assertFalse(
            "a negative must stick rather than being filtered back into a retry",
            "cameraIdList?.isNotEmpty()?.takeIf" in service,
        )
    }

    /** Every camera surface asks one question, and the profile is read only inside that one answer. */
    @Test fun oneRuleAnswersForEveryCameraSurface() {
        assertTrue(
            "the rule combines the declaration with the observation",
            "cameraCapabilityPresent(profile.cameraDeclared, cameraPresence.get())" in service,
        )
        assertEquals(
            "the profile's declaration is read in exactly one place",
            1,
            Regex("profile\\.cameraDeclared").findAll(service).count(),
        )
        assertEquals(
            "the session owner, the bridge and the settings snapshot all ask that one rule",
            3,
            Regex("cameraPresent\\(\\)").findAll(service).count() - 1,
        )
    }

    /**
     * The bridge holds the capability as a supplier, never a value read once while the service was
     * starting. A boolean captured from a probe that threw at construction would announce the camera
     * entity from the later snapshot and then refuse the enable command that arrives for it.
     */
    @Test fun theBridgeAsksRatherThanRemembering() {
        assertTrue("private val hasCamera: () -> Boolean = { false }," in bridge)
        assertTrue("requireCameraEnableAdmission(on, hasCamera()) {" in bridge)
    }

    /**
     * Asking may enumerate Android's cameras, so no call site may ask while holding the owner's lock or
     * while the service is still constructing on the main thread. Both lock-holding readers hoist the
     * answer out, and every reader that has a switch to consult reads that cheap switch first.
     */
    @Test fun askingNeverEnumeratesUnderTheLockOrDuringConstruction() {
        assertTrue("the capability is a supplier", "private val hasCamera: () -> Boolean," in owner)

        val init = body(owner, "    init {")
        assertTrue("the switch is read before the capability at startup", "transport.setListening(enabled() && hasCamera())" in init)

        val prompt = body(owner, "private fun publishPermissionPrompt")
        assertTrue("and again wherever the prompt is republished", "wantsPermission = enabled() && hasCamera() && !permissionGranted()" in prompt)

        val presentation = body(owner, "override fun presentation()")
        val presentationRead = presentation.indexOf("val present = hasCamera()")
        assertTrue("the presentation reads it before taking the lock", presentationRead in 0 until presentation.indexOf("synchronized(lock)"))
        assertTrue("!present -> CameraPresentation.absent()" in presentation)

        val acquire = body(owner, "private fun acquireLease")
        val acquireRead = acquire.indexOf("val present = hasCamera()")
        assertTrue("so does the lease gate", acquireRead in 0 until acquire.indexOf("synchronized(lock)"))
        assertTrue("!present -> CameraRefusal.ABSENT" in acquire)

        assertFalse("nothing enumerates while the lock is held", "!hasCamera() ->" in owner)
    }
}
