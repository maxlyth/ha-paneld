package io.github.maxlyth.hapaneld.panelassistant

import io.github.maxlyth.hapaneld.HaAuthOwner
import io.github.maxlyth.hapaneld.sensors.HaApiSession
import io.github.maxlyth.hapaneld.sensors.HaApiSessionProvider
import io.github.maxlyth.hapaneld.sensors.HaAuthenticationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PanelAssistantTransportOwnerTest {

    @Test fun `an accepted hello carries the panel identity and learns the integration version`() = runTest {
        val connection = FakeConnection(Ha.accepting())
        val harness = harness(connection)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()

        val status = harness.owner.status
        assertEquals(PanelAssistantTransportPhase.CONNECTED, status.phase)
        assertEquals("0.3.0", status.session?.integrationVersion)
        assertEquals("mqtt", status.session?.authority)
        assertEquals(listOf("https://ha.example" to "token"), harness.connector.connects)

        val hello = JSONObject(connection.sent.single())
        assertEquals("panel_assistant/hello", hello.getString("type"))
        assertEquals(1, hello.getInt("id"))
        assertEquals(IDENTITY.did, hello.getString("did"))
        assertEquals("0.9.8-rc1", hello.getJSONObject("app").getString("version"))
        assertEquals(790, hello.getJSONObject("app").getInt("version_code"))
        assertEquals(0, hello.getJSONArray("capabilities").length())
        assertEquals(0, hello.getJSONArray("channels").length())
        harness.owner.close()
    }

    @Test fun `a closed session reconnects on the first backoff step because acceptance reset the counter`() = runTest {
        val first = FakeConnection(Ha.accepting())
        val harness = harness(IOException("down"), IOException("down"), IOException("down"), first, FakeConnection(Ha.accepting()))
        harness.owner.replaceDemand(DEMAND)
        advanceTimeBy(1_000L + 2_000L + 4_000L + 1L)
        runCurrent()
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)

        val closedAt = testScheduler.currentTime
        first.inbound.trySend(Ha.sessionClosed("entry_unloaded"))
        runCurrent()
        assertEquals(PanelAssistantTransportPhase.WAITING, harness.owner.status.phase)
        assertEquals(1, harness.owner.status.attempt)
        assertTrue(first.closed)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(closedAt + 1_000L, harness.connector.times.last())
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        harness.owner.close()
    }

    @Test fun `transport failures back off exponentially to the cap and never park`() = runTest {
        val harness = harness()
        harness.owner.replaceDemand(DEMAND)
        advanceTimeBy(40L * 60_000L)
        runCurrent()

        val gaps = harness.connector.times.zipWithNext { a, b -> b - a }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), gaps.take(8))
        assertTrue("still retrying after ${gaps.size} gaps", gaps.size > 40)
        assertTrue(gaps.all { it <= 60_000L })
        assertFalse(harness.owner.status.slowRetry)
        harness.owner.close()
    }

    @Test fun `unknown_command stays on backoff inside the warm-up window and then slows`() = runTest {
        val harness = harness(repeating = { FakeConnection(Ha.refusing("unknown_command")) })
        harness.owner.replaceDemand(DEMAND)
        advanceTimeBy(9L * 60_000L)
        runCurrent()
        assertFalse(harness.owner.status.slowRetry)

        advanceTimeBy(30L * 60_000L)
        runCurrent()
        val status = harness.owner.status
        assertTrue(status.slowRetry)
        assertEquals("unknown_command", status.refusal)
        val gaps = harness.connector.times.zipWithNext { a, b -> b - a }
        assertEquals(15L * 60_000L, gaps.last())
        harness.owner.close()
    }

    @Test fun `losing a session reopens the warm-up window so an entry reload does not slow the panel`() = runTest {
        val accepted = FakeConnection(Ha.accepting())
        val harness = harness(accepted, repeating = { FakeConnection(Ha.refusing("unknown_panel")) })
        harness.owner.replaceDemand(DEMAND)
        // Well outside the window opened when demand started.
        advanceTimeBy(20L * 60_000L)
        runCurrent()
        accepted.inbound.trySend(Ha.sessionClosed("entry_unloaded"))
        runCurrent()

        advanceTimeBy(5L * 60_000L)
        runCurrent()
        assertEquals("unknown_panel", harness.owner.status.refusal)
        assertFalse(harness.owner.status.slowRetry)
        harness.owner.close()
    }

    @Test fun `a Core restart that just drops the socket also reopens the warm-up window`() = runTest {
        val accepted = FakeConnection(Ha.accepting())
        val harness = harness(accepted, repeating = { FakeConnection(Ha.refusing("unknown_command")) })
        harness.owner.replaceDemand(DEMAND)
        advanceTimeBy(20L * 60_000L)
        runCurrent()
        // No session_closed event: Core closes the socket and the next receive fails.
        accepted.inbound.close()
        runCurrent()

        advanceTimeBy(5L * 60_000L)
        runCurrent()
        assertEquals("unknown_command", harness.owner.status.refusal)
        assertFalse(harness.owner.status.slowRetry)
        harness.owner.close()
    }

    @Test fun `a superseded session waits on the slow schedule instead of taking the session back`() = runTest {
        val first = FakeConnection(Ha.accepting())
        val harness = harness(first, FakeConnection(Ha.accepting()))
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        first.inbound.trySend(Ha.sessionClosed("superseded"))
        runCurrent()

        assertTrue(harness.owner.status.slowRetry)
        assertEquals(PanelAssistantTransportOwner.REFUSAL_SESSION_SUPERSEDED, harness.owner.status.refusal)
        advanceTimeBy(10L * 60_000L)
        runCurrent()
        assertEquals(1, harness.connector.times.size)
        harness.owner.close()
    }

    @Test fun `a CancellationException from a closing socket is a lost socket, not the end of the owner`() = runTest {
        val closing = FakeConnection { _, _ -> throw java.util.concurrent.CancellationException("socket closed") }
        val harness = harness(closing, FakeConnection(Ha.accepting()))
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        assertEquals(PanelAssistantTransportPhase.WAITING, harness.owner.status.phase)
        assertEquals(PanelAssistantTransportOwner.REFUSAL_TRANSPORT, harness.owner.status.refusal)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(2, harness.connector.times.size)
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        harness.owner.close()
    }

    @Test fun `a rejected credential waits slowly and a credential that moved retries on backoff`() = runTest {
        val rejected = harness()
        rejected.session = { HaApiSession("https://ha.example", null, rejected = true) }
        rejected.owner.replaceDemand(DEMAND)
        runCurrent()
        assertTrue(rejected.owner.status.slowRetry)
        assertEquals(PanelAssistantTransportOwner.REFUSAL_CREDENTIAL_REJECTED, rejected.owner.status.refusal)
        assertEquals(0, rejected.connector.times.size)
        rejected.owner.close()

        val moved = harness()
        moved.session = { HaApiSession("https://ha.example", "token", owner = OWNER.copy(refreshToken = "newer")) }
        moved.owner.replaceDemand(DEMAND)
        runCurrent()
        assertFalse(moved.owner.status.slowRetry)
        assertEquals(PanelAssistantTransportOwner.REFUSAL_CREDENTIAL_UNAVAILABLE, moved.owner.status.refusal)
        assertEquals(0, moved.connector.times.size)
        moved.owner.close()
    }

    @Test fun `a refusal retrying cannot change waits on the slow schedule until the network returns`() = runTest {
        val harness = harness(repeating = { FakeConnection(Ha.refusing("panel_user_mismatch")) })
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        assertTrue(harness.owner.status.slowRetry)
        assertEquals("panel_user_mismatch", harness.owner.status.refusal)

        advanceTimeBy(10L * 60_000L)
        runCurrent()
        assertEquals(1, harness.connector.times.size)

        harness.owner.nudge()
        runCurrent()
        assertEquals(2, harness.connector.times.size)
        harness.owner.close()
    }

    @Test fun `a rejected token gets one forced refresh and then the slow schedule`() = runTest {
        val harness = harness(repeatingFailure = { HaAuthenticationException("rejected") })
        harness.owner.replaceDemand(DEMAND)
        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals(listOf(false, true), harness.forces)
        assertTrue(harness.owner.status.slowRetry)
        assertEquals(PanelAssistantTransportOwner.REFUSAL_AUTH_INVALID, harness.owner.status.refusal)
        advanceTimeBy(15L * 60_000L)
        runCurrent()
        assertEquals(listOf(false, true, false), harness.forces)
        harness.owner.close()
    }

    @Test fun `a ping with no inbound frame ends the session and reconnects`() = runTest {
        val silent = FakeConnection(Ha.accepting(answerPings = false))
        val harness = harness(silent, FakeConnection(Ha.accepting()))
        harness.owner.replaceDemand(DEMAND)
        advanceTimeBy(30_000L + 14_999L)
        runCurrent()
        assertFalse(silent.closed)
        assertEquals("ping", JSONObject(silent.sent.last()).getString("type"))

        advanceTimeBy(1L)
        runCurrent()
        assertTrue(silent.closed)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(2, harness.connector.times.size)
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        harness.owner.close()
    }

    @Test fun `answered pings keep one session open with strictly increasing message ids`() = runTest {
        val connection = FakeConnection(Ha.accepting())
        val harness = harness(connection)
        harness.owner.replaceDemand(DEMAND)
        advanceTimeBy(10L * 60_000L)
        runCurrent()

        assertEquals(1, harness.connector.times.size)
        assertFalse(connection.closed)
        val ids = connection.sent.map { JSONObject(it).getLong("id") }
        assertEquals((1L..ids.size.toLong()).toList(), ids)
        assertTrue(ids.size >= 20)
        harness.owner.close()
    }

    @Test fun `an equal demand leaves a live session alone and a new credential replaces it`() = runTest {
        val first = FakeConnection(Ha.accepting())
        val harness = harness(first, FakeConnection(Ha.accepting()))
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        harness.owner.replaceDemand(DEMAND.copy())
        runCurrent()
        assertEquals(1, harness.connector.times.size)
        assertFalse(first.closed)

        harness.credential = OWNER.copy(refreshToken = "other-refresh")
        harness.owner.replaceDemand(DEMAND.copy(credential = harness.credential))
        runCurrent()
        assertTrue(first.closed)
        assertEquals(2, harness.connector.times.size)
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        harness.owner.close()
    }

    @Test fun `removing demand closes the socket and stops`() = runTest {
        val connection = FakeConnection(Ha.accepting())
        val harness = harness(connection)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        harness.owner.replaceDemand(null)
        runCurrent()
        assertTrue(connection.closed)
        assertEquals(PanelAssistantTransportPhase.STOPPED, harness.owner.status.phase)
        advanceTimeBy(60L * 60_000L)
        runCurrent()
        assertEquals(1, harness.connector.times.size)
        harness.owner.close()
    }

    @Test fun `demand needs an address, a credential and a panel identity`() {
        assertEquals(DEMAND, panelAssistantTransportDemand(OWNER, accessTokenPresent = false, IDENTITY))
        assertNull(panelAssistantTransportDemand(OWNER.copy(url = ""), accessTokenPresent = true, IDENTITY))
        assertNull(panelAssistantTransportDemand(OWNER.copy(refreshToken = ""), accessTokenPresent = false, IDENTITY))
        assertEquals(
            OWNER.copy(refreshToken = ""),
            panelAssistantTransportDemand(OWNER.copy(refreshToken = ""), accessTokenPresent = true, IDENTITY)?.credential,
        )
        assertNull(panelAssistantTransportDemand(OWNER, accessTokenPresent = true, IDENTITY.copy(did = null)))
    }

    @Test fun aShadowSessionReportsAFullSyncThenDeltasWithIdsIncreasingAcrossPings() = runTest {
        val shadow = Shadow(listOf("relay1", "screen"))
        shadow.sink("relay1", "ON")
        val connection = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state")))
        val harness = harness(connection, shadow = shadow.reporter)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()

        val hello = JSONObject(connection.sent.first())
        assertEquals(listOf("state"), hello.getJSONArray("capabilities").let { (0 until it.length()).map(it::getString) })
        assertEquals(listOf("relay1", "screen"), hello.getJSONArray("channels").let { (0 until it.length()).map { i -> it.getJSONObject(i).getString("channel") } })
        assertEquals(listOf("panel_assistant/hello", "full_begin", "full_end"), connection.sent.map(::kind))
        assertEquals("opaque-session", JSONObject(connection.sent[1]).getString("session"))

        advanceTimeBy(45_000L)
        runCurrent()
        shadow.sink("screen", """{"state":"OFF"}""")
        runCurrent()
        advanceTimeBy(40_000L)
        runCurrent()
        shadow.sink("relay1", "OFF")
        runCurrent()

        assertEquals(listOf("panel_assistant/hello", "full_begin", "full_end", "ping", "delta", "ping", "delta"), connection.sent.map(::kind))
        val ids = connection.sent.map { JSONObject(it).getLong("id") }
        assertEquals((1L..ids.size.toLong()).toList(), ids)
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        harness.owner.close()
    }

    @Test fun anMqttAuthorityOrAnUngrantedStateCapabilityReportsNothing() = runTest {
        for ((authority, capabilities) in listOf("mqtt" to listOf("state"), "shadow" to emptyList())) {
            val shadow = Shadow(listOf("relay1"))
            shadow.sink("relay1", "ON")
            val connection = FakeConnection(Ha.accepting(authority = authority, capabilities = capabilities))
            val harness = harness(connection, shadow = shadow.reporter)
            harness.owner.replaceDemand(DEMAND)
            runCurrent()
            shadow.sink("relay1", "OFF")
            advanceTimeBy(31_000L)
            runCurrent()
            assertEquals("$authority $capabilities", listOf("panel_assistant/hello", "ping"), connection.sent.map(::kind))
            harness.owner.close()
        }
    }

    @Test fun aNewChannelDuringAShadowSessionEndsItAndHelloesAgainPromptly() = runTest {
        val shadow = Shadow(listOf("relay1"))
        val first = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state")))
        val second = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state")))
        val harness = harness(first, second, shadow = shadow.reporter)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        shadow.keys += "relay2"
        shadow.sink("relay2", "ON")
        runCurrent()
        assertTrue(first.closed)
        assertEquals(listOf("panel_assistant/hello", "full_begin", "full_end"), first.sent.map(::kind))

        advanceTimeBy(1_000L)
        runCurrent()
        val hello = JSONObject(second.sent.first())
        assertEquals(2, hello.getJSONArray("channels").length())
        assertEquals(listOf("relay2"), JSONObject(second.sent[1]).getJSONArray("observations").let { (0 until it.length()).map { i -> it.getJSONObject(i).getString("channel") } })
        harness.owner.close()
    }

    @Test fun sessionUnknownOnAReportEndsTheSession() = runTest {
        val shadow = Shadow(listOf("relay1"))
        shadow.sink("relay1", "ON")
        val connection = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state"), reportError = "session_unknown"))
        val harness = harness(connection, FakeConnection(Ha.accepting()), shadow = shadow.reporter)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        assertTrue(connection.closed)
        assertEquals(PanelAssistantTransportOwner.REFUSAL_SESSION_CLOSED, harness.owner.status.refusal)
        harness.owner.close()
    }

    @Test fun aReplacedSessionFinishingLateCannotStopTheNewSessionsReporting() = runTest {
        val shadow = Shadow(listOf("relay1"))
        shadow.sink("relay1", "ON")
        val release = CompletableDeferred<Unit>()
        val first = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state"))).apply { holdReadAfterCancel = release }
        val second = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state")))
        val harness = harness(first, second, shadow = shadow.reporter)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        assertEquals(listOf("panel_assistant/hello", "full_begin", "full_end"), first.sent.map(::kind))

        // The retired session's socket read outlives its cancellation, so its teardown ends only when released.
        harness.owner.replaceDemand(DEMAND.copy(identity = IDENTITY.copy(appVersionCode = 791)))
        runCurrent()
        assertFalse(first.closed)
        release.complete(Unit)
        runCurrent()
        shadow.sink("relay1", "OFF")
        runCurrent()

        assertTrue(first.closed)
        assertEquals(listOf("panel_assistant/hello", "full_begin", "full_end", "delta"), second.sent.map(::kind))
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        harness.owner.close()
    }

    @Test fun aNativeSessionReportsStateAndAnswersEachCommandOnTheSameSocket() = runTest {
        val shadow = Shadow(listOf("relay1"))
        shadow.sink("relay1", "OFF")
        val sink = ImmediateSink()
        val authorities = mutableListOf<String>()
        val connection = FakeConnection(Ha.accepting(authority = "native", capabilities = listOf("state", "commands", "approval")))
        val harness = harness(connection, shadow = shadow.reporter, commands = sink, onAuthority = { authorities += it })
        harness.owner.replaceDemand(DEMAND)
        runCurrent()

        val hello = JSONObject(connection.sent.first())
        assertEquals(listOf("state", "commands", "approval"), hello.getJSONArray("capabilities").let { (0 until it.length()).map(it::getString) })
        assertEquals(listOf("native"), authorities)
        assertEquals(listOf("panel_assistant/hello", "full_begin", "full_end"), connection.sent.map(::kind))

        connection.inbound.trySend(Ha.command("c1", "relay1", true))
        runCurrent()

        assertEquals(listOf("relay1" to "ON"), sink.ran)
        val answer = JSONObject(connection.sent.last())
        assertEquals(listOf("panel_assistant/command_result", "opaque-session", "c1", "applied"), listOf("type", "session", "command_id", "outcome").map(answer::getString))
        val ids = connection.sent.map { JSONObject(it).getLong("id") }
        assertEquals((1L..ids.size.toLong()).toList(), ids)
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        harness.owner.close()
    }

    @Test fun aShadowSessionRefusesCommandsWithoutRunningThem() = runTest {
        val shadow = Shadow(listOf("relay1"))
        val sink = ImmediateSink()
        val authorities = mutableListOf<String>()
        val connection = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state")))
        val harness = harness(connection, shadow = shadow.reporter, commands = sink, onAuthority = { authorities += it })
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        connection.inbound.trySend(Ha.command("c1", "relay1", true))
        runCurrent()

        assertEquals(listOf("shadow"), authorities)
        assertTrue(sink.ran.isEmpty())
        val answer = JSONObject(connection.sent.last())
        assertEquals(listOf("refused", "authority_mismatch"), listOf("outcome", "code").map(answer::getString))
        harness.owner.close()
    }

    @Test fun anUnknownAuthorityIsNeverPersisted() = runTest {
        val authorities = mutableListOf<String>()
        val connection = FakeConnection(Ha.accepting(authority = "future_mode"))
        val harness = harness(connection, commands = ImmediateSink(), onAuthority = { authorities += it })
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        assertTrue(authorities.isEmpty())
        harness.owner.close()
    }

    @Test fun sessionUnknownAnsweringACommandResultEndsTheSession() = runTest {
        val connection = FakeConnection(Ha.accepting(authority = "native", capabilities = listOf("state", "commands"), commandResultError = "session_unknown"))
        val harness = harness(connection, shadow = Shadow(listOf("relay1")).reporter, commands = ImmediateSink())
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        connection.inbound.trySend(Ha.command("c1", "relay1", true))
        runCurrent()

        assertTrue(connection.closed)
        assertEquals("session_closed", harness.owner.status.refusal)
        harness.owner.close()
    }

    @Test fun sessionUnknownAnsweringACommandResultEndsASessionWithoutStateReporting() = runTest {
        val connection = FakeConnection(Ha.accepting(authority = "native", capabilities = listOf("commands"), commandResultError = "session_unknown"))
        val sink = ImmediateSink()
        val harness = harness(connection, shadow = Shadow(listOf("relay1")).reporter, commands = sink)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        connection.inbound.trySend(Ha.command("c1", "relay1", true))
        runCurrent()

        assertEquals(1, sink.ran.size)
        assertTrue(connection.sent.none { JSONObject(it).getString("type") == "panel_assistant/report_state" })
        assertTrue(connection.closed)
        assertEquals("session_closed", harness.owner.status.refusal)
        harness.owner.close()
    }

    @Test fun aHeldApprovalIsWithdrawnWhenTheSessionEnds() = runTest {
        val sink = ImmediateSink(result = PanelAssistantCommandResult.ApprovalPending("approval-1"))
        val connection = FakeConnection(Ha.accepting(authority = "native", capabilities = listOf("state", "commands", "approval")))
        val harness = harness(connection, shadow = Shadow(listOf("camera_enabled")).reporter, commands = sink)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        connection.inbound.trySend(Ha.command("c1", "camera_enabled", true))
        runCurrent()
        assertEquals("pending_approval", JSONObject(connection.sent.last()).getString("outcome"))

        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(1, sink.ran.size)
        connection.inbound.trySend(Ha.sessionClosed("authority_changed"))
        runCurrent()

        assertEquals(listOf("approval-1"), sink.withdrawn)
        harness.owner.close()
    }

    // ---- harness ---------------------------------------------------------------------------------

    /** Runs every command at once with [result]; approvals stay pending. */
    private class ImmediateSink(private val result: PanelAssistantCommandResult = PanelAssistantCommandResult.Applied) :
        PanelAssistantCommandSink {
        val ran = mutableListOf<Pair<String, String>>()
        val withdrawn = mutableListOf<String>()

        override fun submit(command: PanelAssistantCommand, done: (PanelAssistantCommandResult) -> Unit) {
            ran += command.channel to command.payload
            done(command.admit() ?: result)
        }

        override fun approvalState(approvalId: String) = PanelAssistantApprovalState.PENDING

        override fun withdrawApproval(approvalId: String) {
            withdrawn += approvalId
        }
    }

    private class Shadow(initial: List<String>) {
        val keys = initial.toMutableList()
        val reporter = PanelAssistantShadowReporter(log = {})
        private val bound = reporter.bind { keys.toList() }

        fun sink(channel: String, payload: String) = bound(channel, io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation.Known(payload)) {}
    }

    private class Harness(val owner: PanelAssistantTransportOwner, val connector: FakeConnector, val forces: List<Boolean>) {
        var credential: HaAuthOwner = OWNER
        var session: (() -> HaApiSession)? = null
    }

    private fun TestScope.harness(
        vararg script: Any,
        repeating: (() -> FakeConnection)? = null,
        repeatingFailure: (() -> Exception)? = null,
        shadow: PanelAssistantShadowReporter? = null,
        commands: PanelAssistantCommandSink? = null,
        onAuthority: (String) -> Unit = {},
    ): Harness {
        val connector = FakeConnector(this, script.toMutableList(), repeating, repeatingFailure)
        val forces = mutableListOf<Boolean>()
        lateinit var harness: Harness
        val owner = PanelAssistantTransportOwner(
            scope = backgroundScope,
            auth = HaApiSessionProvider { force ->
                forces += force
                harness.session?.invoke() ?: HaApiSession("https://ha.example", "token", owner = harness.credential)
            },
            connector = connector,
            workerDispatcher = StandardTestDispatcher(testScheduler),
            monotonicMillis = { testScheduler.currentTime },
            jitter = { bound -> bound },
            log = {},
            shadow = shadow,
            commands = commands,
            onAuthority = onAuthority,
        )
        harness = Harness(owner, connector, forces)
        return harness
    }

    private class FakeConnector(
        private val scope: TestScope,
        private val script: MutableList<Any>,
        private val repeating: (() -> FakeConnection)?,
        private val repeatingFailure: (() -> Exception)?,
    ) : PanelAssistantTransportConnector {
        val connects = mutableListOf<Pair<String, String>>()
        val times = mutableListOf<Long>()

        override suspend fun connect(baseUrl: String, accessToken: String): PanelAssistantTransportConnection {
            connects += baseUrl to accessToken
            times += scope.testScheduler.currentTime
            val next: Any = script.removeFirstOrNull()
                ?: repeating?.invoke()
                ?: repeatingFailure?.invoke()
                ?: IOException("unreachable")
            if (next is Exception) throw next
            return next as FakeConnection
        }
    }

    /** Home Assistant's side of one socket: answers each frame the panel sends. */
    private class FakeConnection(private val respond: (JSONObject, FakeConnection) -> Unit) :
        PanelAssistantTransportConnection {
        val inbound = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        var closed = false

        /** When set, a read cancelled mid-wait returns only once this completes, as a slow socket might. */
        var holdReadAfterCancel: CompletableDeferred<Unit>? = null

        override suspend fun send(text: String) {
            check(!closed) { "send on a closed connection" }
            sent += text
            respond(JSONObject(text), this)
        }

        override suspend fun receive(timeoutMs: Long): String? = try {
            select {
                inbound.onReceive { it }
                onTimeout(timeoutMs) { null }
            }
        } catch (cancelled: CancellationException) {
            holdReadAfterCancel?.let { withContext(NonCancellable) { it.await() } }
            throw cancelled
        }

        override suspend fun close() {
            closed = true
            inbound.close()
        }
    }

    private object Ha {
        fun accepting(
            answerPings: Boolean = true,
            authority: String = "mqtt",
            capabilities: List<String> = emptyList(),
            reportError: String? = null,
            commandResultError: String? = null,
        ): (JSONObject, FakeConnection) -> Unit = { frame, connection ->
            when (frame.getString("type")) {
                "panel_assistant/hello" -> connection.inbound.trySend(
                    JSONObject()
                        .put("id", frame.getLong("id"))
                        .put("type", "result")
                        .put("success", true)
                        .put(
                            "result",
                            JSONObject()
                                .put("protocol", 1)
                                .put("session", "opaque-session")
                                .put("authority", authority)
                                .put("capabilities", JSONArray(capabilities))
                                .put("integration", JSONObject().put("version", "0.3.0"))
                                .put("channels", JSONObject().put("accepted", 0).put("unknown", JSONArray())),
                        )
                        .toString(),
                )
                "panel_assistant/report_state" -> connection.inbound.trySend(
                    if (reportError != null) {
                        JSONObject().put("id", frame.getLong("id")).put("type", "result").put("success", false)
                            .put("error", JSONObject().put("code", reportError).put("message", "x")).toString()
                    } else {
                        JSONObject().put("id", frame.getLong("id")).put("type", "result").put("success", true)
                            .put("result", JSONObject().put("rejected", JSONArray())).toString()
                    },
                )
                "panel_assistant/command_result" -> connection.inbound.trySend(
                    if (commandResultError != null) {
                        JSONObject().put("id", frame.getLong("id")).put("type", "result").put("success", false)
                            .put("error", JSONObject().put("code", commandResultError).put("message", "x")).toString()
                    } else {
                        JSONObject().put("id", frame.getLong("id")).put("type", "result").put("success", true)
                            .put("result", JSONObject()).toString()
                    },
                )
                "ping" -> if (answerPings) {
                    connection.inbound.trySend(JSONObject().put("id", frame.getLong("id")).put("type", "pong").toString())
                }
            }
        }

        fun refusing(code: String): (JSONObject, FakeConnection) -> Unit = { frame, connection ->
            connection.inbound.trySend(
                JSONObject()
                    .put("id", frame.getLong("id"))
                    .put("type", "result")
                    .put("success", false)
                    .put("error", JSONObject().put("code", code).put("message", "refused"))
                    .toString(),
            )
        }

        fun command(commandId: String, channel: String, value: Any?): String = JSONObject()
            .put("id", 1)
            .put("type", "event")
            .put(
                "event",
                JSONObject().put("kind", "command").put("command_id", commandId).put("session", "opaque-session")
                    .put("channel", channel).put("value", value ?: JSONObject.NULL).put("deadline_ms", 10_000),
            )
            .toString()

        fun sessionClosed(reason: String): String = JSONObject()
            .put("id", 1)
            .put("type", "event")
            .put("event", JSONObject().put("kind", "session_closed").put("reason", reason))
            .toString()
    }

    private companion object {
        /** A sent frame's command type, or its `sync` for a `report_state`. */
        fun kind(text: String): String = JSONObject(text).let { it.optString("sync").ifEmpty { it.getString("type") } }

        val OWNER = HaAuthOwner(
            url = "https://ha.example",
            refreshToken = "refresh",
            clientId = "",
            staticAccessToken = "",
        )
        val IDENTITY = PanelAssistantHelloIdentity(
            did = "0".repeat(64),
            appVersion = "0.9.8-rc1",
            appVersionCode = 790,
        )
        val DEMAND = PanelAssistantTransportDemand(OWNER, IDENTITY)
    }
}
