package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
