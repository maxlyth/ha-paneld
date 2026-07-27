package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.device.ScreenOff
import io.github.maxlyth.hapaneld.sensors.HaPresenceActivityMarker
import io.github.maxlyth.hapaneld.sensors.HaPresenceAggregate
import io.github.maxlyth.hapaneld.sensors.HaPresencePhase
import io.github.maxlyth.hapaneld.sensors.HaPresenceRequest
import io.github.maxlyth.hapaneld.sensors.HaPresenceSelectedHistory
import io.github.maxlyth.hapaneld.sensors.HaPresenceTransition
import io.github.maxlyth.hapaneld.sensors.HaPresenceValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class AutoSleepControllerTest {
    @Test fun `history uses one hour warmup current lease and selected Area sources`() {
        val requested = CopyOnWriteArrayList<Pair<Long, Long>>()
        Harness(history = { start, end ->
            requested += start to end
            HaPresenceSelectedHistory(
                setOf(SOURCE), start, end,
                mapOf(SOURCE to listOf(
                    HaPresenceTransition(SOURCE, start, HaPresenceValue.OFF),
                    HaPresenceTransition(SOURCE, start + MINUTE, HaPresenceValue.ON),
                    HaPresenceTransition(SOURCE, start + 2 * MINUTE, HaPresenceValue.OFF),
                )),
                mapOf(SOURCE to "Kitchen motion"),
            )
        }).use { h ->
            h.wallNow.set(8 * 60 * MINUTE)
            val request = h.start()
            h.offer(aggregate(request, 0L, HaPresenceValue.OFF))
            h.await { h.status().getString("phase") == "live" }

            val json = JSONObject(runBlocking { h.controller.historyJson(6) })

            assertTrue(json.getBoolean("available"))
            assertEquals(2 * 60 * MINUTE, json.getLong("window_start_epoch_ms"))
            assertEquals(8 * 60 * MINUTE, json.getLong("window_end_epoch_ms"))
            assertEquals(60 * MINUTE, json.getLong("warmup_ms"))
            assertEquals(MINUTE, json.getLong("bucket_ms"))
            assertEquals(MIN_AUTO_SLEEP_LEASE_MS, json.getLong("learned_lease_ms"))
            assertEquals(listOf(60 * MINUTE to 8 * 60 * MINUTE), requested)
            assertEquals(1, json.getInt("source_count"))
            assertFalse(json.has("sources"))
            assertTrue(json.getJSONArray("segments").length() > 0)
            assertTrue(json.getJSONArray("segments").length() <= 360)
            val lane = json.getJSONArray("source_lanes").getJSONObject(0)
            assertEquals("Kitchen motion", lane.getString("label"))
            assertTrue(lane.getJSONArray("segments").length() > 0)
            assertEquals(3, json.getJSONObject("diagnostics").getInt("history_rows"))
        }
    }

    @Test fun `identical concurrent history requests share one HA read`() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicLong()
        Harness(history = { start, end ->
            calls.incrementAndGet()
            entered.complete(Unit)
            release.await()
            HaPresenceSelectedHistory(
                setOf(SOURCE), start, end,
                mapOf(SOURCE to listOf(HaPresenceTransition(SOURCE, start, HaPresenceValue.OFF))),
            )
        }).use { h ->
            h.wallNow.set(8 * 60 * MINUTE)
            val request = h.start()
            h.offer(aggregate(request, 0L, HaPresenceValue.OFF))
            h.await { h.status().getString("phase") == "live" }

            runBlocking {
                val first = async(start = CoroutineStart.UNDISPATCHED) { h.controller.historyJson(6) }
                entered.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) { h.controller.historyJson(6) }
                assertEquals(1L, calls.get())
                release.complete(Unit)
                assertEquals(first.await(), second.await())
            }
            assertEquals(1L, calls.get())
        }
    }

    @Test fun `new projection revision cannot join an older same-hours history flight`() {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val calls = AtomicLong()
        Harness(history = { start, end ->
            val call = calls.incrementAndGet()
            if (call == 1L) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
            HaPresenceSelectedHistory(
                setOf(SOURCE), start, end,
                mapOf(SOURCE to listOf(HaPresenceTransition(SOURCE, start, HaPresenceValue.OFF))),
            )
        }).use { h ->
            h.wallNow.set(8 * 60 * MINUTE)
            val request = h.start()
            h.offer(aggregate(request, 0L, HaPresenceValue.OFF))
            h.await { h.status().getString("phase") == "live" }

            runBlocking {
                val first = async(start = CoroutineStart.UNDISPATCHED) { h.controller.historyJson(6) }
                firstEntered.await()
                h.offer(aggregate(
                    request,
                    1L,
                    HaPresenceValue.ON,
                    marker = HaPresenceActivityMarker(1L, SOURCE, h.now.get()),
                ))
                h.await { h.status().getString("reason") == "source_active" }
                val second = async(start = CoroutineStart.UNDISPATCHED) { h.controller.historyJson(6) }
                assertEquals(1L, calls.get())
                releaseFirst.complete(Unit)
                first.await()
                second.await()
            }
            assertEquals(2L, calls.get())
        }
    }

    @Test fun `history transport failure returns an unavailable projection`() {
        Harness(history = { _, _ -> error("offline") }).use { h ->
            h.wallNow.set(8 * 60 * MINUTE)
            val request = h.start()
            h.offer(aggregate(request, 0L, HaPresenceValue.OFF))
            h.await { h.status().getString("phase") == "live" }

            val json = JSONObject(runBlocking { h.controller.historyJson(6) })

            assertFalse(json.getBoolean("available"))
            assertEquals("history_unavailable", json.getString("detail"))
            assertEquals(0, json.getJSONArray("segments").length())
            assertEquals(0, json.getJSONArray("source_lanes").length())
        }
    }

    @Test fun `typed history failure detail survives the replay projection`() {
        Harness(history = { _, _ -> error("history_parse") }).use { h ->
            h.wallNow.set(8 * 60 * MINUTE)
            val request = h.start()
            h.offer(aggregate(request, 0L, HaPresenceValue.OFF))
            h.await { h.status().getString("phase") == "live" }

            val json = JSONObject(runBlocking { h.controller.historyJson(6) })

            assertFalse(json.getBoolean("available"))
            assertEquals("history_parse", json.getString("detail"))
        }
    }

    @Test fun `history keeps every selected source and disambiguates duplicate labels`() {
        val ids = (1..40).mapTo(linkedSetOf()) { "binary_sensor.motion_$it" }
        Harness(history = { start, end ->
            HaPresenceSelectedHistory(
                ids, start, end,
                ids.associateWith { id -> listOf(HaPresenceTransition(id, start, HaPresenceValue.OFF)) },
                ids.associateWith { id -> if (id == "binary_sensor.motion_1") id else "Motion" },
            )
        }).use { h ->
            h.wallNow.set(8 * 60 * MINUTE)
            val request = h.start()
            h.offer(aggregate(request, 0L, HaPresenceValue.OFF, sources = ids))
            h.await { h.status().getString("phase") == "live" }

            val lanes = JSONObject(runBlocking { h.controller.historyJson(1) }).getJSONArray("source_lanes")

            assertEquals(40, lanes.length())
            assertEquals("Activity source", lanes.getJSONObject(0).getString("label"))
            assertEquals("Motion", lanes.getJSONObject(1).getString("label"))
            assertEquals("Motion (2)", lanes.getJSONObject(2).getString("label"))
        }
    }

    @Test fun `history buckets a well provisioned source set instead of rejecting its count`() {
        val ids = (1..300).mapTo(linkedSetOf()) { "binary_sensor.motion_$it" }
        Harness(history = { start, end ->
            HaPresenceSelectedHistory(
                ids, start, end,
                ids.associateWith { id -> listOf(HaPresenceTransition(id, start, HaPresenceValue.OFF)) },
                ids.associateWith { "Motion" },
            )
        }).use { h ->
            h.wallNow.set(8 * 60 * MINUTE)
            val request = h.start()
            h.offer(aggregate(request, 0L, HaPresenceValue.OFF, sources = ids))
            h.await { h.status().getString("phase") == "live" }

            val json = JSONObject(runBlocking { h.controller.historyJson(48) })

            assertTrue(json.getBoolean("available"))
            assertEquals(300, json.getJSONArray("source_lanes").length())
            assertTrue(json.getLong("source_bucket_ms") > MINUTE)
        }
    }

    @Test fun `suppressed history lane remains visible but cannot affect replay`() {
        val excluded = "binary_sensor.excluded"
        Harness(history = { start, end ->
            HaPresenceSelectedHistory(
                sourceIds = setOf(SOURCE),
                startEpochMs = start,
                endEpochMs = end,
                transitions = mapOf(
                    SOURCE to listOf(HaPresenceTransition(SOURCE, start, HaPresenceValue.OFF)),
                    excluded to listOf(HaPresenceTransition(excluded, start, HaPresenceValue.ON)),
                ),
                sourceLabels = mapOf(SOURCE to "Included", excluded to "Suppressed"),
                discoveredSourceIds = setOf(SOURCE, excluded),
                excludedSourceIds = setOf(excluded),
                areaName = "Office",
                areaKey = "a".repeat(64),
                sourceKeys = mapOf(SOURCE to "b".repeat(64), excluded to "c".repeat(64)),
            )
        }).use { h ->
            h.wallNow.set(8 * 60 * MINUTE)
            val request = h.start()
            h.offer(aggregate(request, 0L, HaPresenceValue.OFF))
            h.await { h.status().getString("phase") == "live" }

            val raw = runBlocking { h.controller.historyJson(6) }
            val json = JSONObject(raw)
            assertEquals("Office", json.getString("area_name"))
            assertEquals(1, json.getInt("source_count"))
            assertEquals(2, json.getInt("discovered_source_count"))
            val lanes = json.getJSONArray("source_lanes")
            assertEquals(2, lanes.length())
            val byLabel = (0 until lanes.length()).associate { index ->
                lanes.getJSONObject(index).getString("label") to lanes.getJSONObject(index)
            }
            assertTrue(byLabel.getValue("Included").getBoolean("included"))
            assertFalse(byLabel.getValue("Suppressed").getBoolean("included"))
            assertFalse(raw.contains(excluded))
            val outputs = json.getJSONArray("segments")
            assertTrue((0 until outputs.length()).none {
                outputs.getJSONObject(it).getString("output") == "hold_awake"
            })
        }
    }

    @Test fun discoveryDiagnosticSurvivesControllerAndHttpProjection() = Harness().use { h ->
        val request = h.start()
        h.offer(aggregate(
            request,
            revision = 0L,
            state = HaPresenceValue.UNAVAILABLE,
            phase = HaPresencePhase.DISCOVERY_FAILED,
            hydrated = false,
            detail = "registry_transport",
        ))

        h.await { h.status().getString("phase") == "discovery_failed" }
        assertEquals("registry_transport", h.status().getString("detail"))
    }

    @Test fun aggregateFloodIsBoundedAndConvergesToTheLatestState() = Harness().use { h ->
        val request = h.start()
        repeat(10_000) { revision ->
            assertTrue(h.offer(aggregate(request, revision.toLong(), HaPresenceValue.OFF)))
        }
        assertTrue(h.offer(aggregate(request, 10_000L, HaPresenceValue.ON,
            marker = HaPresenceActivityMarker(1L, SOURCE, h.now.get()))))

        h.await { h.status().getString("reason") == "source_active" }
        assertEquals(1, h.status().getInt("source_count"))
    }

    @Test fun `status reports the complete automatic source union`() = Harness().use { h ->
        val request = h.start()
        val sources = (1..32).mapTo(linkedSetOf()) { "binary_sensor.presence_$it" }

        h.offer(aggregate(request, 1L, HaPresenceValue.OFF, sources = sources))

        h.await { h.status().getString("phase") == "live" }
        assertEquals(32, h.status().getInt("source_count"))
    }

    @Test fun staleControllerManagerAndFeedInputsCannotReplaceAcceptedState() = Harness().use { h ->
        val request = h.start()
        h.offer(aggregate(request, 2L, HaPresenceValue.ON, manager = 4L,
            marker = HaPresenceActivityMarker(2L, SOURCE, 0L)))
        h.await { h.status().getString("reason") == "source_active" }

        h.offer(aggregate(request.copy(controllerEpoch = request.controllerEpoch - 1L), 9L, HaPresenceValue.OFF,
            manager = 9L))
        h.offer(aggregate(request, 3L, HaPresenceValue.OFF, manager = 3L))
        h.offer(aggregate(request, 1L, HaPresenceValue.OFF, manager = 4L))

        Thread.sleep(50L)
        assertEquals("source_active", h.status().getString("reason"))
    }

    @Test fun sameMarkerAcrossManagerRefreshDoesNotReplayActivity() = Harness().use { h ->
        val first = h.start()
        val marker = HaPresenceActivityMarker(1L, SOURCE, 0L)
        h.offer(aggregate(first, 0L, HaPresenceValue.OFF, marker = marker))
        h.await { h.status().getString("phase") == "live" }

        h.now.set(5 * MINUTE)
        assertTrue(h.controller.refresh())
        val second = h.awaitRequest(2)
        h.offer(aggregate(second, 0L, HaPresenceValue.OFF, manager = 2L, marker = marker))
        h.now.set(15 * MINUTE)
        h.controller.advanceToForTest(h.now.get())

        h.await { h.screen.isIntendedOff() }
    }

    @Test fun advancedFinalOffMarkerDuringDiscoveryExtendsFromItsOwnTime() = Harness().use { h ->
        val first = h.start()
        h.offer(aggregate(first, 0L, HaPresenceValue.OFF,
            marker = HaPresenceActivityMarker(1L, SOURCE, 0L)))
        h.await { h.status().getString("phase") == "live" }

        h.now.set(5 * MINUTE)
        h.controller.refresh()
        val second = h.awaitRequest(2)
        h.now.set(8 * MINUTE)
        h.offer(aggregate(second, 1L, HaPresenceValue.OFF, manager = 2L,
            marker = HaPresenceActivityMarker(2L, SOURCE, h.now.get())))
        h.await { h.status().getString("phase") == "live" }

        h.now.set(15 * MINUTE)
        h.controller.advanceToForTest(h.now.get())
        Thread.sleep(30L)
        assertFalse("advanced marker must retain its full lease", h.screen.isIntendedOff())
        h.now.set(23 * MINUTE)
        h.controller.advanceToForTest(h.now.get())
        h.await { h.screen.isIntendedOff() }
    }

    @Test fun aNewFeedGenerationAcceptsRevisionZero() = Harness().use { h ->
        val request = h.start()
        h.offer(aggregate(request, 8L, HaPresenceValue.OFF, feed = 1L))
        h.await { h.status().getString("phase") == "live" }
        h.now.set(MINUTE)
        h.offer(aggregate(request, 0L, HaPresenceValue.ON, feed = 2L,
            marker = HaPresenceActivityMarker(1L, SOURCE, h.now.get())))

        h.await { h.status().getString("reason") == "source_active" }
    }

    @Test fun rawTouchBeforeTapCallbackTrainsExactlyOnce() = Harness().use { h ->
        val epoch = h.sleepAutomatically()
        h.controller.noteTouchForTest(h.now.get(), epoch.generation)
        h.wakeTap.fireTap()

        h.await { h.learning.corrections.size == 1 }
        assertEquals(1, h.learning.corrections.size)
    }

    @Test fun tapCallbackBeforeQueuedRawTouchTrainsExactlyOnce() = Harness().use { h ->
        val epoch = h.sleepAutomatically()
        h.wakeTap.fireTap()
        h.controller.noteTouchForTest(h.now.get(), epoch.generation)

        h.await { h.learning.corrections.size == 1 }
        Thread.sleep(30L)
        assertEquals(1, h.learning.corrections.size)
    }

    @Test fun rawTouchCanCausallyWakeTheVisibleNoOverlayFallback() {
        Harness(wakeTapAvailable = false).use { h ->
            val epoch = h.sleepAutomatically()
            h.controller.noteTouchForTest(h.now.get(), epoch.generation)

            h.await { h.learning.corrections.size == 1 }
            assertFalse(h.screen.isIntendedOff())
        }
    }

    @Test fun sourceWakeThenTouchNeverManufacturesTapProof() = Harness().use { h ->
        val request = h.prepareAutomaticSleep()
        h.now.incrementAndGet()
        h.offer(aggregate(request, 1L, HaPresenceValue.ON,
            marker = HaPresenceActivityMarker(1L, SOURCE, h.now.get())))
        h.await { !h.screen.isIntendedOff() }

        h.controller.noteTouchForTest(h.now.incrementAndGet(), null)
        Thread.sleep(30L)
        assertTrue(h.learning.corrections.isEmpty())
    }

    @Test fun proximityWakeThenTouchNeverManufacturesTapProof() = Harness().use { h ->
        h.prepareAutomaticSleep()
        h.controller.noteProximityState(true)
        h.await { !h.screen.isIntendedOff() }

        h.controller.noteTouchForTest(h.now.incrementAndGet(), null)
        Thread.sleep(30L)
        assertTrue(h.learning.corrections.isEmpty())
    }

    @Test fun genericScreenWakeThenTouchNeverManufacturesTapProof() = Harness().use { h ->
        h.prepareAutomaticSleep()
        h.screen.wake()
        h.await { !h.screen.isIntendedOff() }

        h.controller.noteTouchForTest(h.now.incrementAndGet(), null)
        Thread.sleep(30L)
        assertTrue(h.learning.corrections.isEmpty())
    }

    @Test fun reconnectWakeThenTouchNeverManufacturesTapProof() = Harness().use { h ->
        val request = h.prepareAutomaticSleep()
        h.offer(aggregate(request, 1L, HaPresenceValue.OFF,
            phase = HaPresencePhase.RECONNECTING, hydrated = false))
        h.await { !h.screen.isIntendedOff() }

        h.controller.noteTouchForTest(h.now.incrementAndGet(), null)
        Thread.sleep(30L)
        assertTrue(h.learning.corrections.isEmpty())
    }

    @Test fun staleDeadlineCannotActuateAcrossRefresh() = runTest {
        val h = Harness(this, StandardTestDispatcher(testScheduler))
        assertTrue(h.controller.start())
        runCurrent()
        val first = h.requests.single()
        h.offer(aggregate(first, 0L, HaPresenceValue.OFF))
        runCurrent()
        assertEquals("live", h.status().getString("phase"))
        val staleToken = h.controller.deadlineTokenForTest()

        h.now.set(5 * MINUTE)
        h.controller.refresh()
        runCurrent()
        val second = h.requests[1]
        h.offer(aggregate(second, 1L, HaPresenceValue.OFF, manager = 2L))
        runCurrent()
        assertEquals(1L, h.controller.feedPositionForTest()?.revision)
        assertNotEquals(staleToken, h.controller.deadlineTokenForTest())
        h.now.set(10 * MINUTE)
        h.controller.advanceToForTest(10 * MINUTE, staleToken)
        runCurrent()

        assertFalse(h.screen.isIntendedOff())
        h.closeWithVirtualTime(::runCurrent)
    }

    @Test fun disabledConfigurationOwnsNoPeriodicRefresh() = Harness(enabled = false).use { h ->
        h.start()
        Thread.sleep(100L)
        assertEquals(1, h.requests.size)
        assertFalse(h.requests.single().enabled)
    }

    @Test fun enabledControllerRediscoveryRunsExactlyOncePerDayAndReschedules() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = Harness(this, dispatcher)
        assertTrue(h.controller.start())
        runCurrent()

        advanceTimeBy(DAY - 1L)
        runCurrent()
        assertEquals(0L, h.managerRefreshes.get())

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1L, h.managerRefreshes.get())
        runCurrent()
        assertEquals("the first deadline must not duplicate refresh", 1L, h.managerRefreshes.get())

        advanceTimeBy(DAY)
        runCurrent()
        assertEquals("a completed refresh must schedule exactly one successor", 2L, h.managerRefreshes.get())
        h.closeWithVirtualTime(::runCurrent)
    }

    @Test fun disablingCancelsPendingDailyRediscovery() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = Harness(this, dispatcher)
        assertTrue(h.controller.start())
        runCurrent()

        advanceTimeBy(DAY / 2L)
        h.setEnabled(false)
        runCurrent()
        advanceTimeBy(DAY * 2L)
        runCurrent()

        assertEquals(0L, h.managerRefreshes.get())
        assertFalse(h.requests.last().enabled)
        h.closeWithVirtualTime(::runCurrent)
    }

    @Test fun closeCancelsPendingDailyRediscovery() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val h = Harness(this, dispatcher)
        assertTrue(h.controller.start())
        runCurrent()

        h.closeWithVirtualTime(::runCurrent)
        advanceTimeBy(DAY * 2L)
        runCurrent()

        assertEquals(0L, h.managerRefreshes.get())
    }

    @Test fun disablingWakesTheExactAutomaticOffEpoch() = Harness().use { h ->
        h.prepareAutomaticSleep()
        h.setEnabled(false)

        h.await { !h.screen.isIntendedOff() }
        assertFalse(h.awaitRequest(2).enabled)
    }

    @Test fun closeRejectsNewAggregatesAfterDrainingAndFlushing() {
        val h = Harness()
        val request = h.start()
        assertTrue(h.offer(aggregate(request, 0L, HaPresenceValue.OFF)))

        assertTrue(h.controller.closeAndJoin(2_000L))

        assertTrue(h.managerClosed.get())
        assertTrue(h.learning.flushed.get())
        assertFalse(h.offer(aggregate(request, 1L, HaPresenceValue.ON)))
        h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun closeDrainsAdmittedEvidenceBeforeFlushWithoutActuating() = runTest {
        val h = Harness(this, StandardTestDispatcher(testScheduler))
        assertTrue(h.controller.start())
        runCurrent()
        val request = h.requests.single()
        h.offer(aggregate(request, 0L, HaPresenceValue.OFF))
        runCurrent()
        h.now.set(15 * MINUTE)
        h.controller.advanceToForTest(h.now.get())
        runCurrent()
        assertTrue(h.screen.isIntendedOff())
        val ownedGeneration = h.screen.currentOffGeneration()
        h.now.incrementAndGet()
        assertTrue(h.offer(aggregate(request, 1L, HaPresenceValue.OFF,
            marker = HaPresenceActivityMarker(1L, SOURCE, h.now.get()))))
        assertTrue(h.controller.noteProximityState(true))

        val closeResult = AtomicBoolean()
        val closer = Thread { closeResult.set(h.controller.closeAndJoin(2_000L)) }.apply { start() }
        while (!h.managerClosed.get()) Thread.yield()
        runCurrent()
        closer.join(2_000L)

        assertTrue(closeResult.get())
        assertEquals(ownedGeneration, h.screen.currentOffGeneration())
        assertTrue("admitted evidence must precede terminal flush: ${h.learning.events}",
            h.learning.events.indexOf("gap") in 0 until h.learning.events.indexOf("flush"))
    }

    @Test fun configureRaceCannotReopenTerminalAdmission() {
        val h = Harness()
        h.start()
        val running = AtomicBoolean(true)
        val refresher = Thread {
            while (running.get()) h.controller.refresh()
        }.apply { start() }

        assertTrue(h.controller.closeAndJoin(2_000L))
        running.set(false)
        refresher.join(1_000L)
        val settledRequests = h.requests.size
        repeat(100) { assertFalse(h.controller.refresh()) }
        Thread.sleep(20L)
        assertEquals(settledRequests, h.requests.size)
        assertFalse(h.offer(HaPresenceAggregate()))
        h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun `confirmed missing Area wakes owned screen and requests fail off once`() {
        val failOffs = AtomicLong()
        Harness(onNoArea = { failOffs.incrementAndGet() }).use { h ->
            val request = h.prepareAutomaticSleep()
            assertTrue(h.screen.isIntendedOff())

            h.offer(aggregate(
                request,
                revision = 1L,
                state = HaPresenceValue.UNAVAILABLE,
                phase = HaPresencePhase.NO_AREA,
                hydrated = false,
                sources = emptySet(),
            ))
            h.await { !h.screen.isIntendedOff() && failOffs.get() == 1L }

            h.offer(aggregate(
                request,
                revision = 2L,
                state = HaPresenceValue.UNAVAILABLE,
                manager = 2L,
                phase = HaPresencePhase.NO_AREA,
                hydrated = false,
                sources = emptySet(),
            ))
            Thread.sleep(20L)
            assertEquals(1L, failOffs.get())
        }
    }

    private class Harness(
        scopeOverride: CoroutineScope? = null,
        workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
        enabled: Boolean = true,
        wakeTapAvailable: Boolean = true,
        history: suspend (Long, Long) -> HaPresenceSelectedHistory = { _, _ ->
            error("history unavailable")
        },
        onNoArea: (Long) -> Unit = {},
    ) : AutoCloseable {
        val now = AtomicLong()
        val wallNow = AtomicLong()
        val scope = scopeOverride ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val learning = FakeLearning()
        val wakeTap = FakeWakeTap(canArm = wakeTapAvailable)
        val screen = ScreenController(
            FakeBacklight(), FakeScreenPower(), FakeRootShell(),
            FakeDaemon(mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK")),
            wakeTap, ScreenOff.DAEMON_BLPOWER,
        )
        val requests = CopyOnWriteArrayList<HaPresenceRequest>()
        val managerClosed = AtomicBoolean()
        val managerRefreshes = AtomicLong()
        private val enabledState = AtomicBoolean(enabled)
        private lateinit var aggregateOffer: (HaPresenceAggregate) -> Boolean
        val controller = AutoSleepController(
            scope = scope,
            screen = screen,
            configuration = { AutoSleepRuntimeConfig(enabledState.get(), "android", "panel", "https://ha") },
            learning = learning,
            onNoArea = onNoArea,
            elapsedRealtime = now::get,
            epochMillis = wallNow::get,
            workerDispatcher = workerDispatcher,
            sourceManagerFactory = { offer ->
                aggregateOffer = offer
                AutoSleepManagerHandle(
                    configure = requests::add,
                    close = { managerClosed.set(true) },
                    refresh = { managerRefreshes.incrementAndGet() },
                    history = history,
                )
            },
        )

        init {
            screen.onWakeCompleted = controller::noteScreenWoken
            screen.onWakeByTap = { it?.let(controller::noteTapWake) }
        }

        fun start(): HaPresenceRequest {
            assertTrue(controller.start())
            return awaitRequest(1)
        }

        fun prepareAutomaticSleep(): HaPresenceRequest {
            val request = start()
            offer(aggregate(request, 0L, HaPresenceValue.OFF))
            await { status().getString("phase") == "live" }
            now.set(15 * MINUTE)
            controller.advanceToForTest(now.get())
            await { screen.isIntendedOff() }
            return request
        }

        fun sleepAutomatically(): AutomaticOffEpoch {
            prepareAutomaticSleep()
            return AutomaticOffEpoch(checkNotNull(screen.currentOffGeneration()))
        }

        fun offer(value: HaPresenceAggregate): Boolean = aggregateOffer(value)
        fun status() = JSONObject(controller.statusJson())
        fun setEnabled(enabled: Boolean) {
            enabledState.set(enabled)
            controller.refresh()
        }

        fun awaitRequest(count: Int): HaPresenceRequest {
            await { requests.size >= count }
            return requests[count - 1]
        }

        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 2_000_000_000L
            while (!condition()) {
                if (System.nanoTime() >= deadline) error("condition did not settle; status=${controller.statusJson()}")
                Thread.sleep(2L)
            }
        }

        fun closeWithVirtualTime(runOwner: () -> Unit) {
            val closeResult = AtomicBoolean()
            val closer = Thread { closeResult.set(controller.closeAndJoin(2_000L)) }.apply { start() }
            val deadline = System.nanoTime() + 2_000_000_000L
            while (closer.isAlive && System.nanoTime() < deadline) {
                runOwner()
                Thread.yield()
            }
            closer.join(2_000L)
            assertTrue(closeResult.get())
        }

        override fun close() {
            controller.closeAndJoin(2_000L)
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    private class FakeLearning : AutoSleepLearning {
        val corrections = CopyOnWriteArrayList<Long>()
        val events = CopyOnWriteArrayList<String>()
        val flushed = AtomicBoolean()
        override fun learnedLease(partition: String, baseLeaseMs: Long) =
            AutoSleepLearnedLease(baseLeaseMs.coerceAtLeast(MIN_AUTO_SLEEP_LEASE_MS), 0, 0, MIN_AUTO_SLEEP_LEASE_MS)
        override fun recordGap(partition: String, evidence: AutoSleepLocalEvidence, gapMs: Long) { events += "gap" }
        override fun recordCorrection(partition: String, floorMs: Long) {
            corrections += floorMs
            events += "correction"
        }
        override fun flush() { events += "flush"; flushed.set(true) }
    }

    companion object {
        private const val SOURCE = "binary_sensor.presence"
        private const val MINUTE = 60_000L
        private const val DAY = 24L * 60L * MINUTE

        private fun aggregate(
            request: HaPresenceRequest,
            revision: Long,
            state: HaPresenceValue,
            manager: Long = 1L,
            feed: Long = 1L,
            marker: HaPresenceActivityMarker? = null,
            phase: HaPresencePhase = HaPresencePhase.LIVE,
            hydrated: Boolean = true,
            detail: String = "",
            sources: Set<String> = setOf(SOURCE),
        ) = HaPresenceAggregate(
            controllerEpoch = request.controllerEpoch,
            managerGeneration = manager,
            feedGeneration = feed,
            feedRevision = revision,
            phase = phase,
            detail = detail,
            finalStates = sources.associateWith { state },
            hydrated = hydrated,
            selectedEntityIds = sources,
            learnedLeaseMs = MIN_AUTO_SLEEP_LEASE_MS,
            activityMarker = marker,
        )
    }
}
