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

    private val ENTITY_ID = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")
    private val FILTER_KEYS = setOf(
        "entity_ids",
        "include_domains", "include_entities", "include_entity_globs",
        "exclude_domains", "exclude_entities", "exclude_entity_globs",
    )

    data class Mutation(val text: String, val modified: Boolean)
    data class Update(val enabled: Boolean?, val entityIds: List<String>?, val mode: String? = null)

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
        if (text.length > MAX_TEXT_FRAME_CHARS || entityIds.isEmpty()) return Mutation(text, false)
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return Mutation(text, false)
        if (obj.optString("type") != "subscribe_entities") return Mutation(text, false)
        if (FILTER_KEYS.any(obj::has)) return Mutation(text, false)
        obj.put("entity_ids", JSONArray(entityIds))
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
    fun documentStartScript(haUrl: String, entityIds: Collection<String>): String {
        val upstream = URI(upstreamWebSocketUrl(haUrl))
        val targetWsOrigin = JSONObject.quote(
            origin(haUrl).replaceFirst("https://", "wss://").replaceFirst("http://", "ws://"),
        )
        val targetWsPath = JSONObject.quote(upstream.rawPath)
        val ids = JSONArray(normalize(entityIds)).toString()
        val filterKeys = JSONArray(FILTER_KEYS.sorted()).toString()
        return """
            (()=>{
              const Native=window.WebSocket;
              if(!Native||Native.__haPaneldEntityFilter)return;
              const targetWsOrigin=$targetWsOrigin,targetWsPath=$targetWsPath,entityIds=$ids,filterKeys=$filterKeys;
              function FilteredWebSocket(url,protocols){
                const socket=protocols===undefined?new Native(url):new Native(url,protocols);
                try{
                  const u=new URL(String(url),location.href);
                  if(u.origin===targetWsOrigin&&u.pathname===targetWsPath){
                    Object.defineProperty(socket,'send',{configurable:true,writable:true,value:function(data){
                      let outgoing=data;
                      if(typeof data==='string')try{
                        const message=JSON.parse(data);
                        if(message&&message.type==='subscribe_entities'&&!filterKeys.some(key=>Object.prototype.hasOwnProperty.call(message,key))){
                          message.entity_ids=entityIds;
                          outgoing=JSON.stringify(message);
                          try{if(window.externalApp&&typeof window.externalApp.entityFilterSubscriptionModified==='function')window.externalApp.entityFilterSubscriptionModified();}catch(e){}
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
}
