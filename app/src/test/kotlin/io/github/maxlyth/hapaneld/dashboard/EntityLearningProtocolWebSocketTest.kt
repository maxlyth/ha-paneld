package io.github.maxlyth.hapaneld.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class EntityLearningProtocolWebSocketTest {
    @Test fun learningScriptTargetsTheExactReverseProxyWebsocket() {
        val script = EntityLearningProtocol.documentStartScript("https://HA.EXAMPLE:443/ha/")

        assertTrue(script.contains("targetWsOrigin=\"wss://ha.example\""))
        assertTrue(script.contains("targetWsPath=\"/ha/api/websocket\""))
        assertTrue(script.contains("u.origin===targetWsOrigin&&u.pathname===targetWsPath"))
    }

    @Test fun reverseProxySocketProducesLearningMetrics() {
        val node = runCatching { ProcessBuilder("node", "--version").start().let { it.waitFor() == 0 } }
            .getOrDefault(false)
        assumeTrue("node unavailable", node)
        val script = EntityLearningProtocol.documentStartScript("https://ha.example/ha")
        val harness = """
            global.window=globalThis;
            global.location={href:'https://ha.example/ha/dashboard'};
            global.document={querySelector(){return null;}};
            const timers=[];
            global.setInterval=(fn)=>{timers.push(fn);return timers.length;};
            const metricBatches=[];
            global.externalApp={entityLearningMetrics(payload){metricBatches.push(JSON.parse(payload));}};
            function Native(url,protocols){this.url=String(url);this.protocols=protocols;this.sent=[];this.listeners={};}
            Native.prototype.send=function(data){this.sent.push(data);};
            Native.prototype.addEventListener=function(type,listener){this.listeners[type]=listener;};
            Native.prototype.emit=function(type,event){const listener=this.listeners[type];if(listener)listener(event);};
            Native.CONNECTING=0;Native.OPEN=1;Native.CLOSING=2;Native.CLOSED=3;
            global.WebSocket=Native;
            $script
            const primary=new WebSocket('wss://ha.example/ha/api/websocket');
            const wrongPath=new WebSocket('wss://ha.example/api/websocket');
            primary.send('{"id":7,"type":"subscribe_entities"}');
            primary.emit('message',{data:'{"id":7,"type":"event","event":{"c":{"sensor.temperature":{"s":"21"}}}}'});
            timers[1]();
            if(!primary.listeners.message)throw Error('reverse-proxy socket was not observed');
            if(wrongPath.listeners.message)throw Error('wrong websocket path was observed');
            if(metricBatches.length!==1)throw Error('wrong metric batch count: '+metricBatches.length);
            if(metricBatches[0]['sensor.temperature'][0]!==1)throw Error('update was not counted');
        """.trimIndent()
        val process = ProcessBuilder("node").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(harness) }
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }
}
