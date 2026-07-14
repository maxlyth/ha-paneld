package io.github.maxlyth.hapaneld.dashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class EntityFilterProtocolTest {
    private val ids = listOf("light.alpha", "sensor.temperature")

    @Test fun unfilteredEntitySubscriptionGetsExactAllowList() {
        val result = EntityFilterProtocol.injectSubscription(
            """{"id":7,"type":"subscribe_entities"}""", ids,
        )

        assertTrue(result.modified)
        val json = JSONObject(result.text)
        assertEquals(7, json.getInt("id"))
        assertEquals(ids, (0 until json.getJSONArray("entity_ids").length()).map {
            json.getJSONArray("entity_ids").getString(it)
        })
    }

    @Test fun authAndUnrelatedMessagesRemainByteIdentical() {
        val auth = """{ "type": "auth", "access_token": "do-not-reencode" }"""
        val ping = """{"id":8,"type":"ping"}"""

        assertEquals(EntityFilterProtocol.Mutation(auth, false), EntityFilterProtocol.injectSubscription(auth, ids))
        assertEquals(EntityFilterProtocol.Mutation(ping, false), EntityFilterProtocol.injectSubscription(ping, ids))
    }

    @Test fun anExistingNarrowFilterIsNeverWidenedOrReencoded() {
        val existing = """{ "id": 9, "type": "subscribe_entities", "entity_ids": ["light.one"] }"""
        assertEquals(
            EntityFilterProtocol.Mutation(existing, false),
            EntityFilterProtocol.injectSubscription(existing, ids),
        )
    }

    @Test fun malformedAndOversizedFramesPassThrough() {
        assertFalse(EntityFilterProtocol.injectSubscription("not-json", ids).modified)
        val huge = "x".repeat(EntityFilterProtocol.MAX_TEXT_FRAME_CHARS + 1)
        assertEquals(huge, EntityFilterProtocol.injectSubscription(huge, ids).text)
    }

    @Test fun entityIdsAreSortedDeduplicatedAndValidated() {
        assertEquals(
            listOf("light.a", "sensor.b"),
            EntityFilterProtocol.normalize(listOf(" sensor.b ", "light.a", "sensor.b")),
        )
        assertTrue(runCatching { EntityFilterProtocol.normalize(listOf("invalid")) }.isFailure)
        assertTrue(runCatching { EntityFilterProtocol.normalize(listOf("Light.upper")) }.isFailure)
    }

    @Test fun updateBodySupportsReplaceAndToggleForms() {
        val replace = EntityFilterProtocol.parseUpdate(
            """{"enabled":true,"entity_ids":["sensor.b","light.a","sensor.b"]}""",
        )
        assertEquals(true, replace.enabled)
        assertEquals(listOf("light.a", "sensor.b"), replace.entityIds)

        val toggle = EntityFilterProtocol.parseUpdate("""{"enabled":false}""")
        assertEquals(false, toggle.enabled)
        assertEquals(null, toggle.entityIds)
    }

    @Test fun updateBodyIsStrict() {
        listOf(
            "{}",
            """{"enabled":"true"}""",
            """{"entity_ids":"sensor.one"}""",
            """{"entity_ids":[1]}""",
        ).forEach { assertTrue(it, runCatching { EntityFilterProtocol.parseUpdate(it) }.isFailure) }
    }

    @Test fun automaticModeIsExplicitAndCannotCarryAnExactList() {
        val automatic = EntityFilterProtocol.parseUpdate("""{"mode":"automatic","enabled":true}""")
        assertEquals("automatic", automatic.mode)
        assertEquals(true, automatic.enabled)
        assertTrue(runCatching {
            EntityFilterProtocol.parseUpdate("""{"mode":"automatic","entity_ids":["light.a"]}""")
        }.isFailure)
    }

    @Test fun websocketUrlAndOriginPreserveReverseProxyPathAndPort() {
        assertEquals(
            "wss://ha.example:8443/prefix/api/websocket",
            EntityFilterProtocol.upstreamWebSocketUrl("https://ha.example:8443/prefix/"),
        )
        assertEquals("https://ha.example:8443", EntityFilterProtocol.origin("https://ha.example:8443/prefix"))
        assertEquals("https://ha.example", EntityFilterProtocol.origin("HTTPS://HA.EXAMPLE:443/prefix"))
        assertEquals(
            "wss://HA.EXAMPLE:443/prefix/api/websocket",
            EntityFilterProtocol.upstreamWebSocketUrl("HTTPS://HA.EXAMPLE:443/prefix/"),
        )
        assertEquals(
            "ws://[2001:db8::12]:8123/ha/api/websocket",
            EntityFilterProtocol.upstreamWebSocketUrl("http://[2001:db8::12]:8123/ha"),
        )
    }

    @Test fun documentScriptTargetsOnlyTheHaApiSocketAndPreservesNativeShape() {
        val script = EntityFilterProtocol.documentStartScript("https://ha.example", ids)
        assertTrue(script.contains("targetWsOrigin=\"wss://ha.example\""))
        assertTrue(script.contains("u.origin===targetWsOrigin"))
        assertTrue(script.contains("targetWsPath=\"/api/websocket\""))
        assertTrue(script.contains("u.pathname===targetWsPath"))
        assertTrue(script.contains("entityIds=[\"light.alpha\",\"sensor.temperature\"]"))
        assertFalse(script.contains("127.0.0.1"))
        assertTrue(script.contains("FilteredWebSocket.prototype=Native.prototype"))
    }

    @Test fun documentScriptExecutesAndFiltersOnlyThePrimarySocket() {
        val node = runCatching { ProcessBuilder("node", "--version").start().let { it.waitFor() == 0 } }
            .getOrDefault(false)
        assumeTrue("node unavailable", node)
        val script = EntityFilterProtocol.documentStartScript("https://ha.example", ids)
        val harness = """
            global.window=globalThis;
            global.location={href:'https://ha.example/dashboard'};
            let modified=0;
            global.externalApp={entityFilterSubscriptionModified(){modified++;}};
            function Native(url,protocols){this.url=String(url);this.protocols=protocols;this.sent=[];}
            Native.prototype.send=function(data){this.sent.push(data);};
            Native.CONNECTING=0;Native.OPEN=1;Native.CLOSING=2;Native.CLOSED=3;
            global.WebSocket=Native;
            $script
            const primary=new WebSocket('wss://ha.example/api/websocket');
            const camera=new WebSocket('wss://ha.example/api/camera', ['camera']);
            const auth='{ "type": "auth", "access_token": "unchanged" }';
            const unfiltered='{"id":7,"type":"subscribe_entities"}';
            const existing='{ "id": 8, "type": "subscribe_entities", "entity_ids": ["light.one"] }';
            primary.send(auth);primary.send(unfiltered);primary.send(existing);camera.send(unfiltered);
            if(primary.url!=='wss://ha.example/api/websocket')throw Error('primary socket replaced: '+primary.url);
            if(camera.url!=='wss://ha.example/api/camera')throw Error('camera socket replaced: '+camera.url);
            if(!(primary instanceof Native))throw Error('prototype not preserved');
            if(WebSocket.OPEN!==1)throw Error('static constants not preserved');
            if(primary.sent[0]!==auth)throw Error('auth changed');
            const filtered=JSON.parse(primary.sent[1]);
            if(JSON.stringify(filtered.entity_ids)!==JSON.stringify(['light.alpha','sensor.temperature']))throw Error('wrong filter');
            if(primary.sent[2]!==existing)throw Error('existing filter changed');
            if(camera.sent[0]!==unfiltered)throw Error('camera command changed');
            if(modified!==1)throw Error('wrong modification count: '+modified);
        """.trimIndent()
        val process = ProcessBuilder("node").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(harness) }
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }

    @Test fun filterHashIsOrderIndependentAndChangesWithMembership() {
        val hash = EntityFilterProtocol.hash(listOf("sensor.b", "light.a"))
        assertEquals(16, hash.length)
        assertEquals(hash, EntityFilterProtocol.hash(listOf("light.a", "sensor.b", "sensor.b")))
        assertFalse(EntityFilterProtocol.hash(listOf("light.a")) == EntityFilterProtocol.hash(listOf("light.b")))
    }

    @Test fun telemetryResetsAndEmitsValidCounterJson() {
        val lease = EntityFilterTelemetry.started(ids)
        EntityFilterTelemetry.subscriptionModified(lease)

        val json = JSONObject(EntityFilterTelemetry.json())
        assertTrue(json.getBoolean("active"))
        assertEquals("native_socket", json.getString("mode"))
        assertEquals(ids.size, json.getInt("entityCount"))
        assertEquals(1, json.getInt("modifiedSubscriptions"))

        EntityFilterTelemetry.stop(lease)
        val stopped = JSONObject(EntityFilterTelemetry.json())
        assertFalse(stopped.getBoolean("active"))
        assertEquals(0, stopped.getInt("entityCount"))
        assertEquals("", stopped.getString("filterHash"))
        assertEquals(0, stopped.getLong("modifiedSubscriptions"))
    }

    @Test fun staleTelemetryLeaseCannotMutateOrStopReplacementState() {
        val old = EntityFilterTelemetry.started(listOf("light.old"))
        val current = EntityFilterTelemetry.started(ids)

        EntityFilterTelemetry.subscriptionModified(old)
        EntityFilterTelemetry.failed(old, "stale")
        EntityFilterTelemetry.held(old, "stale-held")
        EntityFilterTelemetry.directFallback(old)
        EntityFilterTelemetry.stop(old)

        val live = JSONObject(EntityFilterTelemetry.json())
        assertTrue(live.getBoolean("active"))
        assertEquals(ids.size, live.getInt("entityCount"))
        assertEquals(0, live.getLong("modifiedSubscriptions"))
        assertEquals(0, live.getLong("failures"))
        assertEquals("", live.getString("lastError"))
        EntityFilterTelemetry.stop(current)
    }

    @Test fun heldTelemetryIsInactiveAndPreservesConfiguredIdentityWithoutDirectFallback() {
        val lease = EntityFilterTelemetry.started(ids)
        val expectedHash = EntityFilterProtocol.hash(ids)

        EntityFilterTelemetry.held(lease, "document_start_install")

        val held = JSONObject(EntityFilterTelemetry.json())
        assertFalse(held.getBoolean("active"))
        assertEquals(ids.size, held.getInt("entityCount"))
        assertEquals(expectedHash, held.getString("filterHash"))
        assertEquals(1, held.getLong("failures"))
        assertEquals(0, held.getLong("directFallbacks"))
        assertEquals("document_start_install", held.getString("lastError"))
        EntityFilterTelemetry.stop(lease)
    }
}
