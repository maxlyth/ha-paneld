package io.github.maxlyth.hapaneld.sensors

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaAmbientLuxProtocolTest {
    @Test fun `subscription uses official exact state trigger`() {
        val command = HaAmbientLuxProtocol.subscribeTrigger("sensor.room_illuminance", id = 17)

        assertEquals(17, command.getInt("id"))
        assertEquals("subscribe_trigger", command.getString("type"))
        val trigger = command.getJSONObject("trigger")
        assertEquals("state", trigger.getString("platform"))
        assertEquals("sensor.room_illuminance", trigger.getString("entity_id"))
        assertFalse(trigger.has("from"))
        assertFalse(trigger.has("to"))
        assertEquals(2, trigger.length())
    }

    @Test fun `trigger parser projects only to_state`() {
        val expected = state("sensor.room_illuminance", "34.5", "2026-07-17T10:00:02Z")
        val message = JSONObject()
            .put("id", 1)
            .put("type", "event")
            .put("event", JSONObject().put("variables", JSONObject().put("trigger", JSONObject().put("to_state", expected))))

        assertEquals(expected.toString(), HaAmbientLuxProtocol.triggerState(message)?.toString())
        assertNull(HaAmbientLuxProtocol.triggerState(JSONObject().put("type", "event")))
    }

    @Test fun `subscribe first REST hydration cannot overwrite newer stream state`() {
        val samples = mutableListOf<HaAmbientLuxSample>()
        var received = 1_000L
        val gate = HaLuxSampleGate("sensor.room_illuminance", { received++ }, samples::add)
        val rest = state("sensor.room_illuminance", "10", "2026-07-17T10:00:00Z")
        val newerStream = state("sensor.room_illuminance", "50", "2026-07-17T10:00:02Z")
        val staleBuffered = state("sensor.room_illuminance", "20", "2026-07-17T09:59:59Z")

        assertTrue(gate.accept(rest, HaAmbientSampleOrigin.REST_INITIAL))
        assertTrue(gate.accept(newerStream, HaAmbientSampleOrigin.WEBSOCKET))
        assertFalse(gate.accept(staleBuffered, HaAmbientSampleOrigin.WEBSOCKET))
        assertFalse(gate.accept(newerStream, HaAmbientSampleOrigin.WEBSOCKET))

        assertEquals(listOf(10.0, 50.0), samples.map(HaAmbientLuxSample::lux))
        assertEquals(listOf(HaAmbientSampleOrigin.REST_INITIAL, HaAmbientSampleOrigin.WEBSOCKET), samples.map(HaAmbientLuxSample::origin))
    }

    @Test fun `samples reject wrong entity unavailable negative and non-finite values`() {
        val samples = mutableListOf<HaAmbientLuxSample>()
        val gate = HaLuxSampleGate("sensor.room_illuminance", { 0L }, samples::add)

        assertFalse(gate.accept(state("sensor.other", "1", "2026-07-17T10:00:00Z"), HaAmbientSampleOrigin.WEBSOCKET))
        assertFalse(gate.accept(state("sensor.room_illuminance", "unavailable", "2026-07-17T10:00:01Z"), HaAmbientSampleOrigin.WEBSOCKET))
        assertFalse(gate.accept(state("sensor.room_illuminance", "-1", "2026-07-17T10:00:02Z"), HaAmbientSampleOrigin.WEBSOCKET))
        assertFalse(gate.accept(state("sensor.room_illuminance", "NaN", "2026-07-17T10:00:03Z"), HaAmbientSampleOrigin.WEBSOCKET))
        assertTrue(samples.isEmpty())
    }

    @Test fun `candidate discovery keeps bounded illuminance projection only`() {
        val states = JSONArray()
            .put(
                state("sensor.window_lux", "123.4", "2026-07-17T10:00:00Z")
                    .put("attributes", JSONObject().put("device_class", "illuminance").put("unit_of_measurement", "lx").put("friendly_name", "Window lux")),
            )
            .put(
                state("sensor.ceiling_light", "unavailable", "2026-07-17T10:00:00Z")
                    .put("attributes", JSONObject().put("unit_of_measurement", "lux").put("friendly_name", "Ceiling")),
            )
            .put(
                state("sensor.temperature", "21", "2026-07-17T10:00:00Z")
                    .put("attributes", JSONObject().put("device_class", "temperature").put("unit_of_measurement", "C")),
            )
            .put(
                state("light.fake_lux", "10", "2026-07-17T10:00:00Z")
                    .put("attributes", JSONObject().put("device_class", "illuminance")),
            )

        val candidates = HaAmbientLuxProtocol.candidates(states)

        assertEquals(listOf("sensor.ceiling_light", "sensor.window_lux"), candidates.map(HaAmbientLuxCandidate::entityId))
        assertFalse(candidates[0].available)
        assertNull(candidates[0].currentLux)
        assertTrue(candidates[1].available)
        assertEquals(123.4, candidates[1].currentLux!!, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `subscription rejects wildcard-like entity ids`() {
        HaAmbientLuxProtocol.subscribeTrigger("sensor.*")
    }

    private fun state(entityId: String, value: String, updated: String) = JSONObject()
        .put("entity_id", entityId)
        .put("state", value)
        .put("last_updated", updated)
        .put("attributes", JSONObject())
}
