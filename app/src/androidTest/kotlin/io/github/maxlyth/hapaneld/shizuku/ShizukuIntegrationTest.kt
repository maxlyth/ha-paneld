package io.github.maxlyth.hapaneld.shizuku

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/** Real Binder/UID-2000 smoke. Runs only in the dedicated emulator workflow, never on fleet panels. */
@RunWith(AndroidJUnit4::class)
class ShizukuIntegrationTest {
    @Test fun typedShellOperationsAndManagerReinstall() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)

        ShizukuConsent.enable(context, managed = false)
        ShizukuBridge.refresh(requestPermission = true)
        val allow = device.wait(Until.findObject(By.text(Pattern.compile("(?i).*allow.*"))), 15_000)
        allow?.click()
        waitUntil(20_000) { ShizukuBridge.available() }

        assertTrue("Shizuku UserService did not become ready", ShizukuBridge.available())
        assertEquals(ShizukuPolicy.SHELL_UID, ShizukuBridge.uid())
        val png = ShizukuBridge.screenshot()
        assertNotNull(png)
        assertTrue("screenshot should have a PNG signature", png!!.size > 8 && png[1] == 'P'.code.toByte())
        assertNotNull(ShizukuBridge.density())
        assertNotNull(ShizukuBridge.fontScale())
        assertTrue(ShizukuBridge.tap(1, 1))

        // The workflow copies the exact pinned manager APK into target app-private storage. Reinstalling
        // the same package exercises the APK stream and the dependency self-update edge without changing
        // the emulator's version or introducing an untrusted fixture.
        val apkPath = InstrumentationRegistry.getArguments().getString("managerApk")
        assertNotNull("managerApk instrumentation argument missing", apkPath)
        val apk = File(apkPath!!)
        assertTrue("manager APK fixture missing", apk.isFile)
        val install = ShizukuBridge.installApk(apk, allowDowngrade = false, timeoutMs = 180_000)
        assertTrue("manager reinstall failed: $install", install?.contains("Success", ignoreCase = true) == true)
        assertEquals(ShizukuManagerIdentity.Status.TRUSTED, ShizukuManagerIdentity.status(context))
    }

    private fun waitUntil(timeoutMs: Long, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!ready() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200)
    }
}
