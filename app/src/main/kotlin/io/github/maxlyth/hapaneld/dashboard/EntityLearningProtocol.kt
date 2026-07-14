package io.github.maxlyth.hapaneld.dashboard

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Pure dashboard-analysis and document-start helpers for automatic entity learning. */
object EntityLearningProtocol {
    private val ENTITY_ID = Regex("(?<![a-z0-9_])[a-z0-9_]+\\.[a-z0-9_]+(?![a-z0-9_])")
    private val DIRECT_ENTITY_ID = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")
    private val TARGET_KEYS = setOf("entity_id", "device_id", "area_id", "floor_id", "label_id")
    private val TEMPLATE_MARKERS = listOf("{{", "{%", "[[[", "hass.states", "states[")
    private val PSEUDO_DOMAINS = setOf("variables", "config", "hass", "state", "states", "entity")

    data class ScanResult(
        val entityIds: Set<String>,
        /** Canonical HA targets suitable for `extract_from_target`. */
        val targets: List<String>,
        val unresolved: List<String>,
        val selectors: List<Selector> = emptyList(),
    )

    data class Selector(
        val exclude: Boolean,
        val domains: Set<String> = emptySet(),
        val entityGlobs: Set<String> = emptySet(),
        val areas: Set<String> = emptySet(),
        val labels: Set<String> = emptySet(),
    )

    /** Scan every nested dashboard value. False-positive entity-like strings are removed against the catalog later. */
    fun scanDashboard(configJson: String): ScanResult {
        val root = JSONObject(configJson)
        val ids = linkedSetOf<String>()
        val targets = linkedSetOf<String>()
        val unresolved = linkedSetOf<String>()
        val selectors = mutableListOf<Selector>()

        fun strings(value: Any?): Set<String> = when (value) {
            is String -> setOf(value.lowercase())
            is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank)?.lowercase() }.toSet()
            else -> emptySet()
        }

        fun selector(rule: JSONObject, exclude: Boolean): Selector = Selector(
            exclude = exclude,
            domains = strings(rule.opt("domain")),
            entityGlobs = strings(rule.opt("entity_id")),
            areas = strings(rule.opt("area")) + strings(rule.opt("area_id")),
            labels = strings(rule.opt("label")) + strings(rule.opt("label_id")),
        )

        fun autoEntityRules(card: JSONObject, path: String) {
            val filter = card.optJSONObject("filter") ?: return
            for ((name, excluded) in listOf("include" to false, "exclude" to true)) {
                val value = filter.opt(name)
                val rules = when (value) {
                    is JSONArray -> (0 until value.length()).mapNotNull(value::optJSONObject)
                    is JSONObject -> listOf(value)
                    else -> emptyList()
                }
                for (rule in rules) {
                    val parsed = selector(rule, excluded)
                    if (parsed.domains.isEmpty() && parsed.entityGlobs.isEmpty() && parsed.areas.isEmpty() && parsed.labels.isEmpty()) {
                        unresolved += "$path:auto-entities-$name"
                    } else selectors += parsed
                }
            }
        }

        fun scalarIds(value: String) {
            ENTITY_ID.findAll(value.lowercase()).map { it.value }
                .filterNot { it.substringBefore('.') in PSEUDO_DOMAINS }
                .forEach(ids::add)
        }

        fun walk(value: Any?, path: String) {
            when (value) {
                is JSONObject -> {
                    val type = value.optString("type")
                    if (type == "custom:auto-entities") autoEntityRules(value, path)
                    val before = ids.size + targets.size + selectors.size
                    val target = JSONObject()
                    for (key in TARGET_KEYS) if (value.has(key)) target.put(key, value.opt(key))
                    value.optJSONObject("target")?.let { nested ->
                        for (key in TARGET_KEYS) if (nested.has(key)) target.put(key, nested.opt(key))
                    }
                    if (target.length() > 0) targets += canonical(target)
                    val keys = value.keys().asSequence().toList().sorted()
                    for (key in keys) walk(value.opt(key), "$path.$key")
                    if (type.startsWith("custom:") && before == ids.size + targets.size + selectors.size) {
                        unresolved += "$path:$type"
                    }
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i), "$path[$i]")
                is String -> {
                    val before = ids.size
                    scalarIds(value)
                    if (TEMPLATE_MARKERS.any(value::contains) && ids.size == before) {
                        unresolved += "$path:dynamic-template"
                    }
                }
            }
        }
        walk(root, "dashboard")
        return ScanResult(ids, targets.toList(), unresolved.toList(), selectors)
    }

    fun resolveSelectors(
        selectors: List<Selector>,
        catalog: Collection<String>,
        metadata: Map<String, String>,
    ): Set<String> {
        fun glob(pattern: String, value: String): Boolean {
            val regex = buildString {
                append('^')
                for (ch in pattern) append(when (ch) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(ch.toString()) })
                append('$')
            }
            return Regex(regex).matches(value)
        }
        fun matches(selector: Selector, id: String): Boolean {
            val meta = metadata[id]?.let { runCatching { JSONObject(it) }.getOrNull() }
            val labels = meta?.optJSONArray("lb")?.let { a -> (0 until a.length()).map(a::optString).map(String::lowercase).toSet() }.orEmpty()
            return (selector.domains.isEmpty() || id.substringBefore('.') in selector.domains) &&
                (selector.entityGlobs.isEmpty() || selector.entityGlobs.any { glob(it, id) }) &&
                (selector.areas.isEmpty() || meta?.optString("ai")?.lowercase() in selector.areas) &&
                (selector.labels.isEmpty() || labels.any(selector.labels::contains))
        }
        val included = selectors.filterNot(Selector::exclude).flatMap { s -> catalog.filter { matches(s, it) } }.toMutableSet()
        val excluded = selectors.filter(Selector::exclude).flatMap { s -> catalog.filter { matches(s, it) } }.toSet()
        included.removeAll(excluded)
        return included
    }

    fun dashboardUrlPath(homeDashboard: String): String {
        val first = homeDashboard.trim().substringBefore('?').trim('/').substringBefore('/')
        return first.takeUnless { it.isBlank() || it == "lovelace" }.orEmpty()
    }

    fun canonical(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
            JSONObject.quote(key) + ":" + canonical(value.opt(key) ?: JSONObject.NULL)
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it) ?: JSONObject.NULL) }
        JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    /**
     * Observe the root HA state map without altering values. Direct reads and missing direct reads are
     * batched separately; enumeration is deliberately ignored because it is not dependency evidence.
     */
    fun documentStartScript(haUrl: String): String {
        val wsOrigin = JSONObject.quote(haUrl.trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://"))
        return """
        (()=>{
          if(window.__haPaneldEntityLearning)return;
          window.__haPaneldEntityLearning=true;
          const wrapped=new WeakSet(),id=/^[a-z0-9_]+\.[a-z0-9_]+$/;
          const seen=new Map(),missing=new Set(),metrics=new Map(); let enumerating=false;
          function flush(){
            if(!seen.size&&!missing.size)return;
            const payload=JSON.stringify({accessed:Object.fromEntries(seen),missing:Array.from(missing)});
            seen.clear();missing.clear();
            try{window.externalApp&&window.externalApp.entityLearningAccesses(payload)}catch(e){}
          }
          setInterval(flush,2000);
          setInterval(()=>{
            if(!metrics.size)return;const out={};metrics.forEach((v,k)=>out[k]=v);metrics.clear();
            try{window.externalApp&&window.externalApp.entityLearningMetrics(JSON.stringify(out))}catch(e){}
          },5000);
          const Parent=window.WebSocket,target=$wsOrigin;
          function LearningWebSocket(url,protocols){
            const socket=protocols===undefined?new Parent(url):new Parent(url,protocols);
            try{const u=new URL(String(url),location.href);if(u.origin===target&&u.pathname.endsWith('/api/websocket')){
              let hydrated=false,entitySubscriptionId=null;const send=socket.send;
              Object.defineProperty(socket,'send',{configurable:true,writable:true,value:function(data){
                if(typeof data==='string')try{const message=JSON.parse(data);if(message&&message.type==='subscribe_entities')entitySubscriptionId=message.id}catch(e){}
                return send.call(this,data)
              }});
              socket.addEventListener('message',ev=>{if(typeof ev.data!=='string')return;try{
                const decoded=JSON.parse(ev.data),messages=Array.isArray(decoded)?decoded:[decoded];messages.forEach(m=>{
                if(m.id!==entitySubscriptionId)return;const event=m&&m.type==='event'&&m.event;if(!event)return;
                if(event.a&&!hydrated){hydrated=true;Object.keys(event.a).forEach(k=>{if(!id.test(k))return;const old=metrics.get(k)||[0,0];old[0]++;old[1]+=JSON.stringify(event.a[k]).length;metrics.set(k,old)});return}const changed=event.c||{};
                Object.keys(changed).forEach(k=>{if(!id.test(k))return;const old=metrics.get(k)||[0,0];old[0]++;old[1]+=JSON.stringify(changed[k]).length;metrics.set(k,old)})})
              }catch(e){}})
            }}catch(e){}return socket
          }
          Object.setPrototypeOf(LearningWebSocket,Parent);LearningWebSocket.prototype=Parent.prototype;
          for(const k of ['CONNECTING','OPEN','CLOSING','CLOSED'])Object.defineProperty(LearningWebSocket,k,{value:Parent[k]});
          window.WebSocket=LearningWebSocket;
          setInterval(()=>{
            try{
              const root=document.querySelector('home-assistant'),h=root&&root.hass,s=h&&h.states;
              if(!root||!h||!s||wrapped.has(s))return;
              const proxy=new Proxy(s,{
                ownKeys(t){enumerating=true;queueMicrotask(()=>enumerating=false);return Reflect.ownKeys(t)},
                get(t,p,r){
                  if(typeof p==='string'&&id.test(p)&&!enumerating){
                    if(Reflect.has(t,p))seen.set(p,(seen.get(p)||0)+1);else missing.add(p);
                  }
                  return Reflect.get(t,p,r)
                },
                has(t,p){
                  if(typeof p==='string'&&id.test(p)&&!enumerating){
                    if(Reflect.has(t,p))seen.set(p,(seen.get(p)||0)+1);else missing.add(p);
                  }
                  return Reflect.has(t,p)
                }
              });
              wrapped.add(proxy); root.hass=Object.assign({},h,{states:proxy});
            }catch(e){}
          },1000);
        })();
        """.trimIndent()
    }

    fun parseAccessBatch(text: String): Pair<Map<String, Long>, Set<String>> {
        val obj = JSONObject(text)
        val accessed = when (val value = obj.opt("accessed")) {
            is JSONObject -> value.keys().asSequence().mapNotNull { id ->
                id.takeIf(DIRECT_ENTITY_ID::matches)?.let { it to value.optLong(id, 0).coerceIn(1, 1_000_000) }
            }.toMap()
            // Accept the rc3 prototype format across an in-place WebView rebuild.
            is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(DIRECT_ENTITY_ID::matches) }
                .associateWith { 1L }
            else -> emptyMap()
        }
        val missingArray = obj.optJSONArray("missing") ?: JSONArray()
        val missing = (0 until missingArray.length()).mapNotNull {
            missingArray.optString(it).takeIf(DIRECT_ENTITY_ID::matches)
        }.toSet()
        return accessed to missing
    }

    fun parseMetricBatch(text: String): Map<String, Pair<Long, Long>> {
        val obj = JSONObject(text)
        return obj.keys().asSequence().mapNotNull { id ->
            if (!DIRECT_ENTITY_ID.matches(id)) return@mapNotNull null
            val pair = obj.optJSONArray(id) ?: return@mapNotNull null
            id to (pair.optLong(0).coerceAtLeast(0) to pair.optLong(1).coerceAtLeast(0))
        }.toMap()
    }
}
