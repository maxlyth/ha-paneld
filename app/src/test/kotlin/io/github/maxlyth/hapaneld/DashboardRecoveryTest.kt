package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DashboardRecoveryTest {
    @Test fun `registration callback is ignored when activity started online`() {
        val gate = NetworkRecoveryGate(initiallyAvailable = true)
        assertFalse(gate.onAvailable())
    }

    @Test fun `first available reloads when activity started offline`() {
        val gate = NetworkRecoveryGate(initiallyAvailable = false)
        assertTrue(gate.onAvailable())
        assertFalse(gate.onAvailable())
    }

    @Test fun `real network loss makes the next available recover exactly once`() {
        val gate = NetworkRecoveryGate(initiallyAvailable = true)
        gate.onLost()
        assertTrue(gate.onAvailable())
        assertFalse(gate.onAvailable())
    }

    @Test fun `startup connection failures back off without waiting for HA countdown`() {
        val policy = DashboardRetryPolicy()
        assertEquals(5_000L, policy.connectionFailureDelay(wasConnected = false))
        assertEquals(10_000L, policy.afterRetry())
        assertEquals(10_000L, policy.connectionFailureDelay(wasConnected = false))
        assertEquals(20_000L, policy.afterRetry())
        assertEquals(20_000L, policy.connectionFailureDelay(wasConnected = false))
    }

    @Test fun `established dashboard keeps long websocket reconnect grace`() {
        val policy = DashboardRetryPolicy()
        assertEquals(90_000L, policy.connectionFailureDelay(wasConnected = true))
    }

    @Test fun `successful connection resets startup backoff`() {
        val policy = DashboardRetryPolicy()
        policy.afterRetry()
        policy.afterRetry()
        policy.reset()
        assertEquals(5_000L, policy.connectionFailureDelay(wasConnected = false))
    }

    @Test fun `learned network wait drives progress without claiming completion`() {
        assertEquals(0, networkWaitProgress(elapsedMs = 10_000L, estimateMs = 0L))
        assertEquals(500, networkWaitProgress(elapsedMs = 30_000L, estimateMs = 60_000L))
        assertEquals(950, networkWaitProgress(elapsedMs = 60_000L, estimateMs = 60_000L))
        assertEquals(950, networkWaitProgress(elapsedMs = 90_000L, estimateMs = 60_000L))
    }

    @Test fun `startup stages distinguish network address delay`() {
        fun stage(present: Boolean, link: Boolean, address: Boolean, default: Boolean) =
            startupNetworkStage(StartupNetworkSnapshot(present, link, address, default))

        assertEquals("Starting Android network services", stage(false, false, false, false))
        assertEquals("Waiting for a network link", stage(true, false, false, false))
        assertEquals(
            "Network link connected\nWaiting for a network address",
            stage(true, true, false, false),
        )
        assertEquals("Network address received\nPreparing the connection", stage(true, true, true, false))
        assertEquals("Network ready\nOpening Home Assistant", stage(true, true, true, true))
    }

    @Test fun `renderer generation rejects replaced and closed callbacks`() {
        val gate = RendererGenerationGate()
        val first = gate.open()
        assertTrue(gate.owns(first))

        val second = gate.open()
        assertFalse(gate.owns(first))
        assertTrue(gate.owns(second))

        gate.invalidate()
        assertFalse(gate.owns(second))
        val third = gate.open()
        gate.close()
        assertFalse(gate.owns(third))
        assertTrue(runCatching { gate.open() }.isFailure)
    }

    @Test fun `wake media recovery is generation checked and at most once per wake`() {
        val gate = WakeMediaRecoveryGate()
        val first = gate.begin(rendererGeneration = 4)
        assertEquals(first, gate.begin(rendererGeneration = 4))
        assertTrue(gate.owns(first))
        assertEquals(WakeMediaRecoveryAction.INSPECT, gate.onArmResult(first, candidates = 2))
        assertEquals(WakeMediaRecoveryAction.RELOAD, gate.onInspectResult(first, stalled = true))
        assertEquals(WakeMediaRecoveryAction.NONE, gate.onInspectResult(first, stalled = true))

        gate.invalidate()
        assertFalse(gate.owns(first))
        assertEquals(WakeMediaRecoveryAction.NONE, gate.onArmResult(first, candidates = 1))
        val second = gate.begin(rendererGeneration = 4)
        assertTrue(second.cycle > first.cycle)
        assertEquals(WakeMediaRecoveryAction.INSPECT, gate.onArmResult(second, candidates = 1))

        val replacement = gate.begin(rendererGeneration = 5)
        assertFalse(gate.owns(second))
        assertEquals(WakeMediaRecoveryAction.NONE, gate.onInspectResult(second, stalled = true))
        assertTrue(gate.owns(replacement))
        assertEquals(WakeMediaRecoveryAction.NONE, gate.onArmResult(replacement, candidates = 0))
        assertFalse(gate.owns(replacement))
        val healthy = gate.begin(rendererGeneration = 5)
        assertEquals(WakeMediaRecoveryAction.NONE, gate.onInspectResult(healthy, stalled = false))
        assertFalse(gate.owns(healthy))
        gate.close()
        assertTrue(runCatching { gate.begin(6) }.isFailure)
    }

    @Test fun `disconnected wake is retained only for its renderer generation`() {
        val gate = WakeMediaRecoveryGate()
        val deferred = gate.defer(rendererGeneration = 7)

        assertTrue(gate.owns(deferred))
        assertEquals(null, gate.activateDeferred(rendererGeneration = 8))
        assertEquals(deferred, gate.activateDeferred(rendererGeneration = 7))
        assertEquals(null, gate.activateDeferred(rendererGeneration = 7))

        val stale = gate.defer(rendererGeneration = 7)
        val replacement = gate.defer(rendererGeneration = 8)
        assertFalse(gate.owns(stale))
        assertEquals(null, gate.activateDeferred(rendererGeneration = 7))
        assertEquals(replacement, gate.activateDeferred(rendererGeneration = 8))

        gate.defer(rendererGeneration = 8)
        gate.invalidate()
        assertEquals(null, gate.activateDeferred(rendererGeneration = 8))

        gate.defer(rendererGeneration = 9)
        gate.close()
        assertEquals(null, gate.activateDeferred(rendererGeneration = 9))
    }

    @Test fun `javascript integer results reject malformed callbacks`() {
        assertEquals(1, javascriptIntResult("1"))
        assertEquals(-1, javascriptIntResult("\"-1\""))
        assertEquals(null, javascriptIntResult("null"))
        assertEquals(null, javascriptIntResult(null))
    }

    @Test fun `exact wake media scripts classify and sample nested dashboard video`() {
        val node = runCatching { ProcessBuilder("node", "--version").start().let { it.waitFor() == 0 } }
            .getOrDefault(false)
        assumeTrue("node unavailable", node)
        val arm = WakeMediaRecoveryScript.arm(7)
        val inspect = WakeMediaRecoveryScript.inspect(7)
        val harness =
            """
            global.window=globalThis;
            global.innerWidth=800;
            global.innerHeight=600;
            global.getComputedStyle=video=>video.style;
            const makeRoot=(videos=[],elements=[])=>({querySelectorAll(selector){return selector==='video'?videos:elements;}});
            const makeVideo=(values={})=>Object.assign({
              currentTime:0,webkitDecodedFrameCount:0,isConnected:true,ended:false,paused:false,autoplay:false,
              style:{display:'block',visibility:'visible',opacity:'1'},srcObject:null,playCalls:0,
              getBoundingClientRect(){return {width:320,height:180,top:0,left:0,bottom:180,right:320};},
              getVideoPlaybackQuality(){return {totalVideoFrames:this.webkitDecodedFrameCount};},
              play(){this.playCalls++;return {catch(){}};}
            },values);
            const arm=()=>($arm);
            const inspect=()=>($inspect);
            const expect=(actual,want,label)=>{if(actual!==want)throw Error(label+': got '+actual+', want '+want);};
            document=makeRoot();
            expect(arm(),0,'no video');
            const hidden=makeVideo({getBoundingClientRect(){return {width:0,height:0,top:0,left:0,bottom:0,right:0};}});
            document=makeRoot([hidden]);
            expect(arm(),0,'hidden video');
            const manualPause=makeVideo({paused:true});
            document=makeRoot([manualPause]);
            expect(arm(),0,'paused non-autoplay video');
            const ended=makeVideo({ended:true});
            document=makeRoot([ended]);
            expect(arm(),0,'ended video');
            const stalled=makeVideo();
            document=makeRoot([stalled]);
            expect(arm(),1,'visible stalled arm');
            expect(stalled.playCalls,1,'resume play attempt');
            expect(inspect(),1,'visible stalled inspect');
            const progressing=makeVideo();
            document=makeRoot([progressing]);
            expect(arm(),1,'progressing arm');
            progressing.currentTime=1;
            expect(inspect(),1,'time cannot mask flat supported frame counter');
            const timeOnly=makeVideo({webkitDecodedFrameCount:undefined,getVideoPlaybackQuality:null});
            document=makeRoot([timeOnly]);
            expect(arm(),1,'time-only arm');
            timeOnly.currentTime=1;
            expect(inspect(),0,'current time fallback without frame counters');
            const frameProgress=makeVideo();
            document=makeRoot([frameProgress]);
            expect(arm(),1,'frame arm');
            frameProgress.webkitDecodedFrameCount=1;
            expect(inspect(),0,'frame progress');
            const counterLost=makeVideo();
            document=makeRoot([counterLost]);
            expect(arm(),1,'counter-loss arm');
            counterLost.getVideoPlaybackQuality=null;
            counterLost.webkitDecodedFrameCount=undefined;
            counterLost.currentTime=1;
            expect(inspect(),-1,'lost supported counter is inconclusive, not time fallback');
            const reconnecting=makeVideo({paused:true,autoplay:true});
            document=makeRoot([reconnecting]);
            expect(arm(),1,'autoplay without initial track');
            reconnecting.currentTime=0.5;
            expect(inspect(),1,'late clock movement without decoded frames is stalled');
            const healthy=makeVideo();
            const otherStalled=makeVideo();
            document=makeRoot([healthy,otherStalled]);
            expect(arm(),2,'two videos');
            healthy.webkitDecodedFrameCount=1;
            expect(inspect(),1,'one healthy video cannot mask another stalled video');
            const healthyOne=makeVideo();
            const healthyTwo=makeVideo();
            document=makeRoot([healthyOne,healthyTwo]);
            expect(arm(),2,'two healthy videos arm');
            healthyOne.webkitDecodedFrameCount=1;
            healthyTwo.webkitDecodedFrameCount=1;
            expect(inspect(),0,'all visible videos progressing');
            const shadowVideo=makeVideo();
            const shadowRoot=makeRoot([shadowVideo]);
            document=makeRoot([], [{shadowRoot}]);
            expect(arm(),1,'open shadow root video');
            expect(inspect(),1,'shadow video stalled');
            const deepVideo=makeVideo();
            const deepRoot=makeRoot([deepVideo]);
            const middleRoot=makeRoot([], [{shadowRoot:deepRoot}]);
            document=makeRoot([], [{shadowRoot:middleRoot}]);
            expect(arm(),1,'recursive shadow root video');
            expect(inspect(),1,'recursive shadow video stalled');
            const replaced=makeVideo();
            document=makeRoot([replaced]);
            expect(arm(),1,'replace arm');
            replaced.isConnected=false;
            expect(inspect(),-1,'replaced node is inconclusive');
            const hiddenDuringSample=makeVideo();
            document=makeRoot([hiddenDuringSample]);
            expect(arm(),1,'hide arm');
            hiddenDuringSample.style.display='none';
            expect(inspect(),-1,'hidden during sample is inconclusive');
            const pausedDuringSample=makeVideo();
            document=makeRoot([pausedDuringSample]);
            expect(arm(),1,'pause arm');
            pausedDuringSample.paused=true;
            expect(inspect(),-1,'manual pause during sample is inconclusive');
            const liveTrack=makeVideo({paused:true,srcObject:{getVideoTracks(){return [{readyState:'live'}];}}});
            document=makeRoot([liveTrack]);
            expect(arm(),1,'live track candidate');
            expect(inspect(),1,'live track without progress');
            const audioOnly=makeVideo({paused:true,srcObject:{getVideoTracks(){return [];},getTracks(){return [{readyState:'live'}];}}});
            document=makeRoot([audioOnly]);
            expect(arm(),0,'audio-only stream is not video playback');
            if(window.__haPanelWakeMedia===undefined)throw Error('arm state missing before inspect');
            expect(inspect(),-1,'empty sample is inconclusive');
            if(window.__haPanelWakeMedia!==undefined)throw Error('inspect retained sampled nodes');
            """.trimIndent()
        val process = ProcessBuilder("node").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(harness) }
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }

    @Test fun `dashboard navigation stays on the configured authority`() {
        assertTrue(dashboardNavigationAllowed("https://ha.example", "https://HA.EXAMPLE/lovelace/0"))
        assertTrue(dashboardNavigationAllowed("http://ha.example", "https://ha.example/lovelace/0"))
        assertTrue(dashboardNavigationAllowed("http://ha.example:8123", "https://ha.example:8123/lovelace/0"))
        assertFalse(dashboardNavigationAllowed("https://ha.example", "http://ha.example/lovelace/0"))
        assertFalse(dashboardNavigationAllowed("https://ha.example:8123", "http://ha.example:8123/lovelace/0"))
        assertFalse(dashboardNavigationAllowed("https://ha.example", "https://ha.example:8443/lovelace/0"))
        assertFalse(dashboardNavigationAllowed("https://ha.example", "file://ha.example/data/local/tmp/page"))
        assertFalse(dashboardNavigationAllowed("https://ha.example", "https://other.example/lovelace/0"))
        assertFalse(dashboardNavigationAllowed("not a url", "https://ha.example/lovelace/0"))
    }

    @Test fun `physical panel oauth navigation admits only HA and local callback`() {
        assertTrue(panelHaOAuthNavigationAllowed("https://ha.example", "https://ha.example/auth/authorize?state=s"))
        assertTrue(panelHaOAuthNavigationAllowed("https://ha.example", "http://127.0.0.1:8888/api/v1/ha/oauth/callback?state=s&code=c"))
        assertTrue(panelHaOAuthNavigationAllowed("https://ha.example", "http://localhost:8888/api/v1/ha/oauth/callback?state=s&code=c"))
        assertFalse(panelHaOAuthNavigationAllowed("https://ha.example", "http://ha.example/lovelace/0"))
        assertFalse(panelHaOAuthNavigationAllowed("https://ha.example", "http://127.0.0.1:8888/configure"))
        assertFalse(panelHaOAuthNavigationAllowed("https://ha.example", "http://other.example:8888/api/v1/ha/oauth/callback?state=s"))
    }

    @Test fun `physical panel oauth start url targets local control surface`() {
        assertEquals(
            "http://127.0.0.1:8888/api/v1/ha/oauth/panel-start?ha_url=https%3A%2F%2Fha.example%3A8123",
            panelHaOAuthStartUrl("https://ha.example:8123/"),
        )
    }

    @Test fun `an incomplete admission check recovers on its own`() {
        // The panel never got an answer it can act on, and every one of these clears server-side with
        // nobody at the panel — so each must arm the fast ladder.
        listOf(
            AdmissionOutcome.TRANSPORT_FAILED,
            AdmissionOutcome.DASHBOARD_LIST_UNREADABLE,
            AdmissionOutcome.SIGN_IN_PAGE_UNREACHABLE,
            AdmissionOutcome.BRIDGE_HANDSHAKE_MISSED,
            AdmissionOutcome.BRIDGE_ATTACH_FAILED,
        ).forEach { assertEquals(it.name, AdmissionRetryClass.FROM_BASE, admissionRetryClass(it)) }
    }

    @Test fun `a server-repairable answer nobody will announce is probed slowly`() {
        // Creating a dashboard, or a proxy that stops mangling the version, happens entirely
        // server-side and nothing tells the panel — so asking again slowly is the only way it finds
        // out. This is the round-2 regression: NO_LEGAL_DASHBOARD was latched and its resolution
        // cached, so restoring access left the panel blocked until somebody touched it.
        listOf(
            AdmissionOutcome.VERSION_UNVERIFIABLE,
            AdmissionOutcome.NO_LEGAL_DASHBOARD,
        ).forEach { assertEquals(it.name, AdmissionRetryClass.AT_CEILING, admissionRetryClass(it)) }
    }

    @Test fun `a failed bridge attachment on a capable WebView is retried, unlike a missing capability`() {
        // The capability check and the attachment attempt are different evidence: a provider update or
        // process death mid-session fails attachment on a WebView that genuinely supports the bridge,
        // and a fresh WebView can succeed. Only the absent capability is terminal.
        assertEquals(AdmissionRetryClass.FROM_BASE, admissionRetryClass(AdmissionOutcome.BRIDGE_ATTACH_FAILED))
        assertEquals(AdmissionRetryClass.MANUAL_ONLY, admissionRetryClass(AdmissionOutcome.BRIDGE_UNAVAILABLE))
    }

    @Test fun `resume owns countdown visibility only where the top-resumed callback does not exist`() {
        // onTopResumedActivityChanged arrives from API 29. Below that it is never delivered, so without
        // a resume-owned path the countdown would stay blank forever while its retry fired invisibly.
        assertTrue("API 26 has no top-resumed callback", resumeOwnsAdmissionVisibility(26))
        assertTrue("API 28 has no top-resumed callback", resumeOwnsAdmissionVisibility(28))
        assertFalse("API 29 delivers the precise signal", resumeOwnsAdmissionVisibility(29))
        assertFalse(resumeOwnsAdmissionVisibility(34))
    }

    @Test fun `an answer carried by an event or owned by a person never runs a timer`() {
        // An ABSENT credential is repaired by connecting the panel, which relaunches admission
        // immediately, and there is nothing to re-ask with meanwhile. A refused credential must not
        // be replayed unattended because HA can ban repeated login failures. The other two are
        // maintainer-designated terminal outcomes.
        listOf(
            AdmissionOutcome.SIGN_IN_REQUIRED,
            AdmissionOutcome.CREDENTIAL_REFUSED,
            AdmissionOutcome.UNSUPPORTED_HA,
            AdmissionOutcome.BRIDGE_UNAVAILABLE,
        ).forEach { assertEquals(it.name, AdmissionRetryClass.MANUAL_ONLY, admissionRetryClass(it)) }
    }

    @Test fun `a refused credential waits for an explicit retry instead of risking a login ban`() {
        assertEquals(AdmissionRetryClass.MANUAL_ONLY, admissionRetryClass(AdmissionOutcome.CREDENTIAL_REFUSED))
        assertNotEquals(AdmissionRetryClass.FROM_BASE, admissionRetryClass(AdmissionOutcome.CREDENTIAL_REFUSED))
    }

    @Test fun `a missed handshake and an absent credential are classified oppositely`() {
        // The two boundaries that were previously inverted, pinned against each other: a capable
        // WebView that simply missed the exchange recovers, while a panel holding no credential does
        // not, because there is nothing to re-ask with.
        assertEquals(AdmissionRetryClass.FROM_BASE, admissionRetryClass(AdmissionOutcome.BRIDGE_HANDSHAKE_MISSED))
        assertEquals(AdmissionRetryClass.MANUAL_ONLY, admissionRetryClass(AdmissionOutcome.SIGN_IN_REQUIRED))
        // ...and a missed handshake is not the same evidence as a WebView that cannot bridge at all.
        assertEquals(AdmissionRetryClass.MANUAL_ONLY, admissionRetryClass(AdmissionOutcome.BRIDGE_UNAVAILABLE))
        // No blocked outcome may be left without any route back: the explicit retry action owns
        // credential refusal, while the other outcomes below need configuration or platform repair.
        val latched = AdmissionOutcome.entries.filter { admissionRetryClass(it) == AdmissionRetryClass.MANUAL_ONLY }
        assertEquals(
            setOf(
                AdmissionOutcome.SIGN_IN_REQUIRED,
                AdmissionOutcome.CREDENTIAL_REFUSED,
                AdmissionOutcome.UNSUPPORTED_HA,
                AdmissionOutcome.BRIDGE_UNAVAILABLE,
            ),
            latched.toSet(),
        )
    }

    @Test fun `an unusable answer is retried slowly, not on the fast ladder`() {
        assertEquals(AdmissionRetryClass.AT_CEILING, admissionRetryClass(AdmissionOutcome.VERSION_UNVERIFIABLE))
    }

    @Test fun `every admission outcome is classified deliberately`() {
        // A new outcome must be given a class here rather than inheriting one, so the exhaustive set
        // is asserted by size and by every value resolving.
        assertEquals(11, AdmissionOutcome.entries.size)
        AdmissionOutcome.entries.forEach { assertNotNull(it.name, admissionRetryClass(it)) }
    }

    // --- the visible countdown, driven as a lifecycle rather than asserted from source ---

    private class Clock(var now: Long = 0L) : () -> Long { override fun invoke() = now }

    @Test fun `an armed countdown paints and reschedules only while it is visible`() {
        val clock = Clock()
        val owner = AdmissionCountdownOwner(clock)

        // Armed while nothing is visible yet: no repaint, nothing scheduled — but it IS armed.
        val armed = owner.arm(30_000L)
        assertTrue(owner.armed)
        assertNull(armed.text)
        assertNull(armed.scheduleNextTickMs)

        val shown = owner.onVisibilityChanged(true)
        assertEquals("Retrying automatically in 30s", shown.text)
        assertEquals(1_000L, shown.scheduleNextTickMs)

        clock.now += 10_000L
        val tick = owner.onTick()
        assertEquals("Retrying automatically in 20s", tick.text)
        assertEquals(1_000L, tick.scheduleNextTickMs)
    }

    @Test fun `losing top visibility stops the repaint without disarming the retry`() {
        val clock = Clock()
        val owner = AdmissionCountdownOwner(clock)
        owner.arm(60_000L)
        owner.onVisibilityChanged(true)

        val hidden = owner.onVisibilityChanged(false)
        assertNull("a hidden countdown must not repaint", hidden.text)
        assertNull("a hidden countdown must not reschedule per-second work", hidden.scheduleNextTickMs)
        assertTrue("the retry it describes must survive being hidden", owner.armed)
        // Ticks that were already in flight when visibility was lost do nothing either.
        clock.now += 5_000L
        assertNull(owner.onTick().text)
        assertNull(owner.onTick().scheduleNextTickMs)
        assertTrue(owner.armed)
    }

    @Test fun `returning to visibility reconciles to the true remaining time immediately`() {
        val clock = Clock()
        val owner = AdmissionCountdownOwner(clock)
        owner.arm(120_000L)
        owner.onVisibilityChanged(true)
        owner.onVisibilityChanged(false)

        clock.now += 45_000L                       // hidden for 45s
        val back = owner.onVisibilityChanged(true)
        assertEquals("Retrying automatically in 1m 15s", back.text)
        assertEquals(1_000L, back.scheduleNextTickMs)
    }

    @Test fun `an elapsed countdown paints its final figure and stops rescheduling`() {
        val clock = Clock()
        val owner = AdmissionCountdownOwner(clock)
        owner.arm(5_000L)
        owner.onVisibilityChanged(true)

        clock.now += 5_000L
        val done = owner.onTick()
        assertEquals("Retrying automatically in 0s", done.text)
        assertNull("nothing more to count", done.scheduleNextTickMs)
    }

    @Test fun `a disarmed countdown never paints even while visible`() {
        val clock = Clock()
        val owner = AdmissionCountdownOwner(clock)
        owner.arm(30_000L)
        owner.onVisibilityChanged(true)
        owner.disarm()

        assertFalse(owner.armed)
        assertNull(owner.onTick().text)
        assertNull(owner.onVisibilityChanged(true).text)
    }

    @Test fun `admission retries back off from the base and stop growing at the ceiling`() {
        val policy = AdmissionRetryPolicy(jitterSource = { 0.5 })   // 0.5 → zero jitter offset
        val ladder = generateSequence { policy.nextDelayMs(AdmissionRetryClass.FROM_BASE) }.take(8).toList()
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L), ladder)
    }

    @Test fun `jitter spreads the armed delay symmetrically and is bounded`() {
        val low = AdmissionRetryPolicy(jitterSource = { 0.0 }).nextDelayMs(AdmissionRetryClass.AT_CEILING)
        val high = AdmissionRetryPolicy(jitterSource = { 1.0 }).nextDelayMs(AdmissionRetryClass.AT_CEILING)
        assertEquals(240_000L, low)      // 300s − 20%
        assertEquals(360_000L, high)     // 300s + 20%
        // The jittered floor keeps a pathological small base from arming a sub-second hammer.
        assertEquals(1_000L, AdmissionRetryPolicy(baseMs = 1_000L, jitterSource = { 0.0 }).nextDelayMs(AdmissionRetryClass.FROM_BASE))
    }

    @Test fun `ceiling-cadence and manual-only arms never advance the transport ladder`() {
        val policy = AdmissionRetryPolicy(jitterSource = { 0.5 })
        assertEquals(300_000L, policy.nextDelayMs(AdmissionRetryClass.AT_CEILING))
        assertEquals(null, policy.nextDelayMs(AdmissionRetryClass.MANUAL_ONLY))
        assertEquals("a ceiling probe must not inflate the next transport retry", 5_000L, policy.nextDelayMs(AdmissionRetryClass.FROM_BASE))
    }

    @Test fun `manual retry resets the admission backoff`() {
        val policy = AdmissionRetryPolicy(jitterSource = { 0.5 })
        policy.nextDelayMs(AdmissionRetryClass.FROM_BASE)
        policy.nextDelayMs(AdmissionRetryClass.FROM_BASE)
        policy.reset()
        assertEquals(5_000L, policy.nextDelayMs(AdmissionRetryClass.FROM_BASE))
    }

    @Test fun `admission countdown is a ceiled real number, never zero while pending`() {
        assertEquals("Retrying automatically in 47s", admissionRetryCountdown(47_000L))
        assertEquals("Retrying automatically in 1s", admissionRetryCountdown(1L))
        assertEquals("Retrying automatically in 0s", admissionRetryCountdown(0L))
        assertEquals("Retrying automatically in 5m 0s", admissionRetryCountdown(300_000L))
        assertEquals("Retrying automatically in 4m 1s", admissionRetryCountdown(240_001L))
    }

    @Test fun `document start origins mirror allowed scheme upgrades without broadening authority`() {
        assertEquals(setOf("https://ha.example"), dashboardDocumentStartOrigins("https://HA.EXAMPLE/lovelace"))
        assertEquals(
            linkedSetOf("http://ha.example", "https://ha.example"),
            dashboardDocumentStartOrigins("http://HA.EXAMPLE/lovelace"),
        )
        assertEquals(
            linkedSetOf("http://ha.example:8123", "https://ha.example:8123"),
            dashboardDocumentStartOrigins("http://ha.example:8123/lovelace"),
        )
        assertEquals(
            linkedSetOf("http://ha.example", "https://ha.example:80"),
            dashboardDocumentStartOrigins("http://ha.example:80/lovelace"),
        )
        assertEquals(
            setOf("https://ha.example:8443"),
            dashboardDocumentStartOrigins("https://ha.example:8443/lovelace"),
        )
    }
}
