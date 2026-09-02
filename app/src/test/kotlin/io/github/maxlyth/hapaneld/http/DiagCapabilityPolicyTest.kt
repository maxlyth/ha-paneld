package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.fakeProfile
import io.github.maxlyth.hapaneld.control.TameController
import io.github.maxlyth.hapaneld.device.EvdevButton
import io.github.maxlyth.hapaneld.device.ScreenOff
import io.github.maxlyth.hapaneld.device.profile.BundledProfileFixtures
import io.github.maxlyth.hapaneld.shizuku.ShizukuBridge
import io.github.maxlyth.hapaneld.shizuku.ShizukuManagerIdentity
import io.github.maxlyth.hapaneld.shizuku.ShizukuState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagCapabilityPolicyTest {
    private val fallback = BundledProfileFixtures.fallback()
    private val nspanel = BundledProfileFixtures.profile("nspanel-pro")

    @Test fun userFacingDataSizesUseFamiliarUnitLabels() {
        val info = java.io.File("src/main/assets/info.js").readText()
        val entities = java.io.File("src/main/assets/entities.js").readText()
        val panelInfo = java.io.File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PanelInfo.kt").readText()

        listOf(info, entities, panelInfo).forEach { source ->
            assertFalse(source.contains("KiB"))
            assertFalse(source.contains("MiB"))
            assertFalse(source.contains("GiB"))
        }
        assertTrue(entities.contains("function displayBytes(n)"))
        assertTrue(entities.contains("+' GB'"))
        assertTrue(entities.contains("+' MB'"))
        assertTrue(entities.contains("+' KB'"))
    }

    @Test fun performanceUiNamesTheDevtoolsRelayAsRemoteDebugging() {
        val source = java.io.File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        assertTrue(source.contains("<h2>Remote WebView debugging "))
        assertFalse(source.contains("<h2>WebView debugging "))
    }

    @Test fun topProcessesCardHasAnAccessibleCpuRamSelector() {
        val server = java.io.File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        assertTrue(server.contains("""role="group" aria-label="Rank processes by"""))
        assertTrue(server.contains("""data-mode="cpu" aria-pressed="true"""))
        assertTrue(server.contains("""data-mode="ram" aria-pressed="false"""))
    }

    @Test fun configurableValuePencilsStayAttachedToTheirValue() {
        val server = java.io.File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        val css = java.io.File("src/main/assets/info.css").readText()
        assertTrue(server.contains("""&nbsp;<a class="cfglink"""))
        assertTrue(css.contains(".cfglink{display:inline;margin-left:0"))
        assertTrue(css.contains("white-space:nowrap"))
    }

    @Test fun behaviourCardOwnsConfiguredRuntimeBehaviourWithoutPanelInfoDuplicates() {
        val source = java.io.File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        assertTrue(source.contains(""""Keep panel responsive", "Prevent idle dim", "Android dashboard lock", "Navbar","""))
        assertTrue(source.contains(""""silence_boot_chime", "keep_awake", "navbar_mode", "log_ship_enabled""""))
        assertTrue(source.contains(""""watchdog_enabled", "kiosk_lock", "touch_sound""""))
        assertTrue(source.contains("""tcard("behavtbl", "Behaviour", s?.let { behaviourRowsHtml(it) })"""))
    }

    /** Runtime diagnostics owns live transport state; Behaviour reports the configured on/off setting. */
    @Test fun liveLogShippingStatusIsARuntimeDiagnosticsFactNotABehaviourSetting() {
        val source = java.io.File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        val contextKeys = Regex("""private val CONTEXT_KEYS = listOf\(([^)]*)\)""")
            .find(source)?.groupValues?.get(1).orEmpty()
        val behaviourFactKeys = Regex("""private val BEHAVIOUR_FACT_KEYS = setOf\(([^)]*)\)""")
            .find(source)?.groupValues?.get(1).orEmpty()

        assertTrue("Log shipping must be a Runtime diagnostics row", contextKeys.contains(""""Log shipping""""))
        assertFalse("Log shipping must not be claimed by the Behaviour card", behaviourFactKeys.contains(""""Log shipping""""))

        // No formatter may be attached to a BOOL setting — settingRowHtml would silently ignore it.
        assertFalse(source.contains("dashboardLogShipValue"))
        assertTrue(source.contains("settingRowHtml(key, s.live, caps, hints, areaFormatter)"))

        // The off state is suppressed rather than shown twice; Behaviour's "Ship logs" already says it.
        assertTrue(source.contains("""key == "Log shipping" && it == LOG_SHIP_STATUS_OFF"""))
    }

    @Test fun noisyStateEntitiesUseAColumnarTableInsteadOfRepeatedLabels() {
        val script = java.io.File("src/main/assets/info.js").readText()
        val configure = java.io.File("src/main/assets/configure.js").readText()
        val server = java.io.File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        val configReadRoutes = java.io.File("src/main/kotlin/io/github/maxlyth/hapaneld/http/ConfigReadRoutes.kt").readText()

        assertTrue(script.contains("function paintNoisy(entities,identified)"))
        assertTrue(script.contains("['Top entities','Rate','Payload']"))
        assertTrue(script.contains("String(e.updates1h)+'/hr'"))
        assertTrue(script.contains("fmtByteTotal(e.payloadBytes1h||0)+'/hr'"))
        assertTrue(script.contains("function fmtByteTotal(n)"))
        assertTrue(script.contains("toFixed(1)+' KB'"))
        assertTrue(script.contains("toFixed(1)+' MB'"))
        assertFalse(script.contains("toFixed(1)+' KiB'"))
        assertFalse(script.contains("toFixed(1)+' MiB'"))
        assertTrue(script.contains("toFixed(1)+' KB/s'"))
        assertTrue(script.contains("toFixed(1)+' MB/s'"))
        assertFalse(script.contains("KiB/s"))
        assertFalse(script.contains("MiB/s"))
        assertFalse(script.contains("label:'Noisy entity'"))
        assertTrue(server.contains("""<table class="dt" id="noisyentities">"""))
        assertTrue(server.contains("""<div id="cfg-all-cards">"""))
        assertTrue(server.contains("""<div id="cfg-groups" class="cards" data-card-size-page="configure"""))
        assertFalse(server.contains("Save changes does not control them"))
        assertTrue(server.contains("""id="savebar" class="savebar" role="region" aria-label="Unsaved settings" hidden"""))
        assertTrue(configure.contains("""bar.hidden = !dirty && !saving"""))
        assertTrue(configure.contains("""document.body.classList.toggle("cfg-dirty", dirty || saving)"""))
        assertTrue(configure.contains("""if (!dirty || saving) return"""))
        assertTrue(configure.contains("""editGeneration !== submittedGeneration"""))
        assertTrue(configure.contains("""function recomputeDirty()"""))
        assertTrue(configure.contains("""values[f.key] !== savedValues[f.key]"""))
        assertTrue(configure.contains("""savedValues = Object.assign({}, values)"""))
        assertTrue(configure.contains("""return "Built-in renderer"""))
        assertTrue(configure.contains("""return "Home Assistant connection"""))
        assertTrue(configure.contains("""if (g === "Built-in renderer")"""))
        assertTrue(configure.contains("""dashboard_entity_learning: true, dashboard_fullscreen: true, dashboard_native_kiosk: true,"""))
        assertTrue(configure.contains("""dashboard_idle_return_min: true, dashboard_zoom: true"""))
        assertTrue(configure.contains("""var HA_CONNECTION_KEYS = { ha_url: true, ha_token: true }"""))
        assertTrue(configure.contains("""moveGroupTo("Home Assistant connection", 2)"""))
        assertTrue(configure.contains("""moveGroupTo("Dashboard", 3)"""))
        assertTrue(configure.contains("""moveGroupTo("Built-in renderer", 4)"""))
        assertTrue(configure.contains("""groups.push("Logging")"""))
        assertFalse(configure.contains(""""Behaviour": "Android/app behaviour""""))
        assertFalse(configure.contains("dashboard appearance; does not lock Android"))
        assertTrue(configure.contains(""""Sensors": "Home Assistant reporting""""))
        assertTrue(configure.contains(""""Diagnostics": "Home Assistant reporting""""))
        assertTrue(configure.contains("""el("small", { text: " · " + CARD_NOTES[g] })"""))
        val css = java.io.File("src/main/assets/info.css").readText()
        assertTrue(css.contains("#noisyentities th:first-child,#noisyentities td:first-child{width:auto}"))
        assertTrue(css.contains("#noisyentities .num{white-space:nowrap}"))
        assertTrue(css.contains("#noisyentities .rate{width:68px}"))
        assertTrue(css.contains("#noisyentities .payload{width:78px}"))
        assertTrue(css.contains(".savebar{position:fixed"))
        assertTrue(css.contains(".savebar[hidden]{display:none}"))
        assertTrue(css.contains("--config-divider:#3a3a3a"))
        assertTrue(css.contains("--card:#181818;--card-head:#222"))
        assertTrue(css.contains("border-top:1px solid var(--config-divider)"))
        assertFalse(css.contains(".cfg-group-contents{display:contents}"))
        assertTrue(configure.contains("""card.setAttribute("data-config-group", g)"""))
        assertTrue(configure.contains("desiredCards.splice(loggingCardIndex < 0 ? desiredCards.length : loggingCardIndex, 0, proximityCard)"))
        assertTrue(configure.contains("""var helpKids = [el("span", { lang: f.helpLanguage, text: f.help })]"""))
        assertTrue(configure.contains("""} else if (f.key === "auto_sleep") {
      help = el("small", { lang: f.helpLanguage, text: f.help });"""))
        assertTrue(configure.contains("""var labelText = el("span", { lang: f.labelLanguage })"""))
        assertFalse(configure.contains("document.documentElement.lang"))
        assertTrue(server.contains("""get("/configure") {
                    call.response.headers.append(HttpHeaders.ContentLanguage, AppLocale.ENGLISH)"""))
        assertTrue(server.contains("""page("configure", "Configure", configureBody(), languageTag = AppLocale.ENGLISH)"""))
        assertTrue(server.contains("configReadRoutes("))
        assertTrue(server.contains("render = ::configSchemaJson"))
        assertTrue(configReadRoutes.contains("LocalizedConfigSchema(render(strings), strings.languages)"))
        assertTrue(configReadRoutes.contains("HttpHeaders.ContentLanguage"))
        assertTrue(server.contains("\\\"labelLanguage\\\":${'$'}{s(label.language)}"))
        assertTrue(server.contains("\\\"helpLanguage\\\":${'$'}helpLanguageJson"))
        assertTrue(configure.contains("f.displaySizingAvailable === true"))
        assertTrue(configure.contains("href: \"/install#cfg-display\", text: \"Display Sizing\""))
        assertTrue(server.contains("val displaySizingAvailable = caps.canSetDisplay"))
        assertTrue(server.contains("spec.key == \"dashboard_zoom\" && displaySizingAvailable"))
        assertTrue(server.contains("""<div class="cards" id="install-cards" data-card-size-page="install"""))
        assertTrue(server.contains("""<div class="cards" id="dashboard-cards" data-card-size-page="dashboard"""))
        assertFalse(server.contains("For panels with no physical nav bar"))
        assertTrue(server.indexOf("\${tcard(\"infotbl\", \"Panel information\"") < server.indexOf("\$shotCard"))
        assertTrue(server.contains("""class="gh gh-inline cnotes"""))
        assertTrue(server.contains("class=\"gh gh-inline\" href=\"\$RELEASES_URL\""))
        assertTrue(css.contains(".gh-inline svg{width:16px;height:16px"))
        assertTrue(server.contains("""aria-label="Release notes on GitHub"""))
        assertFalse(server.contains("""class="cfglink cnotes"""))
        assertTrue(server.contains("""UpdateChecker.compareVersions(candidate, it)"""))
        assertTrue(server.contains("""comparison > 0 -> "Upgrade"""))
        assertTrue(server.contains("""else -> "Downgrade"""))
        assertTrue(server.contains("""wv.playManaged -> """))
        assertTrue(server.contains("Managed by Google Play — updates via the Play Store"))
        val install = java.io.File("src/main/assets/install.js").readText()
        assertTrue(install.contains("o.setAttribute('data-action', v.action || 'Install')"))
        assertTrue(install.contains("btn.textContent = o ? (o.getAttribute('data-action') || 'Install') : 'Install'"))
        val proximity = java.io.File("src/main/assets/proximity-learning.js").readText()
        assertTrue(proximity.contains("""var cardRoot = document.getElementById("cfg-groups")"""))
        assertTrue(proximity.contains("""cardRoot.insertBefore(card, cardRoot.querySelector('[data-config-group="Logging"]'))"""))
    }

    @Test fun diagnosticButtonRequestUsesTheInjectedProfile() {
        val profile = fakeProfile(
            evdevButtons = listOf(
                EvdevButton("/dev/input/event7", 116, grab = true, eventType = "power"),
                EvdevButton("/dev/input/event3", 14, grab = false, eventType = "mute", sw = true),
            ),
        )
        assertEquals(
            "/dev/input/event7:KEY/116:grab,/dev/input/event3:SW/14:watch",
            DiagReader.evdevRequestDescription(profile),
        )
    }

    /**
     * The capability row must describe the route the panel will actually take, and on
     * [ScreenOff.BRIGHTNESS_ZERO] that route never reaches a privileged actuator at all:
     * `ScreenController.sleepInternal` hard-codes `ScreenOff.BRIGHTNESS_ZERO -> null` for its
     * powered-off route. Reporting a helper- or su-backed backlight-off there tells the owner of a
     * rooted panel that the screen goes properly dark when it only dims.
     */
    @Test fun brightnessZeroRouteReportsDimOnlyEvenWhenPrivilegeIsAvailable() {
        listOf(
            Triple(true, true, "helper and su"),
            Triple(false, true, "helper only"),
            Triple(true, false, "su only"),
            Triple(false, false, "neither"),
        ).forEach { (su, daemon, label) ->
            val cap = DiagReader.screenOnOffCapability(ScreenOff.BRIGHTNESS_ZERO, su = su, daemon = daemon)

            assertEquals("brightness-zero must never claim ok with $label", "degraded", cap.status)
            assertFalse(
                "brightness-zero must not claim a backlight-off it never attempts with $label: ${cap.note}",
                cap.note.contains("backlight-off"),
            )
            assertEquals(
                "brightness-zero must say it only dims, and why, with $label",
                "DIM ONLY — this panel's profile selects the brightness-zero route, which never powers the backlight down",
                cap.note,
            )
        }
    }

    /** A bl_power route names the transport it will actually try first, not whichever exists. */
    @Test fun blPowerRoutesNameTheirOwnFirstAttempt() {
        val su = DiagReader.screenOnOffCapability(ScreenOff.SU_BLPOWER, su = true, daemon = true)
        assertEquals("ok", su.status)
        assertTrue("su-blpower tries su first: ${su.note}", su.note.contains("su bl_power"))

        val daemon = DiagReader.screenOnOffCapability(ScreenOff.DAEMON_BLPOWER, su = true, daemon = true)
        assertEquals("ok", daemon.status)
        assertTrue("daemon-blpower tries the daemon first: ${daemon.note}", daemon.note.contains("helper daemon"))
    }

    /** A bl_power route with no privileged transport at all cannot power the backlight down. */
    @Test fun blPowerRoutesWithoutPrivilegeReportDimOnly() {
        listOf(ScreenOff.SU_BLPOWER, ScreenOff.DAEMON_BLPOWER).forEach { route ->
            val cap = DiagReader.screenOnOffCapability(route, su = false, daemon = false)
            assertEquals("$route without privilege must be degraded", "degraded", cap.status)
            assertEquals(
                "$route must say it only dims, and why",
                "DIM ONLY — the backlight stays powered; needs su or the helper daemon for a real off",
                cap.note,
            )
        }
    }

    @Test fun profileWithoutEvdevButtonsHasNoRequestedStream() {
        assertNull(DiagReader.evdevRequestDescription(fakeProfile()))
    }

    @Test fun exactProfileDeclarationsHideAbsentLedAndHardwareButtons() {
        assertFalse(DiagReader.showRgbLedCapability(nspanel))
        assertFalse(DiagReader.showHardwareButtonsCapability(nspanel))
    }

    @Test fun genericProfileRetainsRuntimeLedAndButtonDiscoveryRows() {
        assertTrue(DiagReader.showRgbLedCapability(fallback))
        assertTrue(DiagReader.showHardwareButtonsCapability(fallback))
    }

    @Test fun profiledEvdevButtonsKeepTheHardwareButtonRow() {
        val profile = fakeProfile(
            evdevButtons = listOf(EvdevButton("/dev/input/event7", 116, grab = true, eventType = "power")),
        )

        assertTrue(DiagReader.showHardwareButtonsCapability(profile))
    }

    @Test fun exactProfileCardOmitsExplicitlyAbsentHardware() {
        val keys = PaneldServer.profileFactKeys(
            nspanel,
            mapOf(
                "Platform" to "Sonoff NSPanel Pro",
                "SoC" to "Rockchip RK3326 · 4× Arm Cortex-A35 · introduced 2018",
                "LED" to "none",
                "Light sensor" to "yes · Ambient light",
                "Proximity" to "yes · Infrared",
                "Zigbee" to "sonoff · running",
                "Relays" to "none",
                "CPU profile" to "Auto",
            ),
        )

        assertFalse("LED" in keys)
        assertFalse("Relays" in keys)
        assertTrue("SoC" in keys)
        assertTrue("Light sensor" in keys)
        assertTrue("Proximity" in keys)
        assertTrue("Zigbee" in keys)
    }

    @Test fun genericProfileCardKeepsCapabilityDiscoveryButOmitsUnknownSoc() {
        val keys = PaneldServer.profileFactKeys(
            fallback,
            mapOf("LED" to "none", "Relays" to "none", "Zigbee" to "none"),
        )

        assertTrue("LED" in keys)
        assertTrue("Relays" in keys)
        assertTrue("Zigbee" in keys)
        assertFalse("SoC" in keys)
    }

    @Test fun unexpectedObservedHardwareRemainsVisibleForProfileCorrection() {
        val keys = PaneldServer.profileFactKeys(
            nspanel,
            mapOf("LED" to "RGB", "Relays" to "2"),
        )

        assertTrue("LED" in keys)
        assertTrue("Relays" in keys)
    }

    @Test fun installOwnsImmediatePanelToolsAndConfigureOnlyOwnsSaveTogetherSettings() {
        val source = java.io.File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        val configure = source.substring(source.indexOf("private fun configureBody"), source.indexOf("private fun profilesBody"))
        val install = source.substring(source.indexOf("private fun installBody"), source.indexOf("private fun installWarning"))

        listOf("displayCardHtml(management.privilege.typedShellControlReady, displaySizing)", "tameCardHtml(root)").forEach {
            assertFalse(it in configure)
            assertTrue(it in install)
        }
        assertTrue("/assets/proximity-learning.js" in configure)
        assertFalse("/assets/prox.js" in source)
        assertFalse("proximityCardHtml()" in source)
        assertTrue("configImport(this)" in source.substring(source.indexOf("private fun backupCardHtml"), source.indexOf("private fun apkCardHtml")))
        assertTrue("""installIcon("cfg-display")""" in source)
        assertTrue("""cfgIcon("cfg-wake_on_wave")""" in source)
        assertTrue("/install#cfg-tame" in source)
    }

    @Test fun proximityHasNoProfileSpecificTuningSurface() {
        val source = java.io.File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        assertFalse("showProximityTuning" in source)
        assertFalse("Threshold fine-tune" in source)
        assertFalse("proxCap(" in source)
    }

    @Test fun missingAppSuDoesNotClaimHelperBackedActionsAreUnavailable() {
        val cap = DiagReader.rootSuCapability(su = false, daemon = true)

        assertEquals("Root (su)", cap.name)
        assertEquals("degraded", cap.status)
        assertTrue(cap.note.contains("routed through the helper daemon"))
        assertFalse(cap.note.contains("unavailable"))
        assertFalse(cap.note.contains("no su on this firmware"))
    }

    @Test fun missingBothPrivilegeRoutesDefersToSpecificCapabilityRows() {
        val cap = DiagReader.rootSuCapability(su = false, daemon = false)

        assertEquals("none", cap.status)
        assertTrue(cap.note.contains("individual capability rows"))
        assertFalse(cap.note.contains("reboot/reload"))
    }

    @Test fun appVisibleSuIsReportedPrecisely() {
        val cap = DiagReader.rootSuCapability(su = true, daemon = false)

        assertEquals("ok", cap.status)
        assertEquals("available directly to ha-paneld", cap.note)
    }

    @Test fun blPowerRoutesReportABacklightOff() {
        val daemonRoute = DiagReader.screenOnOffCapability(ScreenOff.DAEMON_BLPOWER, su = false, daemon = true)
        assertEquals("Screen on/off", daemonRoute.name)
        assertEquals("ok", daemonRoute.status)
        assertEquals("true backlight-off via the helper daemon", daemonRoute.note)

        val suRoute = DiagReader.screenOnOffCapability(ScreenOff.SU_BLPOWER, su = true, daemon = false)
        assertEquals("true backlight-off via su bl_power", suRoute.note)

        val none = DiagReader.screenOnOffCapability(ScreenOff.BRIGHTNESS_ZERO, su = false, daemon = false)
        assertEquals("degraded", none.status)
        assertEquals(
            "DIM ONLY — this panel's profile selects the brightness-zero route, which never powers the backlight down",
            none.note,
        )
    }

    /** A panel whose screen-off is Android's own sleep must not be told it has a backlight off, and it
     *  must be told plainly that a local touch is not guaranteed to wake it. */
    @Test fun keyeventRouteDescribesAndroidSleepRatherThanABacklightOff() {
        listOf(
            DiagReader.screenOnOffCapability(ScreenOff.KEYEVENT, su = true, daemon = false),
            DiagReader.screenOnOffCapability(ScreenOff.KEYEVENT, su = false, daemon = true),
        ).forEach { cap ->
            assertEquals("ok", cap.status)
            assertTrue(cap.note.contains("KEYCODE_SLEEP"))
            assertTrue(cap.note.contains("Home Assistant always wakes it"))
            assertTrue(cap.note.contains("platform wake source"))
            assertFalse("the keyevent route blanks no backlight", cap.note.contains("backlight-off"))
        }

        val unprivileged = DiagReader.screenOnOffCapability(ScreenOff.KEYEVENT, su = false, daemon = false)
        assertEquals("with no privileged injector there is no real off at all", "degraded", unprivileged.status)
        assertEquals(
            "DIM ONLY — needs su or the helper daemon to inject KEYCODE_SLEEP",
            unprivileged.note,
        )
    }

    @Test fun screenBrightnessCallsOutReducedHardwareOnlyControl() {
        val direct = DiagReader.screenBrightnessCapability(canWrite = true, su = false, daemon = false, pkg = "test.pkg")
        assertEquals("Screen brightness", direct.name)
        assertEquals("ok", direct.status)
        assertEquals("WRITE_SETTINGS granted", direct.note)

        val helper = DiagReader.screenBrightnessCapability(canWrite = false, su = false, daemon = true, pkg = "test.pkg")
        assertEquals("degraded", helper.status)
        assertTrue(helper.note.contains("helper daemon"))
        assertFalse(helper.note.contains("adb shell"))

        val root = DiagReader.screenBrightnessCapability(canWrite = false, su = true, daemon = false, pkg = "test.pkg")
        assertEquals("degraded", root.status)
        assertTrue(root.note.contains("via su"))

        val unavailable = DiagReader.screenBrightnessCapability(canWrite = false, su = false, daemon = false, pkg = "test.pkg")
        assertEquals("none", unavailable.status)
        assertTrue(unavailable.note.contains("adb shell appops set test.pkg WRITE_SETTINGS allow"))
    }

    @Test fun rootedOrHelperBackedPanelsExplainConfiguredShizukuIsRedundant() {
        for (manager in ShizukuManagerIdentity.Status.entries) {
            assertTrue(DiagReader.showShizukuCapability(consentEnabled = true, manager))
        }
        assertFalse(
            DiagReader.showShizukuCapability(
                consentEnabled = false,
                ShizukuManagerIdentity.Status.MISSING,
            ),
        )
        assertTrue(
            DiagReader.shizukuCapabilityNote(
                ShizukuState.READY,
                ShizukuManagerIdentity.Status.TRUSTED,
                preferredPrivilegeReady = true,
            ).contains("adds no capability while root or the helper daemon provides the preferred route"),
        )
        val unhealthy = DiagReader.shizukuCapabilityNote(
            ShizukuState.READY,
            ShizukuManagerIdentity.Status.UNTRUSTED,
            preferredPrivilegeReady = true,
        )
        assertTrue(unhealthy.contains("adds no capability"))
        assertTrue(unhealthy.contains("signer is not trusted"))
        assertFalse(unhealthy.contains("ready as shell UID"))
    }

    @Test fun shizukuCapabilityStatusRequiresAReadyBridgeAndTrustedManager() {
        val ready = ShizukuBridge.Snapshot(ShizukuState.READY, ready = true)
        val trusted = DiagReader.shizukuCapability(
            ready,
            ShizukuManagerIdentity.Status.TRUSTED,
            preferredPrivilegeReady = true,
        )
        val untrusted = DiagReader.shizukuCapability(
            ready,
            ShizukuManagerIdentity.Status.UNTRUSTED,
            preferredPrivilegeReady = true,
        )
        val stopped = DiagReader.shizukuCapability(
            ShizukuBridge.Snapshot(ShizukuState.STOPPED, ready = false),
            ShizukuManagerIdentity.Status.TRUSTED,
        )

        assertEquals("ok", trusted.status)
        assertTrue(trusted.note.contains("adds no capability"))
        assertEquals("none", untrusted.status)
        assertTrue(untrusted.note.contains("signer is not trusted"))
        assertEquals("none", stopped.status)
        assertTrue(stopped.note.contains("service is stopped"))
    }

    @Test fun genuinelyUnrootedPanelsShowShizukuOnlyWhenConfiguredOrInstalled() {
        assertFalse(
            DiagReader.showShizukuCapability(
                consentEnabled = false,
                ShizukuManagerIdentity.Status.MISSING,
            ),
        )
        assertTrue(
            DiagReader.showShizukuCapability(
                consentEnabled = true,
                ShizukuManagerIdentity.Status.MISSING,
            ),
        )
        assertTrue(
            DiagReader.showShizukuCapability(
                consentEnabled = false,
                ShizukuManagerIdentity.Status.TRUSTED,
            ),
        )
    }

    @Test fun enhancedAccessDiagnosticsGiveDifferentDisabledAndStoppedRecoveryPaths() {
        val disabled = DiagReader.shizukuCapabilityNote(
            ShizukuState.DISABLED,
            ShizukuManagerIdentity.Status.TRUSTED,
        )
        val stopped = DiagReader.shizukuCapabilityNote(
            ShizukuState.STOPPED,
            ShizukuManagerIdentity.Status.TRUSTED,
        )

        assertTrue(disabled.contains("Configure → toolbar overflow → Enhanced access → Enable"))
        assertFalse(disabled.contains("service is stopped"))
        assertTrue(stopped.contains("service is stopped"))
        assertTrue(stopped.contains("open Shizuku"))
        assertFalse(stopped.contains("→ Enable"))
    }

    @Test fun bootSecurityDiagnosticsNormalizeOnlyAllowlistedCategoricalFacts() {
        val properties = mapOf(
            "ro.boot.verifiedbootstate" to "GREEN",
            "ro.boot.flash.locked" to "1",
            "ro.boot.vbmeta.device_state" to "locked",
            "ro.debuggable" to "0",
        )

        assertEquals(
            "[boot-security] verified=green flash=locked vbmeta=locked build=user debuggable=no",
            DiagReader.bootSecurityLine({ properties[it].orEmpty() }, "user"),
        )
    }

    @Test fun bootSecurityDiagnosticsDoNotEchoUnknownRawPropertyValues() {
        val identifyingRawValue = "device-specific-value-12345"
        val line = DiagReader.bootSecurityLine({ identifyingRawValue }, identifyingRawValue)

        assertEquals(
            "[boot-security] verified=unknown flash=unknown vbmeta=unknown build=unknown debuggable=unknown",
            line,
        )
        assertFalse(line.contains(identifyingRawValue))
    }

    @Test fun publicPanelFactsExcludeProfileAndDeploymentAuthoredText() {
        val privateValue = "private-room-or-network.example"
        val facts = linkedMapOf(
            "ha-paneld" to "0.9.5-rc1 (build 294)",
            "MQTT state" to "connected · ack 2s ago · ipv4",
            "Security mode" to "Hardened · high-impact remote actions need physical on-panel approval",
            "Prevent idle dim" to "on · timeout 60s (not applied)",
            "Platform" to privateValue,
            "Model" to privateValue,
            "Light sensor" to privateValue,
            "Proximity" to privateValue,
            "CPU profile" to privateValue,
            "Log shipping" to "tcp://$privateValue:9000",
            "Friendly name" to privateValue,
            "MQTT" to "$privateValue · connected",
        )

        val public = DiagReader.publicPanelFacts(facts)

        assertEquals(listOf("ha-paneld", "MQTT state", "Security mode", "Prevent idle dim"), public.keys.toList())
        assertFalse(public.values.joinToString().contains(privateValue))
    }

    /**
     * The Wi-Fi line is the one allowlisted fact that is also conditional. The panel's own card shows
     * every episode; this report is terse by design and is read by somebody triaging a bug, so the
     * line enters it only once the instability is chronic.
     */
    @Test fun theWifiStabilityLineEntersThePastedReportOnlyWhenTheInstabilityIsChronic() {
        val facts = linkedMapOf(
            "ha-paneld" to "0.9.7-rc1 (build 563)",
            "Wi-Fi stability" to "2 outages in the last 24 h",
        )

        assertEquals(
            listOf("ha-paneld"),
            DiagReader.publicPanelFacts(facts, wifiStabilityChronic = false).keys.toList(),
        )
        assertEquals(
            listOf("ha-paneld", "Wi-Fi stability"),
            DiagReader.publicPanelFacts(facts, wifiStabilityChronic = true).keys.toList(),
        )
        // Text that leaves the panel fails closed: a caller that says nothing gets no line.
        assertFalse(DiagReader.publicPanelFacts(facts).containsKey("Wi-Fi stability"))
    }

    @Test fun vendorTameDiagnosticsExposeCountsWithoutProfilePackageIdentifiers() {
        val packageName = "private.example.vendor.panel"
        val line = DiagReader.vendorTameSummary(
            listOf(
                TameController.Candidate(packageName, "one", installed = true, disabled = false, blocked = false),
                TameController.Candidate("private.example.disabled", "two", installed = true, disabled = true, blocked = false),
                TameController.Candidate("private.example.absent", "three", installed = false, disabled = false, blocked = false),
            ),
        )

        assertEquals("[vendor-tame] known=3 installed=2 active=1 disabled=1", line)
        assertFalse(line.contains(packageName))
        assertFalse(line.contains("private.example"))
    }
}
