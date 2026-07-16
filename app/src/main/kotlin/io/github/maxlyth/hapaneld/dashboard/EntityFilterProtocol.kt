package io.github.maxlyth.hapaneld.dashboard

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/** Pure protocol helpers for the experimental panel-side Home Assistant entity subscription filter. */
object EntityFilterProtocol {
    const val PATH = "/api/websocket"
    const val MAX_ENTITY_IDS = 50_000
    const val MAX_TEXT_FRAME_CHARS = 1_000_000
    const val MAX_API_BODY_BYTES = 4_000_000
    internal const val TRAFFIC_SAMPLE_MS = 5_000
    internal const val MAX_TRAFFIC_COUNT = 50_000_000L
    internal const val MAX_TRAFFIC_CHARS = 1_000_000_000L
    internal const val MAX_TRAFFIC_MICROS = 300_000_000L
    /** Home Assistant treats an empty entity_ids array as no filter. A syntactically valid sentinel
     * keeps its exact-ID filter active on every HA version which supports subscribe_entities, while
     * using a domain reserved to ha-paneld makes a real-state collision impractical. */
    internal const val EMPTY_SUBSCRIPTION_ENTITY_ID =
        "ha_paneld_internal.empty_subscription_5f39d48b7a6c4e2a"

    private val ENTITY_ID = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")
    private val FILTER_KEYS = setOf(
        "entity_ids",
        "include", "exclude",
        "include_domains", "include_entities", "include_entity_globs",
        "exclude_domains", "exclude_entities", "exclude_entity_globs",
    )

    data class Mutation(val text: String, val modified: Boolean)
    data class Update(val enabled: Boolean?, val entityIds: List<String>?, val mode: String? = null)
    data class TrafficBatch(
        val sampleMs: Long,
        val frames: Long,
        val frameChars: Long,
        val entityUpdates: Long,
        val processingMicros: Long,
        val droppedFrames: Long,
    )

    /** Strict JSON body for the experimental runtime API; null fields mean "leave unchanged". */
    fun parseUpdate(text: String): Update {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_API_BODY_BYTES) { "request too large" }
        val obj = JSONObject(text)
        val enabled = if (obj.has("enabled")) {
            obj.opt("enabled") as? Boolean ?: throw IllegalArgumentException("enabled must be boolean")
        } else null
        val ids = if (obj.has("entity_ids")) {
            val array = obj.opt("entity_ids") as? JSONArray
                ?: throw IllegalArgumentException("entity_ids must be an array")
            normalize((0 until array.length()).map { i ->
                array.opt(i) as? String ?: throw IllegalArgumentException("entity_ids must contain strings")
            })
        } else null
        val mode = if (obj.has("mode")) obj.optString("mode").also {
            require(it == "manual" || it == "automatic") { "mode must be manual or automatic" }
        } else null
        require(enabled != null || ids != null || mode != null) { "enabled, entity_ids or mode required" }
        require(mode != "automatic" || ids == null) { "automatic mode does not accept entity_ids" }
        return Update(enabled, ids, mode)
    }

    /** Sort, de-duplicate and validate an exact HA entity-id allow-list. */
    fun normalize(entityIds: Iterable<String>): List<String> {
        val normalized = entityIds.asSequence().map(String::trim).filter(String::isNotEmpty)
            .distinct().sorted().toList()
        require(normalized.size <= MAX_ENTITY_IDS) { "too many entity_ids (max $MAX_ENTITY_IDS)" }
        val invalid = normalized.firstOrNull { it.length > 255 || !ENTITY_ID.matches(it) }
        require(invalid == null) { "invalid entity_id" }
        return normalized
    }

    /** Stable non-reversible identifier for comparing private runtime filter sets in diagnostics. */
    fun hash(entityIds: Iterable<String>): String {
        val normalized = normalize(entityIds)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.joinToString("\n").toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Add an exact allow-list only to the frontend's unfiltered `subscribe_entities` command. Existing
     * filters are passed through byte-for-byte: the wrapper must never broaden a caller's narrower request.
     * Every other command, including the token-bearing auth message, is returned without re-encoding.
     */
    fun injectSubscription(text: String, entityIds: List<String>): Mutation {
        if (text.length > MAX_TEXT_FRAME_CHARS) return Mutation(text, false)
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return Mutation(text, false)
        if (obj.optString("type") != "subscribe_entities") return Mutation(text, false)
        if (FILTER_KEYS.any(obj::has)) return Mutation(text, false)
        obj.put("entity_ids", JSONArray(entityIds.ifEmpty { listOf(EMPTY_SUBSCRIPTION_ENTITY_ID) }))
        return Mutation(obj.toString(), true)
    }

    /** Convert the configured HA HTTP(S) base URL to its WebSocket API URL. */
    fun upstreamWebSocketUrl(haUrl: String): String {
        val uri = URI(haUrl.trim().trimEnd('/'))
        val wsScheme = when (uri.scheme?.lowercase()) {
            "https" -> "wss"
            "http" -> "ws"
            else -> throw IllegalArgumentException("ha_url must use http or https")
        }
        require(!uri.rawAuthority.isNullOrBlank()) { "ha_url must contain a host" }
        return URI(wsScheme, uri.rawAuthority, uri.rawPath.trimEnd('/') + PATH, null, null).toString()
    }

    /** Normalized browser Origin expected from a page loaded from [haUrl]. */
    fun origin(haUrl: String): String {
        val uri = URI(haUrl.trim())
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "ha_url must use http or https" }
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("ha_url must contain a host")
        val port = uri.port.takeUnless { (scheme == "http" && it == 80) || (scheme == "https" && it == 443) } ?: -1
        return URI(scheme, null, host, port, null, null, null).toString().trimEnd('/')
    }

    /**
     * Document-start wrapper that leaves Chromium's native socket intact and rewrites only an unfiltered
     * subscribe_entities command on the configured HA API socket. Static constants and the native
     * prototype are preserved so the frontend observes a normal WebSocket; auth and inbound frames never
     * cross a proxy or JavaScript parser.
     */
    fun documentStartScript(
        haUrl: String,
        entityIds: Collection<String>,
        documentOrigins: Collection<String> = setOf(origin(haUrl)),
    ): String {
        val upstream = URI(upstreamWebSocketUrl(haUrl))
        val targetWsOrigins = JSONArray(documentOrigins.map(::origin).distinct().sorted().map {
            it.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        }).toString()
        val targetWsPath = JSONObject.quote(upstream.rawPath)
        val ids = JSONArray(normalize(entityIds)).toString()
        val emptySubscriptionEntityId = JSONObject.quote(EMPTY_SUBSCRIPTION_ENTITY_ID)
        val filterKeys = JSONArray(FILTER_KEYS.sorted()).toString()
        return """
            (()=>{
              if(window.top&&window.top!==window)return;
              const Native=window.WebSocket;
              if(!Native||Native.__haPaneldEntityFilter)return;
              const targetWsOrigins=$targetWsOrigins,targetWsPath=$targetWsPath,entityIds=$ids,emptySubscriptionEntityId=$emptySubscriptionEntityId,filterKeys=$filterKeys;
              function FilteredWebSocket(url,protocols){
                const socket=protocols===undefined?new Native(url):new Native(url,protocols);
                try{
                  const u=new URL(String(url),location.href);
                  if(targetWsOrigins.includes(u.origin)&&u.pathname===targetWsPath){
                    Object.defineProperty(socket,'send',{configurable:true,writable:true,value:function(data){
                      let outgoing=data;
                      if(typeof data==='string')try{
                        const message=JSON.parse(data);
                        if(message&&message.type==='subscribe_entities'){
                          window.__haPaneldEntitySubscriptionId=message.id;
                          if(!filterKeys.some(key=>Object.prototype.hasOwnProperty.call(message,key))){
                            message.entity_ids=entityIds.length?entityIds:[emptySubscriptionEntityId];
                            outgoing=JSON.stringify(message);
                            try{if(window.externalApp&&typeof window.externalApp.entityFilterSubscriptionModified==='function')window.externalApp.entityFilterSubscriptionModified();}catch(e){}
                          }
                        }
                      }catch(e){}
                      return Native.prototype.send.call(this,outgoing);
                    }});
                  }
                }catch(e){}
                return socket;
              }
              Object.setPrototypeOf(FilteredWebSocket,Native);
              FilteredWebSocket.prototype=Native.prototype;
              for(const k of ['CONNECTING','OPEN','CLOSING','CLOSED'])Object.defineProperty(FilteredWebSocket,k,{value:Native[k]});
              Object.defineProperty(FilteredWebSocket,'__haPaneldEntityFilter',{value:true});
              window.WebSocket=FilteredWebSocket;
            })();
        """.trimIndent()
    }

    /**
     * Fixed-cardinality traffic observer for paired filter-on/filter-off measurements. It observes only
     * the configured HA entity socket, retains no entity ids or message content, and crosses the native
     * bridge once per bounded sample rather than once per frame. The script must be registered after the
     * filter wrapper so it sees the effective outbound subscribe_entities command in either test arm.
     */
    fun trafficObserverDocumentStartScript(
        haUrl: String,
        documentOrigins: Collection<String> = setOf(origin(haUrl)),
    ): String {
        val upstream = URI(upstreamWebSocketUrl(haUrl))
        val targetWsOrigins = JSONArray(documentOrigins.map(::origin).distinct().sorted().map {
            it.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        }).toString()
        val targetWsPath = JSONObject.quote(upstream.rawPath)
        return """
            (()=>{
              if(window.top&&window.top!==window)return;
              if(window.__haPaneldEntityTraffic)return;
              window.__haPaneldEntityTraffic=true;
              const Parent=window.WebSocket,targetWsOrigins=$targetWsOrigins,targetWsPath=$targetWsPath,
                maxFrameChars=$MAX_TEXT_FRAME_CHARS,maxCount=$MAX_TRAFFIC_COUNT,maxChars=$MAX_TRAFFIC_CHARS,
                maxMicros=$MAX_TRAFFIC_MICROS,sampleMs=$TRAFFIC_SAMPLE_MS;
              if(!Parent)return;
              const now=()=>window.performance&&typeof window.performance.now==='function'?window.performance.now():0;
              const sat=(left,right,limit)=>Math.min(limit,left+Math.max(0,right));
              let frames=0,frameChars=0,entityUpdates=0,processingMs=0,droppedFrames=0,lastFlush=now();
              function countOwn(value){
                if(!value||typeof value!=='object')return;
                for(const key in value)if(Object.prototype.hasOwnProperty.call(value,key))entityUpdates=sat(entityUpdates,1,maxCount);
              }
              function inspect(message,subscriptionId){
                const effectiveId=subscriptionId===null||subscriptionId===undefined?window.__haPaneldEntitySubscriptionId:subscriptionId;
                if(!message||message.id!==effectiveId||message.type!=='event'||!message.event)return;
                countOwn(message.event.a);countOwn(message.event.c);
              }
              function TrafficWebSocket(url,protocols){
                const socket=protocols===undefined?new Parent(url):new Parent(url,protocols);
                try{
                  const u=new URL(String(url),location.href);
                  if(targetWsOrigins.includes(u.origin)&&u.pathname===targetWsPath){
                    let entitySubscriptionId=null;
                    const send=socket.send;
                    Object.defineProperty(socket,'send',{configurable:true,writable:true,value:function(data){
                      const started=now();
                      try{
                        if(typeof data==='string'&&data.length<=maxFrameChars){
                          try{const message=JSON.parse(data);if(message&&message.type==='subscribe_entities')entitySubscriptionId=message.id}catch(e){}
                        }
                      }finally{processingMs=sat(processingMs,Math.max(0,now()-started),maxMicros/1000)}
                      return send.call(this,data);
                    }});
                    socket.addEventListener('message',event=>{
                      const started=now();
                      try{
                        if(typeof event.data!=='string')return;
                        frames=sat(frames,1,maxCount);frameChars=sat(frameChars,event.data.length,maxChars);
                        if(event.data.length>maxFrameChars){droppedFrames=sat(droppedFrames,1,maxCount);return}
                        try{
                          const decoded=JSON.parse(event.data);
                          if(Array.isArray(decoded)){for(let i=0;i<decoded.length;i++)inspect(decoded[i],entitySubscriptionId)}
                          else inspect(decoded,entitySubscriptionId);
                        }catch(e){droppedFrames=sat(droppedFrames,1,maxCount)}
                      }finally{processingMs=sat(processingMs,Math.max(0,now()-started),maxMicros/1000)}
                    });
                  }
                }catch(e){}
                return socket;
              }
              Object.setPrototypeOf(TrafficWebSocket,Parent);TrafficWebSocket.prototype=Parent.prototype;
              for(const key of ['CONNECTING','OPEN','CLOSING','CLOSED'])Object.defineProperty(TrafficWebSocket,key,{value:Parent[key]});
              window.WebSocket=TrafficWebSocket;
              setInterval(()=>{
                const current=now(),elapsed=Math.max(0,current-lastFlush);lastFlush=current;
                const payload=[Math.min(maxCount,Math.round(elapsed)),frames,frameChars,entityUpdates,
                  Math.min(maxMicros,Math.round(processingMs*1000)),droppedFrames].join(',');
                frames=frameChars=entityUpdates=processingMs=droppedFrames=0;
                try{if(window.externalApp&&typeof window.externalApp.entityFilterTrafficMetrics==='function')window.externalApp.entityFilterTrafficMetrics(payload)}catch(e){}
              },sampleMs);
            })();
        """.trimIndent()
    }

    /** Parse the observer's numeric, label-free fixed-cardinality bridge envelope. */
    fun parseTrafficBatch(text: String): TrafficBatch {
        val values = LongArray(6)
        var valueIndex = 0
        var value = 0L
        var hasDigit = false
        require(text.isNotEmpty()) { "traffic batch is empty" }
        for (char in text) {
            if (char == ',') {
                require(hasDigit) { "empty traffic field" }
                require(valueIndex < values.lastIndex) { "too many traffic fields" }
                values[valueIndex++] = value
                value = 0L
                hasDigit = false
            } else {
                require(char in '0'..'9') { "invalid traffic field" }
                hasDigit = true
                val digit = (char - '0').toLong()
                value = if (value > (MAX_TRAFFIC_CHARS - digit) / 10L) {
                    MAX_TRAFFIC_CHARS
                } else {
                    value * 10L + digit
                }
            }
        }
        require(hasDigit) { "empty traffic field" }
        require(valueIndex == values.lastIndex) { "traffic batch must contain six fields" }
        values[valueIndex] = value
        return TrafficBatch(
            sampleMs = values[0].coerceIn(0L, MAX_TRAFFIC_COUNT),
            frames = values[1].coerceIn(0L, MAX_TRAFFIC_COUNT),
            frameChars = values[2].coerceIn(0L, MAX_TRAFFIC_CHARS),
            entityUpdates = values[3].coerceIn(0L, MAX_TRAFFIC_COUNT),
            processingMicros = values[4].coerceIn(0L, MAX_TRAFFIC_MICROS),
            droppedFrames = values[5].coerceIn(0L, MAX_TRAFFIC_COUNT),
        )
    }
}
