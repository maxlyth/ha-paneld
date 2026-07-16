package io.github.maxlyth.hapaneld.dashboard

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/** Pure dashboard-analysis and document-start helpers for automatic entity learning. */
object EntityLearningProtocol {
    data class BrowserObserverCosts(
        val frames: Long,
        val entities: Long,
        val frameChars: Long,
        val parseMicros: Long,
        val stringifyMicros: Long,
        val dropped: Long,
        val coalesced: Long,
    )

    data class MetricEnvelope(
        val metrics: Map<String, Pair<Long, Long>>,
        val observer: BrowserObserverCosts?,
    )

    private val ENTITY_ID = Regex("(?<![a-z0-9_])[a-z0-9_]+\\.[a-z0-9_]+(?![a-z0-9_])")
    private val DIRECT_ENTITY_ID = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")
    private val TARGET_REGISTRY_ID = Regex("^[A-Za-z0-9_-]+$")
    private val DASHBOARD_PATH_SEGMENT = Regex("^[a-z0-9][a-z0-9_-]*$")
    private val URL_SCHEME = Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE)
    private val TARGET_KEYS = setOf("entity_id", "device_id", "area_id", "floor_id", "label_id")
    private val EXPANDABLE_TARGET_KEYS = TARGET_KEYS - "entity_id"
    private val TEMPLATE_MARKERS = listOf("{{", "{%", "[[[", "hass.states", "states[")
    private val PSEUDO_DOMAINS = setOf("variables", "config", "hass", "state", "states", "entity")

    internal const val MAX_NATIVE_BRIDGE_PAYLOAD_CHARS = 1_000_000
    internal const val MAX_OBSERVER_WS_FRAME_CHARS = 786_432
    internal const val MAX_OBSERVER_ENTITY_ID_CHARS = 255
    internal const val MAX_OBSERVER_ACCESS_IDS = 1_024
    internal const val MAX_OBSERVER_MISSING_IDS = 1_024
    internal const val MAX_OBSERVER_METRIC_IDS = 2_048

    data class ScanResult(
        val entityIds: Set<String>,
        /** Canonical HA targets suitable for `extract_from_target`. */
        val targets: List<String>,
        val unresolved: List<String>,
        val selectors: List<Selector> = emptyList(),
        val dynamicExpressions: List<DynamicExpression> = emptyList(),
    )

    data class DynamicExpression(
        val sourceLocation: String,
        val literal: String,
        val truncated: Boolean,
        val fingerprint: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("source_location", sourceLocation)
            .put("literal", literal)
            .put("truncated", truncated)
            .put("fingerprint", fingerprint)
    }

    data class Selector(
        val exclude: Boolean,
        val domains: Set<String> = emptySet(),
        val entityGlobs: Set<String> = emptySet(),
        val areas: Set<String> = emptySet(),
        val labels: Set<String> = emptySet(),
        val source: String = "dashboard:auto-entities-selector",
    )

    data class SelectorResolution(val entityIds: Set<String>, val unresolved: List<String>)

    /** Scan every nested dashboard value. False-positive entity-like strings are removed against the catalog later. */
    fun scanDashboard(configJson: String): ScanResult {
        val root = JSONObject(configJson)
        val ids = linkedSetOf<String>()
        val targets = linkedSetOf<String>()
        val unresolved = linkedSetOf<String>()
        val selectors = mutableListOf<Selector>()
        val dynamicExpressions = linkedMapOf<String, DynamicExpression>()

        fun recordDynamicExpression(value: String, path: String) {
            if (TEMPLATE_MARKERS.none(value::contains)) return
            val key = "$path\u0000$value"
            dynamicExpressions.putIfAbsent(key, DynamicExpression(
                sourceLocation = path,
                literal = value.take(MAX_DYNAMIC_EXPRESSION_LENGTH),
                truncated = value.length > MAX_DYNAMIC_EXPRESSION_LENGTH,
                fingerprint = hash(key),
            ))
        }

        fun inspectDynamicExpressions(value: Any?, path: String) {
            when (value) {
                is JSONObject -> value.keys().asSequence().toList().sorted().forEach { key ->
                    inspectDynamicExpressions(value.opt(key), "$path.$key")
                }
                is JSONArray -> for (index in 0 until value.length()) {
                    inspectDynamicExpressions(value.opt(index), "$path[$index]")
                }
                is String -> recordDynamicExpression(value, path)
            }
        }

        fun strings(value: Any?): Set<String> = when (value) {
            is String -> setOf(value.lowercase())
            is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank)?.lowercase() }.toSet()
            else -> emptySet()
        }

        fun selector(rule: JSONObject, exclude: Boolean, source: String): Selector = Selector(
            exclude = exclude,
            domains = strings(rule.opt("domain")),
            entityGlobs = strings(rule.opt("entity_id")),
            areas = strings(rule.opt("area")) + strings(rule.opt("area_id")),
            labels = strings(rule.opt("label")) + strings(rule.opt("label_id")),
            source = source,
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
                for ((index, rule) in rules.withIndex()) {
                    val parsed = selector(rule, excluded, "$path:auto-entities-$name[$index]")
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

        fun targetValues(key: String, value: Any?): Pair<Any?, Boolean> {
            fun valid(candidate: String): Boolean = when (key) {
                "entity_id" -> DIRECT_ENTITY_ID.matches(candidate.lowercase())
                else -> TARGET_REGISTRY_ID.matches(candidate)
            }
            return when (value) {
                is String -> if (valid(value)) value to false else null to true
                is JSONArray -> {
                    val accepted = JSONArray()
                    var rejected = false
                    for (i in 0 until value.length()) {
                        val candidate = value.optString(i)
                        if (candidate.isNotBlank() && valid(candidate)) accepted.put(candidate) else rejected = true
                    }
                    accepted.takeIf { it.length() > 0 } to rejected
                }
                else -> null to true
            }
        }

        fun walk(value: Any?, path: String) {
            when (value) {
                is JSONObject -> {
                    val type = value.optString("type")
                    if (type == "custom:auto-entities") {
                        autoEntityRules(value, path)
                        // Selector filters are not walked for ordinary literal IDs, but their dynamic
                        // expressions are still valuable configuration evidence.
                        value.opt("filter")?.let { inspectDynamicExpressions(it, "$path.filter") }
                    }
                    val before = ids.size + targets.size + selectors.size
                    val target = JSONObject()
                    for (key in TARGET_KEYS) if (value.has(key)) {
                        val (accepted, rejected) = targetValues(key, value.opt(key))
                        if (accepted != null) target.put(key, accepted)
                        if (rejected) unresolved += "$path.$key:dynamic-target"
                    }
                    value.optJSONObject("target")?.let { nested ->
                        for (key in TARGET_KEYS) if (nested.has(key)) {
                            val (accepted, rejected) = targetValues(key, nested.opt(key))
                            if (accepted != null) target.put(key, accepted)
                            if (rejected) unresolved += "$path.target.$key:dynamic-target"
                        }
                    }
                    // Literal entity IDs are collected by scalarIds while walking the same object. HA's
                    // extract_from_target is only needed for registry selectors; sending every card's
                    // entity ID turns a normal dashboard into hundreds of serial WebSocket round trips.
                    val expandableTarget = JSONObject()
                    for (key in EXPANDABLE_TARGET_KEYS) if (target.has(key)) expandableTarget.put(key, target.opt(key))
                    if (expandableTarget.length() > 0) targets += canonical(expandableTarget)
                    // The selector linter owns auto-entities filter semantics. Walking those rules as
                    // ordinary strings would incorrectly promote literal IDs from an exclude rule.
                    val keys = value.keys().asSequence().toList().sorted().filterNot {
                        type == "custom:auto-entities" && it == "filter"
                    }
                    for (key in keys) walk(value.opt(key), "$path.$key")
                    if (type.startsWith("custom:") && before == ids.size + targets.size + selectors.size) {
                        unresolved += "$path:$type"
                    }
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i), "$path[$i]")
                is String -> {
                    val before = ids.size
                    scalarIds(value)
                    recordDynamicExpression(value, path)
                    if (TEMPLATE_MARKERS.any(value::contains) && ids.size == before) {
                        unresolved += "$path:dynamic-template"
                    }
                }
            }
        }
        walk(root, "dashboard")
        return ScanResult(
            ids,
            targets.toList(),
            unresolved.toList(),
            selectors,
            dynamicExpressions.values.sortedWith(compareBy({ it.sourceLocation }, { it.fingerprint })),
        )
    }

    fun resolveSelectors(
        selectors: List<Selector>,
        catalog: Collection<String>,
        metadata: Map<String, String>,
    ): SelectorResolution {
        if (selectors.isEmpty()) return SelectorResolution(emptySet(), emptyList())
        fun glob(pattern: String): Regex = Regex(buildString {
                append('^')
                for (ch in pattern) append(when (ch) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(ch.toString()) })
                append('$')
            })
        data class Meta(val area: String, val labels: Set<String>)
        data class CompiledSelector(val source: Selector, val entityGlobs: List<Regex>)
        val parsedMetadata = mutableMapOf<String, Meta>()
        fun metadataFor(id: String): Meta = parsedMetadata.getOrPut(id) {
            val meta = metadata[id]?.let { runCatching { JSONObject(it) }.getOrNull() }
            Meta(meta?.optString("ai")?.lowercase().orEmpty(), meta?.optJSONArray("lb")?.let { a ->
                (0 until a.length()).map(a::optString).map(String::lowercase).toSet()
            }.orEmpty())
        }
        val compiled = selectors.map { CompiledSelector(it, it.entityGlobs.map(::glob)) }
        fun matches(selector: CompiledSelector, id: String): Boolean {
            val source = selector.source
            return (source.domains.isEmpty() || id.substringBefore('.') in source.domains) &&
                (selector.entityGlobs.isEmpty() || selector.entityGlobs.any { it.matches(id) }) &&
                (source.areas.isEmpty() || metadataFor(id).area in source.areas) &&
                (source.labels.isEmpty() || metadataFor(id).labels.any(source.labels::contains))
        }
        val included = linkedSetOf<String>()
        val unresolved = mutableListOf<String>()
        for (selector in compiled.filterNot { it.source.exclude }) {
            val matched = catalog.filterTo(sortedSetOf()) { matches(selector, it) }
            when {
                matched.size > SELECTOR_ENTITY_LIMIT -> unresolved +=
                    "${selector.source.source}:broad-selector(${matched.size}>$SELECTOR_ENTITY_LIMIT)"
                (included + matched).size > SELECTOR_TOTAL_BUDGET -> unresolved +=
                    "${selector.source.source}:selector-budget(${included.size}+${matched.size}>$SELECTOR_TOTAL_BUDGET)"
                else -> included += matched
            }
        }
        val excluded = compiled.filter { it.source.exclude }.flatMap { s -> catalog.filter { matches(s, it) } }.toSet()
        included.removeAll(excluded)
        return SelectorResolution(included, unresolved)
    }

    internal const val SELECTOR_ENTITY_LIMIT = 64
    internal const val SELECTOR_TOTAL_BUDGET = 128
    internal const val MAX_DYNAMIC_EXPRESSION_LENGTH = 2048

    /**
     * A blank/root renderer route means "use this HA user's default dashboard". Explicit routes,
     * including `/lovelace`, stay authoritative and must not be replaced by frontend user data.
     */
    fun usesFrontendDefaultPanel(homeDashboard: String): Boolean = homeDashboard.trim()
        .substringBefore('?')
        .substringBefore('#')
        .trim('/')
        .isBlank()

    /**
     * Mirror the backend-visible portion of the Home Assistant frontend's default-panel chain.
     * A browser-local legacy default may still override [FRONTEND_FALLBACK_PANEL] in old profiles;
     * selecting the built-in Home panel here is deliberately fail-closed because it has no Lovelace
     * configuration for the static learner to scan.
     */
    fun frontendDefaultPanel(userDefault: String?, systemDefault: String?): String =
        userDefault?.takeIf(String::isNotBlank)
            ?: systemDefault?.takeIf(String::isNotBlank)
            ?: FRONTEND_FALLBACK_PANEL

    /**
     * Return the Lovelace WebSocket dashboard URL path, where an empty result selects ordinary
     * Lovelace. [defaultPanel] is considered only when [homeDashboard] is blank/root and is treated
     * as untrusted frontend user data.
     */
    fun dashboardUrlPath(homeDashboard: String, defaultPanel: String? = null): String {
        if (!usesFrontendDefaultPanel(homeDashboard)) {
            val first = homeDashboard.trim().substringBefore('?').substringBefore('#')
                .trim('/').substringBefore('/')
            return first.takeUnless { it.isBlank() || it == "lovelace" }.orEmpty()
        }
        return sanitizedDefaultPanelPath(defaultPanel)
    }

    private fun sanitizedDefaultPanelPath(defaultPanel: String?): String {
        val value = defaultPanel?.trim().orEmpty()
        if (value.isBlank() || value.startsWith("//") || '\\' in value || URL_SCHEME.containsMatchIn(value)) return ""
        val first = value.substringBefore('?').substringBefore('#').trim('/').substringBefore('/')
        return first.takeIf { it != "lovelace" && it != "null" && DASHBOARD_PATH_SEGMENT.matches(it) }.orEmpty()
    }

    internal const val FRONTEND_FALLBACK_PANEL = "home"

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
    fun documentStartScript(
        haUrl: String,
        documentOrigins: Collection<String> = setOf(EntityFilterProtocol.origin(haUrl)),
        featureCostsEnabled: Boolean = true,
    ): String {
        val upstream = URI(EntityFilterProtocol.upstreamWebSocketUrl(haUrl))
        val targetWsOrigins = JSONArray(documentOrigins.map(EntityFilterProtocol::origin).distinct().sorted().map {
            it.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        }).toString()
        val targetWsPath = JSONObject.quote(upstream.rawPath)
        val observerSetup = if (featureCostsEnabled) """
          const now=()=>window.performance&&typeof window.performance.now==='function'?window.performance.now():0;
          let observerFrames=0,observerEntities=0,observerFrameChars=0,observerParseMs=0,observerStringifyMs=0,
            observerDropped=0,observerCoalesced=0;
          function measuredParse(value){const started=now();try{return JSON.parse(value)}finally{observerParseMs+=Math.max(0,now()-started)}}
          function measuredStringify(value){const started=now(),encoded=JSON.stringify(value);observerStringifyMs+=Math.max(0,now()-started);return typeof encoded==='string'?encoded:''}
        """.trimIndent() else """
          function measuredParse(value){return JSON.parse(value)}
          function measuredStringify(value){const encoded=JSON.stringify(value);return typeof encoded==='string'?encoded:''}
        """.trimIndent()
        val accessCoalesced = if (featureCostsEnabled) "observerCoalesced=sat(observerCoalesced,1);" else ""
        val accessDropped = if (featureCostsEnabled) "observerDropped=sat(observerDropped,1);" else ""
        val observerMetricGuard = if (featureCostsEnabled) "&&!observerFrames&&!observerDropped&&!observerCoalesced" else ""
        val observerEnvelope = if (featureCostsEnabled) """
            out.__ha_paneld_observer={frames:observerFrames,entities:observerEntities,frame_chars:observerFrameChars,
              parse_us:Math.round(observerParseMs*1000),stringify_us:Math.round(observerStringifyMs*1000),
              dropped:observerDropped,coalesced:observerCoalesced};
        """.trimIndent() else ""
        val observerReset = if (featureCostsEnabled) """
            observerFrames=observerEntities=observerFrameChars=observerParseMs=observerStringifyMs=
              observerDropped=observerCoalesced=0;
        """.trimIndent() else ""
        val recordFrame = if (featureCostsEnabled) {
            "observerFrames=sat(observerFrames,1);observerFrameChars=sat(observerFrameChars,ev.data.length);"
        } else ""
        val recordEntity = if (featureCostsEnabled) "observerEntities=sat(observerEntities,1);" else ""
        val oversizedFrameDrop = if (featureCostsEnabled) "observerDropped=sat(observerDropped,1);" else ""
        val oversizedPayloadDrop = if (featureCostsEnabled) {
            "else observerDropped=sat(observerDropped,Math.max(1,discarded));"
        } else ""
        return """
        (()=>{
          if(window.top&&window.top!==window)return;
          if(window.__haPaneldEntityLearning)return;
          window.__haPaneldEntityLearning=true;
          const wrapped=new WeakSet(),id=/^[a-z0-9_]+\.[a-z0-9_]+$/;
          const seen=new Map(),missing=new Set(),metrics=new Map(); let enumerating=false;
          const maxBridgeChars=$MAX_NATIVE_BRIDGE_PAYLOAD_CHARS,maxFrameChars=$MAX_OBSERVER_WS_FRAME_CHARS,
            maxIdChars=$MAX_OBSERVER_ENTITY_ID_CHARS,maxAccessIds=$MAX_OBSERVER_ACCESS_IDS,
            maxMissingIds=$MAX_OBSERVER_MISSING_IDS,maxMetricIds=$MAX_OBSERVER_METRIC_IDS,maxCount=$MAX_OBSERVER_COUNT;
          const sat=(left,right)=>Math.min(maxCount,left+Math.max(0,right));
          $observerSetup
          function validId(value){return typeof value==='string'&&value.length<=maxIdChars&&id.test(value)}
          function recordAccess(entityId,present){
            if(present){const count=seen.get(entityId);if(count!==undefined){seen.set(entityId,sat(count,1));$accessCoalesced return}
              if(seen.size>=maxAccessIds){$accessDropped return}seen.set(entityId,1);return}
            if(missing.has(entityId)){$accessCoalesced return}
            if(missing.size>=maxMissingIds){$accessDropped return}missing.add(entityId)
          }
          function recordMetric(entityId,value){
            if(!validId(entityId))return;
            const old=metrics.get(entityId);if(old){old[0]=sat(old[0],1);old[1]=sat(old[1],measuredStringify(value).length);$accessCoalesced return}
            if(metrics.size>=maxMetricIds){$accessDropped return}
            metrics.set(entityId,[1,measuredStringify(value).length]);
          }
          function flush(){
            if(!seen.size&&!missing.size)return;
            const accessed={};seen.forEach((count,entityId)=>accessed[entityId]=count);
            const payload=JSON.stringify({accessed:accessed,missing:Array.from(missing)}),discarded=seen.size+missing.size;
            seen.clear();missing.clear();
            if(payload.length<maxBridgeChars){try{window.externalApp&&window.externalApp.entityLearningAccesses(payload)}catch(e){}}
            ${if (featureCostsEnabled) "else observerDropped=sat(observerDropped,discarded);" else ""}
          }
          setInterval(flush,2000);
          setInterval(()=>{
            if(!metrics.size$observerMetricGuard)return;
            const out={},discarded=metrics.size;metrics.forEach((v,k)=>out[k]=v);metrics.clear();
            $observerEnvelope
            const payload=JSON.stringify(out);
            $observerReset
            if(payload.length<maxBridgeChars){try{window.externalApp&&window.externalApp.entityLearningMetrics(payload)}catch(e){}}
            $oversizedPayloadDrop
          },5000);
          const Parent=window.WebSocket,targetWsOrigins=$targetWsOrigins,targetWsPath=$targetWsPath;
          function LearningWebSocket(url,protocols){
            const socket=protocols===undefined?new Parent(url):new Parent(url,protocols);
            try{const u=new URL(String(url),location.href);if(targetWsOrigins.includes(u.origin)&&u.pathname===targetWsPath){
              let hydrated=false,entitySubscriptionId=null;const send=socket.send;
              Object.defineProperty(socket,'send',{configurable:true,writable:true,value:function(data){
                if(typeof data==='string'){if(data.length>maxFrameChars){$oversizedFrameDrop}else try{const message=measuredParse(data);if(message&&message.type==='subscribe_entities')entitySubscriptionId=message.id}catch(e){}}
                return send.call(this,data)
              }});
              socket.addEventListener('message',ev=>{if(typeof ev.data!=='string')return;$recordFrame
                if(ev.data.length>maxFrameChars){$oversizedFrameDrop return}try{
                const decoded=measuredParse(ev.data);
                const messages=Array.isArray(decoded)?decoded:[decoded];messages.forEach(m=>{
                if(m.id!==entitySubscriptionId)return;const event=m&&m.type==='event'&&m.event;if(!event)return;
                if(event.a&&!hydrated){hydrated=true;for(const k in event.a)if(Object.prototype.hasOwnProperty.call(event.a,k)){$recordEntity recordMetric(k,event.a[k])}return}const changed=event.c||{};
                for(const k in changed)if(Object.prototype.hasOwnProperty.call(changed,k)){$recordEntity recordMetric(k,changed[k])}})
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
                  if(validId(p)&&!enumerating){
                    recordAccess(p,Reflect.has(t,p));
                  }
                  return Reflect.get(t,p,r)
                },
                has(t,p){
                  if(validId(p)&&!enumerating){
                    recordAccess(p,Reflect.has(t,p));
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
            // Accept the legacy prototype format across an in-place WebView rebuild.
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

    fun parseMetricBatch(text: String): Map<String, Pair<Long, Long>> = parseMetricEnvelope(text).metrics

    fun parseMetricEnvelope(text: String): MetricEnvelope {
        val obj = JSONObject(text)
        val metrics = obj.keys().asSequence().mapNotNull { id ->
            if (!DIRECT_ENTITY_ID.matches(id)) return@mapNotNull null
            val pair = obj.optJSONArray(id) ?: return@mapNotNull null
            id to (pair.optLong(0).coerceAtLeast(0) to pair.optLong(1).coerceAtLeast(0))
        }.toMap()
        val observer = obj.optJSONObject(OBSERVER_COST_KEY)?.let { cost ->
            BrowserObserverCosts(
                frames = cost.optLong("frames").coerceIn(0L, MAX_OBSERVER_COUNT),
                entities = cost.optLong("entities").coerceIn(0L, MAX_OBSERVER_COUNT),
                frameChars = cost.optLong("frame_chars").coerceIn(0L, MAX_OBSERVER_CHARS),
                parseMicros = cost.optLong("parse_us").coerceIn(0L, MAX_OBSERVER_MICROS),
                stringifyMicros = cost.optLong("stringify_us").coerceIn(0L, MAX_OBSERVER_MICROS),
                dropped = cost.optLong("dropped").coerceIn(0L, MAX_OBSERVER_COUNT),
                coalesced = cost.optLong("coalesced").coerceIn(0L, MAX_OBSERVER_COUNT),
            )
        }
        return MetricEnvelope(metrics, observer)
    }

    private const val OBSERVER_COST_KEY = "__ha_paneld_observer"
    private const val MAX_OBSERVER_COUNT = 50_000_000L
    private const val MAX_OBSERVER_CHARS = 1_000_000_000L
    private const val MAX_OBSERVER_MICROS = 300_000_000L
}
