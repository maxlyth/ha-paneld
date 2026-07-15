package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.platform.AccessibilityActions
import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.DaemonLongResult
import io.github.maxlyth.hapaneld.platform.RootShell
import io.github.maxlyth.hapaneld.platform.ShellPrivilege
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InteractiveControllerTest {
    private class Shell(
        private val calls: MutableList<String>,
        private val screenshotBytes: ByteArray? = null,
        private val inputResult: Boolean = false,
    ) : ShellPrivilege {
        override fun available() = true
        override fun uid() = 2000
        override fun screenshot(): ByteArray? { calls += "shizuku:screenshot"; return screenshotBytes }
        override fun inputKey(keyCode: Int): Boolean { calls += "shizuku:key:$keyCode"; return inputResult }
        override fun tap(x: Int, y: Int): Boolean { calls += "shizuku:tap:$x:$y"; return inputResult }
        override fun density(): String? = null
        override fun setDensity(dpi: Int) = false
        override fun resetDensity() = false
        override fun fontScale(): String? = null
        override fun setFontScale(scale: Float) = false
        override fun resetFontScale() = false
        override fun installApk(apk: File, allowDowngrade: Boolean, timeoutMs: Long): String? = null
    }
    private class Root(
        private val calls: MutableList<String>,
        runResults: List<Boolean> = emptyList(),
        byteResults: List<ByteArray?> = emptyList(),
    ) : RootShell {
        private val runs = runResults.toMutableList()
        private val bytes = byteResults.toMutableList()

        override fun available() = false
        override fun run(cmd: String): Boolean {
            calls += "su:$cmd"
            return if (runs.isEmpty()) false else runs.removeAt(0)
        }
        override fun runOutput(cmd: String): String? = null
        override fun runBytes(cmd: String): ByteArray? {
            calls += "su-bytes:$cmd"
            return if (bytes.isEmpty()) null else bytes.removeAt(0)
        }
        override fun fireAndForget(cmd: String) = false
    }

    private class Helper(
        private val calls: MutableList<String>,
        byteResults: List<ByteArray?> = emptyList(),
    ) : Daemon {
        private val bytes = byteResults.toMutableList()

        override fun available() = false
        override fun send(cmd: String): String? = null
        override fun sendLong(cmd: String, timeoutMs: Long) = DaemonLongResult.NotSubmitted
        override fun sendBytes(cmd: String): ByteArray? {
            calls += "helper-bytes:$cmd"
            return if (bytes.isEmpty()) null else bytes.removeAt(0)
        }
    }

    private class Accessibility(
        private val calls: MutableList<String>,
        results: List<Boolean> = emptyList(),
    ) : AccessibilityActions {
        private val remaining = results.toMutableList()

        override fun back() = result("a11y:back")
        override fun recents() = result("a11y:recents")
        override fun tap(x: Int, y: Int) = result("a11y:tap:$x:$y")

        private fun result(call: String): Boolean {
            calls += call
            return if (remaining.isEmpty()) false else remaining.removeAt(0)
        }
    }

    private fun controller(
        canSu: Boolean,
        calls: MutableList<String>,
        rootRuns: List<Boolean> = emptyList(),
        rootBytes: List<ByteArray?> = emptyList(),
        helperBytes: List<ByteArray?> = emptyList(),
        accessibilityResults: List<Boolean> = emptyList(),
    ) = InteractiveController(
        canSu = canSu,
        root = Root(calls, rootRuns, rootBytes),
        daemon = Helper(calls, helperBytes),
        accessibility = Accessibility(calls, accessibilityResults),
    )

    @Test fun screenshotPrefersSuAndStopsOnNonemptyBytes() {
        val calls = mutableListOf<String>()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val result = controller(
            canSu = true,
            calls = calls,
            rootBytes = listOf(png),
            helperBytes = listOf(byteArrayOf(1)),
        ).screenshot()

        assertArrayEquals(png, result)
        assertEquals(listOf("su-bytes:screencap -p"), calls)
    }

    @Test fun screenshotFallsFromEmptySuBytesToHelper() {
        val calls = mutableListOf<String>()
        val png = byteArrayOf(1, 2, 3)
        val result = controller(
            canSu = true,
            calls = calls,
            rootBytes = listOf(byteArrayOf()),
            helperBytes = listOf(png),
        ).screenshot()

        assertArrayEquals(png, result)
        assertEquals(listOf("su-bytes:screencap -p", "helper-bytes:SCREENCAP"), calls)
    }

    @Test fun screenshotFallsFromRawHelperEofToSu() {
        val calls = mutableListOf<String>()
        val png = byteArrayOf(4, 5, 6)
        val result = controller(
            canSu = false,
            calls = calls,
            rootBytes = listOf(png),
            helperBytes = listOf(byteArrayOf()),
        ).screenshot()

        assertArrayEquals(png, result)
        assertEquals(listOf("helper-bytes:SCREENCAP", "su-bytes:screencap -p"), calls)
    }

    @Test fun screenshotReturnsNullAfterBothByteRoutesFail() {
        val calls = mutableListOf<String>()
        assertNull(controller(canSu = false, calls = calls).screenshot())
        assertEquals(listOf("helper-bytes:SCREENCAP", "su-bytes:screencap -p"), calls)
    }

    @Test fun backFallsFromPreferredSuToAccessibility() {
        val calls = mutableListOf<String>()
        assertTrue(controller(
            canSu = true,
            calls = calls,
            rootRuns = listOf(false),
            accessibilityResults = listOf(true),
        ).back())
        assertEquals(listOf("su:input keyevent 4", "a11y:back"), calls)
    }

    @Test fun recentsFallsFromPreferredAccessibilityToSu() {
        val calls = mutableListOf<String>()
        assertTrue(controller(
            canSu = false,
            calls = calls,
            rootRuns = listOf(true),
            accessibilityResults = listOf(false),
        ).recents())
        assertEquals(listOf("a11y:recents", "su:input keyevent 187"), calls)
    }

    @Test fun navigationStopsAfterPreferredRouteSucceeds() {
        val calls = mutableListOf<String>()
        assertTrue(controller(
            canSu = false,
            calls = calls,
            rootRuns = listOf(true),
            accessibilityResults = listOf(true),
        ).back())
        assertEquals(listOf("a11y:back"), calls)
    }

    @Test fun navigationReportsAllRoutesFailed() {
        val calls = mutableListOf<String>()
        assertFalse(controller(canSu = true, calls = calls).back())
        assertEquals(listOf("su:input keyevent 4", "a11y:back"), calls)
    }

    @Test fun tapFallsAcrossRoutesAndUsesIntegralCoordinates() {
        val calls = mutableListOf<String>()
        assertTrue(controller(
            canSu = false,
            calls = calls,
            rootRuns = listOf(true),
            accessibilityResults = listOf(false),
        ).tap(12.9f, 34.1f))
        assertEquals(listOf("a11y:tap:12:34", "su:input tap 12 34"), calls)
    }

    @Test fun tapRejectsInvalidCoordinatesBeforeEitherBoundary() {
        listOf(
            Float.NaN to 1f,
            Float.POSITIVE_INFINITY to 1f,
            -1f to 1f,
            1f to -1f,
        ).forEach { (x, y) ->
            val calls = mutableListOf<String>()
            assertFalse(controller(canSu = true, calls = calls).tap(x, y))
            assertTrue(calls.isEmpty())
        }
    }

    @Test fun screenshotFallsThroughExistingRoutesToShizukuLast() {
        val calls = mutableListOf<String>()
        val png = byteArrayOf(7, 8, 9)
        val result = InteractiveController(
            canSu = true,
            root = Root(calls),
            daemon = Helper(calls),
            accessibility = Accessibility(calls),
            shell = Shell(calls, screenshotBytes = png),
        ).screenshot()
        assertArrayEquals(png, result)
        assertEquals(listOf("su-bytes:screencap -p", "helper-bytes:SCREENCAP", "shizuku:screenshot"), calls)
    }

    @Test fun readyShizukuPrecedesSpeculativeSuOnSandboxedProfile() {
        val calls = mutableListOf<String>()
        val controller = InteractiveController(
            canSu = false,
            root = Root(calls),
            daemon = Helper(calls),
            accessibility = Accessibility(calls),
            shell = Shell(calls, inputResult = true),
        )
        assertTrue(controller.tap(12f, 34f))
        assertEquals(listOf("a11y:tap:12:34", "shizuku:tap:12:34"), calls)
    }

    @Test fun failedReadyShizukuStillFallsThroughToSpeculativeSu() {
        val calls = mutableListOf<String>()
        val controller = InteractiveController(
            canSu = false,
            root = Root(calls, runResults = listOf(true)),
            daemon = Helper(calls),
            accessibility = Accessibility(calls),
            shell = Shell(calls, inputResult = false),
        )
        assertTrue(controller.back())
        assertEquals(listOf("a11y:back", "shizuku:key:4", "su:input keyevent 4"), calls)
    }
}
