package io.github.maxlyth.hapaneld

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardScreenPolicyTest {
    @Test fun preventIdleDimOwnsTheBuiltInRendererWindowFlag() {
        assertTrue(shouldKeepBuiltInRendererScreenOn(preventIdleDim = true))
        assertFalse(shouldKeepBuiltInRendererScreenOn(preventIdleDim = false))
    }

    @Test fun activityAppliesAndClearsTheFlagAcrossLiveAndResumePaths() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt").readText()
        val policy = source.substring(
            source.indexOf("private fun applyRendererScreenPolicy()"),
            source.indexOf("private fun applyOverscroll()"),
        )

        assertTrue(policy.contains("window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"))
        assertTrue(policy.contains("window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"))
        assertTrue(source.contains("activityConfig.registerChangeListener(rendererPowerListener)"))
        assertTrue(source.contains("activityConfig.unregisterChangeListener(rendererPowerListener)"))
        assertTrue(source.substring(source.indexOf("override fun onResume()"), source.indexOf("private fun applyOverscroll()"))
            .contains("applyRendererScreenPolicy()"))
    }

    @Test fun dashboardEntryBootstrapsTheServiceBeforeConfigGates() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt").readText()
        val onCreate = source.substring(
            source.indexOf("override fun onCreate(savedInstanceState: Bundle?)"),
            source.indexOf("override fun dispatchTouchEvent"),
        )

        assertTrue(onCreate.contains("PaneldService.start(this)"))
        assertTrue(onCreate.indexOf("PaneldService.start(this)") < onCreate.indexOf("val config = Config(this)"))
        assertTrue(onCreate.contains("if (!config.builtInRendererReady())"))
    }

    @Test fun unauthenticatedBuiltinCompatibilityFailureUsesSignInRepairCopy() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt").readText()
        val block = source.substring(
            source.indexOf("DashboardV2ProbeResult.AuthenticationFailed"),
            source.indexOf("is DashboardV2ProbeResult.Unavailable"),
        )

        assertTrue(block.contains("config.haToken.isBlank() && config.haRefreshToken.isBlank()"))
        assertTrue(block.contains("\"Home Assistant sign-in needed\""))
        assertTrue(block.contains("\"Home Assistant version check rejected\""))
    }

    // --- admission auto-retry contracts (an admission screen must never be terminal) ---

    private fun dashboardSource() = File("src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt").readText()

    /** A window from each blocked-screen call large enough to include its outcome. */
    private fun callWindows(source: String, title: String): List<String> =
        Regex("showBlockedAdmissionScreen\\(\\s*" + Regex.escape("\"$title\"")).findAll(source).map {
            source.substring(it.range.first, minOf(source.length, it.range.first + 700))
        }.toList()

    @Test fun everyBlockedAdmissionScreenNamesWhatThePanelLearned() {
        val source = dashboardSource()

        // Each blocked screen names its evidence; admissionRetryClass (not the call site) decides the
        // policy, so a screen cannot quietly pick its own recovery behavior.
        fun outcomeOf(title: String) = callWindows(source, title).map { w ->
            Regex("""AdmissionOutcome\.([A-Z_]+)""").find(w)?.groupValues?.get(1)
        }
        assertEquals(listOf("TRANSPORT_FAILED"), outcomeOf("Home Assistant version unavailable"))
        assertEquals(listOf("DASHBOARD_LIST_UNREADABLE"), outcomeOf("Home Assistant dashboard list unavailable"))
        assertEquals(listOf("SIGN_IN_PAGE_UNREACHABLE"), outcomeOf("Home Assistant sign-in unavailable"))
        assertEquals(listOf("BRIDGE_HANDSHAKE_MISSED"), outcomeOf("Secure external bridge not detected"))
        assertEquals(listOf("VERSION_UNVERIFIABLE"), outcomeOf("Home Assistant version unverifiable"))
        assertEquals(listOf("UNSUPPORTED_HA"), outcomeOf("Home Assistant upgrade required"))
        assertEquals(
            listOf("NO_LEGAL_DASHBOARD", "DASHBOARD_LIST_UNREADABLE", "NO_LEGAL_DASHBOARD"),
            outcomeOf("No Home Assistant dashboards available"),
        )
        // One permanent-incapability screen; the three attachment failures are separate, retryable evidence.
        assertEquals(listOf("BRIDGE_UNAVAILABLE"), outcomeOf("Secure dashboard bridge unavailable"))
        assertEquals(List(3) { "BRIDGE_ATTACH_FAILED" }, outcomeOf("Secure dashboard bridge interrupted"))
        // The credential branch chooses its title inside one call, so read the branch itself.
        val authBranch = source.substring(
            source.indexOf("DashboardV2ProbeResult.AuthenticationFailed ->"),
            source.indexOf("is DashboardV2ProbeResult.Unavailable ->"),
        )
        assertTrue(authBranch.contains("AdmissionOutcome.CREDENTIAL_REFUSED"))
    }

    @Test fun onlyProgressScreensMayOmitAnOutcome() {
        val source = dashboardSource()
        // The raw painter is private and reached through exactly two helpers: blocked screens must
        // pass an outcome (no default), progress screens use the helper that never arms.
        assertEquals(2, Regex(Regex.escape("showV2CompatibilityScreen(title, detail,")).findAll(source).count())
        assertFalse("an outcome must never be defaulted", source.contains("outcome: AdmissionOutcome ="))
        assertFalse("call sites must not choose the retry class themselves", source.contains("autoRetry = AdmissionRetryClass."))
        assertEquals(2, Regex(Regex.escape("showAdmissionProgressScreen(")).findAll(source).count() - 1)
        assertTrue(source.contains("showV2CompatibilityScreen(title, detail, \"Retry\", admissionRetryClass(outcome))"))
    }

    @Test fun theActivityDelegatesCountdownVisibilityToTheTestedOwner() {
        // Behavior lives in AdmissionCountdownOwner and is proven there by executable lifecycle tests;
        // these assertions only pin that the activity delegates rather than re-deciding.
        val source = dashboardSource()
        assertTrue(source.contains("private val admissionCountdown = AdmissionCountdownOwner { SystemClock.uptimeMillis() }"))

        // Top-visibility, not resumed-ness, owns the repaint: Android 10+ can drop top-resumed status
        // without ever calling onPause.
        val topResumed = source.substring(
            source.indexOf("override fun onTopResumedActivityChanged"),
            source.indexOf("override fun onTopResumedActivityChanged") + 400,
        )
        assertTrue(topResumed.contains("onAdmissionVisibilityChanged(isTopResumedActivity)"))
        // Exactly one tier owns visibility: resume below API 29, top-resumed from 29 up. The rule itself
        // is proven executably by resumeOwnsAdmissionVisibility's tests.
        assertTrue(topResumed.contains("if (!resumeOwnsAdmissionVisibility("))
        val resume = source.substring(
            source.indexOf("override fun onResume()"),
            source.indexOf("override fun onPause()"),
        )
        assertTrue(resume.contains("if (resumeOwnsAdmissionVisibility(android.os.Build.VERSION.SDK_INT)) onAdmissionVisibilityChanged(true)"))
        val pause = source.substring(
            source.indexOf("override fun onPause()"),
            source.indexOf("override fun onTopResumedActivityChanged"),
        )
        assertTrue(pause.contains("onAdmissionVisibilityChanged(false)"))
        assertFalse("pausing must not disarm the retry", pause.contains("main.removeCallbacks(admissionRetry)"))
        assertFalse("pausing must not disarm the countdown owner", pause.contains("admissionCountdown.disarm()"))

        // A detached view counts as not visible, so a replaced screen cannot keep a stale row alive.
        val apply = source.substring(
            source.indexOf("private fun applyAdmissionPaint"),
            source.indexOf("private fun onAdmissionCountdownTick"),
        )
        assertTrue(apply.contains("it.isAttachedToWindow"))
        assertTrue(apply.contains("main.removeCallbacks(admissionCountdownTick)"))
    }

    @Test fun everyContentReplacingFunctionDisarmsTheAdmissionAutoRetry() {
        val source = dashboardSource()
        val bodies = source.split(Regex("\\n    (?:private |internal |public )?(?:override )?fun "))
        val painting = bodies.filter { it.contains("setContentView(") }
        assertEquals("a new screen must decide its disarm behavior", 6, painting.size)
        painting.forEach { body ->
            assertTrue(
                "a content-replacing function must disarm the admission auto-retry: ${body.lineSequence().first()}",
                body.contains("cancelAdmissionAutoRetry()"),
            )
        }
    }

    @Test fun automaticAndManualAdmissionRetryRunTheSameSequence() {
        val source = dashboardSource()
        val runnable = source.substring(
            source.indexOf("private val admissionRetry = Runnable"),
            source.indexOf("private val admissionCountdownTick"),
        )
        assertTrue(runnable.contains("destroyed || !BuiltinDashboard.ownsActivity(activityOwner) || authLatched"))
        assertTrue("the timer must not reset the back-off it is pacing", runnable.contains("retryAdmission(resetBackoff = false)"))
        assertTrue(
            "a present human does not wait out a timer",
            source.contains("setOnClickListener { retryAdmission(resetBackoff = true) }"),
        )
        val sequence = source.substring(
            source.indexOf("private fun retryAdmission(resetBackoff: Boolean)"),
            source.indexOf("private fun cancelAdmissionAutoRetry()"),
        )
        assertTrue(sequence.contains("compatibilityAttempts.invalidate()"))
        assertTrue(sequence.contains("v2Handshake.reset()"))
        assertTrue(sequence.contains("buildAndLoad(Config(this))"))
    }

    @Test fun admissionSuccessResetsTheBackoffAndDisarmsTheTimer() {
        // Without the reset, a recovered panel carries an inflated ladder into the NEXT incident, so
        // its first retry waits up to the ceiling instead of the base. Success means actually building
        // the renderer, which is exactly the buildCompatibleAndLoad prologue.
        val source = dashboardSource()
        val success = source.substring(
            source.indexOf("private fun buildCompatibleAndLoad"),
            source.indexOf("WebView.setWebContentsDebuggingEnabled"),
        )
        assertTrue(success.contains("cancelAdmissionAutoRetry()"))
        assertTrue(
            "recovery must not leave the back-off inflated for the next incident",
            success.contains("admissionRetryPolicy.reset()"),
        )
    }

    @Test fun theJitteredDelayIsComputedOncePerPaintAndProgressScreensNeverArm() {
        val source = dashboardSource()
        val painter = source.substring(
            source.indexOf("private fun showV2CompatibilityScreen("),
            source.indexOf("private fun retryAdmission(resetBackoff: Boolean)"),
        )
        assertEquals(1, Regex(Regex.escape("admissionRetryPolicy.nextDelayMs(")).findAll(painter).count())
        assertTrue(painter.contains("if (retryLabel == null) null else admissionRetryPolicy.nextDelayMs(autoRetry)"))

        // A provisional dashboard remains on screen, so this recovery route deliberately does not
        // repaint. It still owns one independently computed delay and arms the shared retry owner.
        val resolver = source.substring(
            source.indexOf("private fun resolveHomeDashboardAndLoad"),
            source.indexOf("private fun navigateAfterHomeDashboardCorrection"),
        )
        assertTrue(resolver.contains("admissionRetryPolicy.nextDelayMs("))
        assertTrue(resolver.contains("armAdmissionAutoRetry(it, \"Home Assistant dashboard list unavailable\")"))
    }

    @Test fun theCountdownAndTheRetryCallbackShareTheHandlerClock() {
        // Handler.postDelayed schedules on uptimeMillis, so the countdown owner is given that same
        // clock; a sleep-advancing clock would let the text reach 0s while the callback is pending.
        // The counting behavior itself is proven executably in AdmissionCountdownOwner's tests.
        val source = dashboardSource()
        assertTrue(source.contains("AdmissionCountdownOwner { SystemClock.uptimeMillis() }"))
        assertFalse(
            "the admission countdown must not read the sleep-advancing clock",
            source.substring(
                source.indexOf("private fun retryAdmission(resetBackoff: Boolean)"),
                source.indexOf("private fun resolveHomeDashboardAndLoad"),
            ).contains("elapsedRealtime"),
        )
        val arm = source.substring(
            source.indexOf("private fun armAdmissionAutoRetry"),
            source.indexOf("private fun applyAdmissionPaint"),
        )
        assertTrue("the armed delay and the painted delay are one value", arm.contains("main.postDelayed(admissionRetry, delayMs)"))
        assertTrue(arm.contains("admissionCountdown.arm(delayMs)"))
    }

    @Test fun theEntityFilterHoldNamesTheReasonTheAddressAndAWayOut() {
        // The hold protects the first impression; it must never become a trap. Someone standing at the panel
        // with no browser to hand needs both the address of the page and a way to proceed without it, and the
        // skip has to say what it costs — it is the unfiltered load this screen exists to avoid.
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt").readText()
        val screen = source.substring(
            source.indexOf("private fun showWaitingForEntityFilterAnswer()"),
            source.indexOf("private fun showWaitingForEntityBootstrap()"),
        )
        assertTrue(screen.contains("to optimise the "))
        assertTrue("the page's own address must be shown", screen.contains("\"/setup\""))
        assertTrue(screen.contains("\"Skip and load the dashboard now\""))
        assertTrue("skipping must state its cost", screen.contains("which is slower on this panel"))
        assertTrue(screen.contains("skipEntityFilterQuestion()"))

        // Skipping records the SAME answer the browser's decline records, so the two surfaces cannot disagree
        // about whether the question was asked — and it must not quietly enable what the user passed over.
        val skip = source.substring(
            source.indexOf("private fun skipEntityFilterQuestion()"),
            source.indexOf("private fun showWaitingForEntityFilterAnswer()"),
        )
        assertTrue(skip.contains("config.setupEntityFilterAnswered = true"))
        assertFalse(skip.contains("dashboardEntityLearning"))
        assertTrue(skip.contains("buildAndLoad(config)"))
    }
}
