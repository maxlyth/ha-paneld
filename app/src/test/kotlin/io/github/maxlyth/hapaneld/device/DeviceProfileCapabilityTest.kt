package io.github.maxlyth.hapaneld.device

import io.github.maxlyth.hapaneld.device.profile.BundledProfileFixtures
import io.github.maxlyth.hapaneld.hardware.LedFactory
import io.github.maxlyth.hapaneld.hardware.NoOpLedController
import io.github.maxlyth.hapaneld.hardware.SocketLedController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cross-profile capability invariants, evaluated from packaged YAML rather than compiled objects. */
class DeviceProfileCapabilityTest {
    private val loaded get() = BundledProfileFixtures.bundled
    private val all get() = loaded.map { it.profile() }

    @Test fun recommendedVendorPackagesRemainExplicitYamlPolicy() {
        val ledDemo = all.flatMap { profile -> profile.tameVendorCandidates.map { profile.id to it } }
            .single { (_, candidate) -> candidate.pkg == "com.gulukai.pwmlightdemo" }
        assertTrue(ledDemo.second.defaultTame)
        assertTrue(ledDemo.second.note.contains("continuously cycles colours"))
    }

    @Test fun idsArePresentAndUnique() {
        all.forEach { assertTrue("blank id on ${it.displayName}", it.id.isNotBlank()) }
        val ids = all.map { it.id }
        assertEquals("duplicate profile ids", ids.size, ids.toSet().size)
    }

    @Test fun sandboxedAndDaemonBackedProfilesDeclareTheirDaemonRequirement() {
        all.forEach {
            if (!it.appCanSu) assertTrue("sandboxed profile must require daemon on ${it.id}", it.usesDaemon)
            val daemonHardware = it.ledMechanism in setOf(
                LedMechanism.SYSFS_DAEMON,
                LedMechanism.RK3576_IOCTL_DAEMON,
            ) || it.screenOff == ScreenOff.DAEMON_BLPOWER || it.hasButtonBacklight ||
                it.proximityGpio != null || it.evdevButtons.isNotEmpty()
            if (daemonHardware) assertTrue("daemon-backed feature omitted from usesDaemon on ${it.id}", it.usesDaemon)
        }
        all.filter { it.evdevButtons.isNotEmpty() }.forEach {
            assertTrue("evdev buttons require helper on ${it.id}", it.usesDaemon)
        }
        assertEquals(
            "helper-backed evdev hardware changed",
            setOf("tpa10", "wf1589t"),
            all.filter { it.evdevButtons.isNotEmpty() }.mapTo(mutableSetOf()) { it.id },
        )
    }

    @Test fun noSuFormImpliesAppCannotSu() {
        all.forEach {
            if (it.suForm == SuForm.NONE) assertFalse("suForm NONE but appCanSu on ${it.id}", it.appCanSu)
            if (it.appCanSu) assertTrue("appCanSu but suForm NONE on ${it.id}", it.suForm != SuForm.NONE)
        }
    }

    @Test fun screenOffPathAgreesWithSuCapability() {
        all.forEach {
            when (it.screenOff) {
                ScreenOff.SU_BLPOWER -> assertTrue("SU_BLPOWER but !appCanSu on ${it.id}", it.appCanSu)
                ScreenOff.DAEMON_BLPOWER -> assertFalse("DAEMON_BLPOWER but appCanSu on ${it.id}", it.appCanSu)
                ScreenOff.BRIGHTNESS_ZERO -> Unit
            }
        }
    }

    @Test fun daemonRoutedLedImpliesSandboxWalled() {
        all.forEach {
            if (it.ledMechanism in setOf(LedMechanism.SYSFS_DAEMON, LedMechanism.RK3576_IOCTL_DAEMON)) {
                assertFalse("daemon LED but appCanSu on ${it.id}", it.appCanSu)
            }
        }
        assertEquals(
            "daemon LED routing changed",
            mapOf(
                LedMechanism.SYSFS_DAEMON to setOf("tpa10"),
                LedMechanism.RK3576_IOCTL_DAEMON to setOf("smt1019"),
            ),
            all.filter {
                it.ledMechanism in setOf(LedMechanism.SYSFS_DAEMON, LedMechanism.RK3576_IOCTL_DAEMON)
            }.groupBy { it.ledMechanism }.mapValues { (_, profiles) -> profiles.mapTo(mutableSetOf()) { it.id } },
        )
    }

    @Test fun buttonBacklightIsAnExplicitHardwareFactNotAnRgbBackendInference() {
        val buttonProfiles = all.filter { it.hasButtonBacklight }
        assertEquals("button-backlight ownership changed", setOf("tpa10"), buttonProfiles.mapTo(mutableSetOf()) { it.id })
        buttonProfiles.forEach { profile ->
            assertTrue("button-backlight omitted from daemon requirement on ${profile.id}", profile.usesDaemon)
        }
        all.filter { it.ledMechanism == LedMechanism.RK3576_IOCTL_DAEMON }.forEach { profile ->
            assertTrue(LedFactory.detect(profile) is SocketLedController)
            assertFalse("daemon RGB must not imply a button-backlight on ${profile.id}", profile.hasButtonBacklight)
        }
    }

    @Test fun roomClimateCapabilityAndDriverStayInLockstep() {
        loaded.forEach { source ->
            assertEquals(
                "sensor.cht8305-daemon driver mismatch on ${source.document.id}",
                "sensor.cht8305-daemon" in source.document.requires.drivers,
                source.profile().hasCht8305,
            )
        }
        assertEquals(
            "room-climate capability ownership changed",
            setOf("tpa10", "zx-smt156"),
            all.filter { it.hasCht8305 }.mapTo(mutableSetOf()) { it.id },
        )
    }

    @Test fun profileMetadataIsStructurallyValid() {
        val packageName = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
        val eventType = Regex("^KEYCODE_[A-Z0-9_]+$")
        val cert = Regex("^[0-9a-f]{64}$")
        all.forEach { profile ->
            assertTrue("blank display name on ${profile.id}", profile.displayName.isNotBlank())
            assertTrue("blank soc class on ${profile.id}", profile.socClass.isNotBlank())
            profile.recommendedDensity?.let { assertTrue("invalid density on ${profile.id}", it in 72..640) }
            profile.recommendedFontScale?.let { assertTrue("invalid font scale on ${profile.id}", it in 0.5f..2f) }
            profile.physicalPpi?.let { assertTrue("invalid physical ppi on ${profile.id}", it in 50..1000) }
            assertTrue("non-finite room offset on ${profile.id}", profile.roomTempOffsetC.isFinite())
            profile.recommendedWebView?.let { webView ->
                assertTrue("non-HTTPS WebView pin on ${profile.id}", webView.url.startsWith("https://"))
                assertTrue("unparseable WebView version on ${profile.id}", webView.major > 0)
                assertTrue("invalid WebView cert on ${profile.id}", cert.matches(webView.certSha256))
                assertTrue("invalid WebView artifact hash on ${profile.id}", cert.matches(webView.apkSha256))
            }
            val tamePackages = profile.tameVendorCandidates.map { it.pkg }
            assertEquals("duplicate tame package on ${profile.id}", tamePackages.size, tamePackages.toSet().size)
            profile.tameVendorCandidates.forEach {
                assertTrue("invalid tame package ${it.pkg} on ${profile.id}", packageName.matches(it.pkg))
                assertTrue("blank tame rationale for ${it.pkg} on ${profile.id}", it.note.isNotBlank())
            }
            profile.evdevButtons.forEach {
                assertTrue("invalid evdev node ${it.node} on ${profile.id}", it.node.matches(Regex("^/dev/input/event\\d+$")))
                assertTrue("invalid evdev code on ${profile.id}", it.code > 0)
                assertTrue("invalid event type ${it.eventType} on ${profile.id}", eventType.matches(it.eventType))
            }
        }
    }

    @Test fun ledFactoryRoutesEveryDeterministicYamlMechanism() {
        all.forEach { profile ->
            when (profile.ledMechanism) {
                LedMechanism.NONE -> assertTrue("NONE should be no-op on ${profile.id}", LedFactory.detect(profile) is NoOpLedController)
                LedMechanism.SYSFS_DAEMON,
                LedMechanism.RK3576_IOCTL_DAEMON,
                -> assertTrue("daemon LED should use socket on ${profile.id}", LedFactory.detect(profile) is SocketLedController)
                LedMechanism.RK3576_IOCTL,
                LedMechanism.AUTODETECT,
                -> Unit // Native/probing routes are intentionally not exercised by a hermetic JVM test.
            }
        }
    }
}
