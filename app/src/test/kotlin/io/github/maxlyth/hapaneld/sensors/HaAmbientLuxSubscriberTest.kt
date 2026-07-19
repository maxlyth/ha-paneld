package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.HaAuthSnapshot
import io.github.maxlyth.hapaneld.stableOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HaAmbientLuxSubscriberTest {
    @Test fun `source validation reads only the exact state without disturbing the live source`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeConnection()
        val transport = FakeTransport(connection)
        val samples = mutableListOf<HaAmbientLuxSample>()
        val subscriber = subscriber(dispatcher, transport, samples, mutableListOf())

        val result = subscriber.validateSource(ENTITY)

        assertTrue(result is HaAmbientSourceValidation.Ready)
        assertEquals(1, transport.stateCount)
        assertEquals(0, transport.statesCount)
        assertEquals(0, transport.subscribeCount)
        assertTrue(samples.isEmpty())
        assertEquals(HaAmbientSourcePhase.DISABLED, subscriber.latestStatus().phase)
        subscriber.close()
    }

    @Test fun `source validation distinguishes missing and nonnumeric state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeTransport(FakeConnection()).apply {
            stateResult = CompletableDeferred<JSONObject?>().also { it.complete(null) }
        }
        val subscriber = subscriber(dispatcher, transport, mutableListOf(), mutableListOf())

        val missing = subscriber.validateSource(ENTITY) as HaAmbientSourceValidation.Rejected
        assertEquals("ha-source-missing", missing.error)

        transport.stateResult = CompletableDeferred<JSONObject?>().also {
            it.complete(state("unavailable", "2026-07-17T10:00:00Z"))
        }
        val unavailable = subscriber.validateSource(ENTITY) as HaAmbientSourceValidation.Rejected
        assertEquals("ha-source-unavailable", unavailable.error)
        subscriber.close()
    }

    @Test fun `source validation retries one authentication rejection with a forced session`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeTransport(FakeConnection()).apply { rejectStates = 1 }
        val forces = mutableListOf<Boolean>()
        val subscriber = HaAmbientLuxSubscriber(
            scope = this,
            auth = HaApiSessionProvider { force ->
                forces += force
                HaApiSession("https://ha.example", "token-${forces.size}", owner = OWNER)
            },
            transport = transport,
            onSample = {},
            onStatus = {},
            onCandidates = {},
            workerDispatcher = dispatcher,
        )

        assertTrue(subscriber.validateSource(ENTITY) is HaAmbientSourceValidation.Ready)
        assertEquals(listOf(false, true), forces)
        assertEquals(2, transport.stateCount)
        subscriber.close()
    }

    @Test fun `source validation rejects numeric sensors that are not illuminance`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeTransport(FakeConnection()).apply {
            stateResult = CompletableDeferred<JSONObject?>().also {
                it.complete(state("21.5", "2026-07-17T10:00:00Z", deviceClass = "temperature", unit = "°C"))
            }
        }
        val subscriber = subscriber(dispatcher, transport, mutableListOf(), mutableListOf())

        val rejected = subscriber.validateSource(ENTITY) as HaAmbientSourceValidation.Rejected
        assertEquals("ha-source-unavailable", rejected.error)
        subscriber.close()
    }

    @Test fun `credential owner ignores access refresh but detects credential replacement`() {
        val refreshedAccess = OWNER_SNAPSHOT.copy(accessToken = "access-2", tokenExpiry = 9999)
        assertEquals(OWNER, refreshedAccess.stableOwner())
        assertTrue(OWNER != OWNER_SNAPSHOT.copy(refreshToken = "refresh-2").stableOwner())
        assertTrue(OWNER != OWNER_SNAPSHOT.copy(clientId = "client-2").stableOwner())
        assertTrue(
            OWNER_SNAPSHOT.copy(refreshToken = "", accessToken = "static-1").stableOwner() !=
                OWNER_SNAPSHOT.copy(refreshToken = "", accessToken = "static-2").stableOwner(),
        )
    }

    @Test fun `candidate discovery is lazy single flight and ttl cached`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeTransport(FakeConnection())
        var now = 1_000_000L
        val subscriber = HaAmbientLuxSubscriber(
            scope = this,
            auth = HaApiSessionProvider { HaApiSession("https://ha.example", "token") },
            transport = transport,
            onSample = {},
            onStatus = {},
            onCandidates = {},
            workerDispatcher = dispatcher,
            epochMillis = { now },
        )

        assertEquals(0, transport.statesCount)
        repeat(20) { subscriber.refreshCandidates() }
        runCurrent()
        assertEquals(1, transport.statesCount)
        subscriber.refreshCandidates()
        runCurrent()
        assertEquals(1, transport.statesCount)
        now += 600_001L
        subscriber.refreshCandidates()
        runCurrent()
        assertEquals(2, transport.statesCount)
        subscriber.close()
    }

    @Test fun `socket events are buffered until REST initial state and then ordered by timestamp`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeConnection()
        val transport = FakeTransport(connection).apply { stateResult = CompletableDeferred() }
        val samples = mutableListOf<HaAmbientLuxSample>()
        val statuses = mutableListOf<HaAmbientSourceStatus>()
        val subscriber = subscriber(dispatcher, transport, samples, statuses)

        subscriber.setSource(ENTITY)
        repeat(2) { runCurrent() }
        connection.messages.send(HaSocketMessage.State(state("50", "2026-07-17T10:00:02Z")))
        runCurrent()
        transport.stateResult.complete(state("10", "2026-07-17T10:00:00Z"))
        runCurrent()

        assertEquals(listOf(10.0, 50.0), samples.map(HaAmbientLuxSample::lux))
        assertEquals(HaAmbientSourcePhase.LIVE, subscriber.latestStatus().phase)
        assertEquals(ENTITY, subscriber.latestStatus().entityId)
        assertTrue(statuses.any { it.phase == HaAmbientSourcePhase.SYNCHRONIZING })
        subscriber.close()
    }

    @Test fun `authentication rejection forces one refreshed DashboardAuth session before reconnect`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeConnection()
        val transport = FakeTransport(connection).apply { rejectSubscriptions = 1 }
        val forces = mutableListOf<Boolean>()
        val auth = HaApiSessionProvider { force ->
            forces += force
            HaApiSession("https://ha.example", "token-${forces.size}")
        }
        val subscriber = HaAmbientLuxSubscriber(
            scope = this,
            auth = auth,
            transport = transport,
            onSample = {},
            onStatus = {},
            onCandidates = {},
            workerDispatcher = dispatcher,
            reconnectBaseMs = 10,
            reconnectMaxMs = 10,
        )

        subscriber.setSource(ENTITY)
        runCurrent()
        assertEquals(listOf(false), forces)
        assertEquals(HaAmbientSourcePhase.AUTH_FAILED, subscriber.latestStatus().phase)
        advanceTimeBy(10)
        runCurrent()

        assertEquals(listOf(false, true), forces)
        assertEquals(2, transport.subscribeCount)
        assertEquals(HaAmbientSourcePhase.LIVE, subscriber.latestStatus().phase)
        subscriber.close()
    }

    @Test fun `missing REST state stays subscribed and becomes live on first trigger`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeConnection()
        val transport = FakeTransport(connection).apply {
            stateResult = CompletableDeferred<JSONObject?>().also { it.complete(null) }
        }
        val samples = mutableListOf<HaAmbientLuxSample>()
        val subscriber = subscriber(dispatcher, transport, samples, mutableListOf())

        subscriber.setSource(ENTITY)
        repeat(2) { runCurrent() }
        assertEquals(HaAmbientSourcePhase.SOURCE_MISSING, subscriber.latestStatus().phase)
        connection.messages.send(HaSocketMessage.State(state("7", "2026-07-17T10:00:00Z")))
        runCurrent()

        assertEquals(listOf(7.0), samples.map(HaAmbientLuxSample::lux))
        assertEquals(HaAmbientSourcePhase.LIVE, subscriber.latestStatus().phase)
        subscriber.close()
    }

    @Test fun `silent socket fails liveness and reconnects`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = FakeConnection(autoPong = false)
        val second = FakeConnection(autoPong = true)
        val transport = FakeTransport(first, second)
        val subscriber = HaAmbientLuxSubscriber(
            scope = this,
            auth = HaApiSessionProvider { HaApiSession("https://ha.example", "token") },
            transport = transport,
            onSample = {},
            onStatus = {},
            onCandidates = {},
            workerDispatcher = dispatcher,
            livenessIntervalMs = 100,
            pongTimeoutMs = 50,
            reconnectBaseMs = 10,
            reconnectMaxMs = 10,
        )

        subscriber.setSource(ENTITY)
        runCurrent()
        advanceTimeBy(151)
        runCurrent()
        assertEquals(HaAmbientSourcePhase.RECONNECTING, subscriber.latestStatus().phase)
        advanceTimeBy(10)
        runCurrent()

        assertEquals(2, transport.subscribeCount)
        assertEquals(HaAmbientSourcePhase.LIVE, subscriber.latestStatus().phase)
        subscriber.close()
    }

    private fun kotlinx.coroutines.test.TestScope.subscriber(
        dispatcher: CoroutineDispatcher,
        transport: FakeTransport,
        samples: MutableList<HaAmbientLuxSample>,
        statuses: MutableList<HaAmbientSourceStatus>,
    ) = HaAmbientLuxSubscriber(
        scope = this,
        auth = HaApiSessionProvider { HaApiSession("https://ha.example", "token", owner = OWNER) },
        transport = transport,
        onSample = samples::add,
        onStatus = statuses::add,
        onCandidates = {},
        workerDispatcher = dispatcher,
    )

    private class FakeConnection(private val autoPong: Boolean = true) : HaTriggerConnection {
        val messages = Channel<HaSocketMessage>(Channel.UNLIMITED)
        override suspend fun receive(): HaSocketMessage = messages.receive()
        override suspend fun ping(id: Int) {
            if (autoPong) messages.send(HaSocketMessage.Pong(id))
        }
        override suspend fun close() {
            messages.close()
        }
    }

    private class FakeTransport(vararg connections: FakeConnection) : HaAmbientTransport {
        private val connections = ArrayDeque(connections.toList())
        var rejectSubscriptions = 0
        var subscribeCount = 0
        var statesCount = 0
        var stateCount = 0
        var rejectStates = 0
        var stateResult: CompletableDeferred<JSONObject?> =
            CompletableDeferred(state("1", "2026-07-17T09:00:00Z"))

        override suspend fun subscribe(baseUrl: String, accessToken: String, entityId: String): HaTriggerConnection {
            subscribeCount++
            if (rejectSubscriptions-- > 0) throw HaAuthenticationException("rejected")
            return connections.removeFirst()
        }

        override suspend fun state(baseUrl: String, accessToken: String, entityId: String): JSONObject? {
            stateCount++
            if (rejectStates-- > 0) throw HaAuthenticationException("rejected")
            return stateResult.await()
        }
        override suspend fun states(baseUrl: String, accessToken: String): JSONArray = JSONArray().also { statesCount++ }
        override suspend fun config(baseUrl: String, accessToken: String): JSONObject = JSONObject()
    }

    private companion object {
        const val ENTITY = "sensor.room_illuminance"
        val OWNER_SNAPSHOT = HaAuthSnapshot("https://ha.example", "access-1", "refresh-1", 1234, "client-1")
        val OWNER = OWNER_SNAPSHOT.stableOwner()
        fun state(
            value: String,
            updated: String,
            deviceClass: String = "illuminance",
            unit: String = "lx",
        ) = JSONObject()
            .put("entity_id", ENTITY)
            .put("state", value)
            .put("last_updated", updated)
            .put("attributes", JSONObject().put("device_class", deviceClass).put("unit_of_measurement", unit))
    }
}
