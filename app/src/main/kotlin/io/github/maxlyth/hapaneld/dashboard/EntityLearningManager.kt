package io.github.maxlyth.hapaneld.dashboard

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.DashboardAuth
import io.github.maxlyth.hapaneld.control.BuiltinDashboard
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Owns catalog synchronization, automatic set promotion and the HTTP/UI query surface. */
class EntityLearningManager(
    context: Context,
    private val config: Config,
    private val scope: CoroutineScope,
    private val onFilterChanged: () -> Unit,
) {
    private val store = EntityCatalogStore(context.applicationContext)
    private val syncMutex = Mutex()
    private val promotionQueued = AtomicBoolean(false)
    @Volatile private var syncJob: Job? = null
    private var promotionWindowStart = 0L
    private var promotionsInWindow = 0

    fun start() {
        scope.launch {
            while (true) {
                delay(HOURLY_CHECK_MS)
                if (!config.dashboardEntityLearningEnabled || BuiltinDashboard.screenAwakeNow) continue
                val s = store.snapshot(instance(), dashboardPath())
                if (System.currentTimeMillis() - s.lastSyncAt >= DAILY_SYNC_MS) syncNow("daily")
            }
        }
        if (config.dashboardEntityLearningEnabled && store.snapshot(instance(), dashboardPath()).lastSyncAt == 0L) {
            syncNow("startup")
        }
    }

    fun close() { syncJob?.cancel(); store.close() }

    fun setEnabled(enabled: Boolean): Boolean {
        val committed = config.applyBatch {
            config.setDashboardEntityLearningEnabled(enabled)
            // Every opt-in begins in observation mode. A stale candidate must never silently replace a
            // known-good manual list merely because learning was toggled off and back on.
            config.setDashboardEntityLearningApplied(false)
        }
        if (!committed) return false
        if (enabled) {
            syncNow("enable")
            // Rebuild without changing the current allow-list so the document-start access observer is
            // present for the entire warm-up period.
            onFilterChanged()
        } else {
            config.setDashboardEntityFilter(false, config.dashboardEntityFilterIds)
            store.markStatus(instance(), dashboardPath(), "disabled")
            onFilterChanged()
        }
        return true
    }

    /** Promote the accumulated candidate set only after an explicit operator confirmation. */
    fun activate(confirm: Boolean): String {
        require(config.dashboardEntityLearningEnabled) { "automatic learning is not enabled" }
        if (!confirm) return JSONObject().put("ok", false).put("confirmation_required", true).toString()
        val active = store.activeIds(instance(), dashboardPath(), System.currentTimeMillis())
        require(active.isNotEmpty()) { "learned candidate set is empty" }
        check(config.commitDashboardEntityLearningApplied(true)) { "failed to commit activation latch" }
        if (!config.setDashboardEntityFilter(true, active)) {
            config.commitDashboardEntityLearningApplied(false)
            error("failed to commit learned entity set")
        }
        store.markStatus(instance(), dashboardPath(), "active")
        onFilterChanged()
        return JSONObject().put("ok", true).put("entity_count", active.size)
            .put("filter_hash", EntityFilterProtocol.hash(active)).toString()
    }

    fun syncNow(reason: String = "manual"): Boolean = synchronized(this) {
        if (syncJob?.isActive == true) return@synchronized false
        syncJob = scope.launch {
            syncMutex.withLock {
                val instance = instance(); val path = dashboardPath()
                runCatching { synchronize(instance, path) }
                    .onFailure {
                        Log.w(TAG, "entity catalog sync failed ($reason): ${it.message}")
                        store.markStatus(instance, path, "degraded", it.message ?: it.javaClass.simpleName)
                    }
            }
        }
        true
    }

    private suspend fun synchronize(instance: String, path: String) = withContext(Dispatchers.IO) {
        require(config.haUrl.isNotBlank()) { "Home Assistant URL is not configured" }
        val auth = DashboardAuth.forConfig(config)
        val token = auth.session?.accessToken ?: error(if (auth.rejected) "Home Assistant credential rejected" else "Home Assistant token unavailable")
        val states = fetchStates(config.haUrl, token)
        require(states.isNotEmpty()) { "Home Assistant returned no visible states" }
        val ws = fetchDashboardAndRegistry(config.haUrl, token, EntityLearningProtocol.dashboardUrlPath(path))
        val scan = EntityLearningProtocol.scanDashboard(ws.configJson)
        val expanded = expandTargets(config.haUrl, token, scan.targets)
        val catalogIds = states.asSequence().map { it.entityId }.toHashSet()
        val selected = EntityLearningProtocol.resolveSelectors(scan.selectors, catalogIds, ws.metadata)
        val derived = (scan.entityIds + expanded + selected).filterTo(sortedSetOf()) { it in catalogIds }
        require(derived.isNotEmpty()) { "dashboard analysis found no visible entity dependencies" }
        val now = System.currentTimeMillis()
        val apply = config.dashboardEntityLearningApplied
        store.commitSync(instance, path, states, ws.metadata, ws.configJson, derived, scan.unresolved, if (apply) "active" else "observing", now)
        applyStoredOverrides(instance, path)
        val active = store.activeIds(instance, path, now)
        require(active.isNotEmpty()) { "automatic entity set is empty" }
        if (apply) {
            val changed = active != config.dashboardEntityFilterIds || !config.dashboardEntityFilterEnabled
            check(config.setDashboardEntityFilter(true, active)) { "failed to commit automatic entity set" }
            if (changed) onFilterChanged()
        }
    }

    fun recordAccessBatch(text: String) {
        if (!config.dashboardEntityLearningEnabled) return
        val (accessed, missing) = runCatching { EntityLearningProtocol.parseAccessBatch(text) }.getOrElse { return }
        val instance = instance(); val path = dashboardPath(); val now = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            val knownAccessed = accessed.filterKeys { store.hasEntity(instance, it) }
            val knownMissing = missing.filterTo(mutableSetOf()) { store.hasEntity(instance, it) }
            store.recordAccess(instance, path, knownAccessed + knownMissing.associateWith { 1L }, now)
            if (knownMissing.isNotEmpty() && config.dashboardEntityLearningApplied) queuePromotion()
        }
    }

    fun recordMetricBatch(text: String) {
        if (!config.dashboardEntityLearningEnabled) return
        val metrics = runCatching { EntityLearningProtocol.parseMetricBatch(text) }.getOrElse { return }
        scope.launch(Dispatchers.IO) { store.recordMetrics(instance(), dashboardPath(), metrics, System.currentTimeMillis()) }
    }

    private fun queuePromotion() {
        if (!promotionQueued.compareAndSet(false, true)) return
        scope.launch {
            delay(PROMOTION_DEBOUNCE_MS)
            promotionQueued.set(false)
            val now = System.currentTimeMillis()
            if (now - promotionWindowStart > PROMOTION_WINDOW_MS) {
                promotionWindowStart = now; promotionsInWindow = 0
            }
            if (promotionsInWindow >= MAX_PROMOTIONS) {
                store.markStatus(instance(), dashboardPath(), "degraded", "runtime dependencies queued; synchronize manually")
                return@launch
            }
            val active = withContext(Dispatchers.IO) { store.activeIds(instance(), dashboardPath(), now) }
            if (active != config.dashboardEntityFilterIds && active.isNotEmpty()) {
                promotionsInWindow++
                if (config.setDashboardEntityFilter(true, active)) onFilterChanged()
            }
        }
    }

    fun setOverride(entityId: String, override: String, force: Boolean): String {
        val id = EntityFilterProtocol.normalize(listOf(entityId)).single()
        require(override in setOf("auto", "pinned", "forced_exclude")) { "invalid override" }
        if (override == "forced_exclude" && !force) {
            return JSONObject().put("ok", false).put("confirmation_required", true).put("entity_id", id).toString()
        }
        val encoded = config.dashboardEntityOverrides.toMutableMap().apply {
            if (override == "auto") remove(id) else put(id, override)
        }
        check(config.setDashboardEntityOverrides(encoded)) { "override commit failed" }
        store.setOverride(instance(), dashboardPath(), id, override)
        val active = store.activeIds(instance(), dashboardPath(), System.currentTimeMillis())
        val changed = active != config.dashboardEntityFilterIds
        if (config.dashboardEntityLearningApplied && active.isNotEmpty()) config.setDashboardEntityFilter(true, active)
        if (config.dashboardEntityLearningApplied && changed) onFilterChanged()
        return JSONObject().put("ok", true).put("entity_id", id).put("override", override).toString()
    }

    /** Tester recovery/reset: discard derived dashboard evidence, never credentials or the HA catalog. */
    fun resetEvidence(confirm: Boolean): String {
        if (!confirm) return JSONObject().put("ok", false).put("confirmation_required", true).toString()
        check(syncJob?.isActive != true) { "synchronization is running" }
        check(config.setDashboardEntityOverrides(emptyMap())) { "failed to clear entity overrides" }
        check(config.commitDashboardEntityLearningApplied(false)) { "failed to clear activation latch" }
        store.resetEvidence(instance(), dashboardPath())
        val started = if (config.dashboardEntityLearningEnabled) syncNow("reset") else false
        return JSONObject().put("ok", true).put("sync_started", started).toString()
    }

    private fun applyStoredOverrides(instance: String, path: String) {
        config.dashboardEntityOverrides.forEach { (id, override) -> store.setOverride(instance, path, id, override) }
    }

    fun statusJson(): String {
        val s = store.snapshot(instance(), dashboardPath())
        val candidates = store.activeIds(instance(), dashboardPath(), System.currentTimeMillis())
        val filtered = config.dashboardEntityFilterEnabled && config.dashboardEntityFilterIds.isNotEmpty()
        return JSONObject()
            .put("requested_enabled", config.dashboardEntityLearningEnabled)
            .put("mode", if (config.dashboardEntityLearningEnabled) "automatic" else "manual")
            .put("state", if (!config.dashboardEntityLearningEnabled) "disabled" else s.state)
            .put("applied", config.dashboardEntityLearningApplied)
            .put("last_sync_at", s.lastSyncAt).put("catalog_count", s.catalogCount)
            .put("active_count", config.dashboardEntityFilterIds.size).put("candidate_count", candidates.size)
            .put("stream_mode", if (filtered) "filtered" else "unfiltered")
            .put("stream_entity_count", if (filtered) config.dashboardEntityFilterIds.size else s.catalogCount)
            .put("unresolved_count", s.unresolvedCount)
            .put("error", s.error).put("db_bytes", s.dbBytes).put("sync_running", syncJob?.isActive == true)
            .toString()
    }

    fun entitiesJson(query: String, filter: String, limit: Int, offset: Int): String {
        val subscribed = filter == "subscribed"
        val review = filter == "review"
        val filtered = config.dashboardEntityFilterEnabled && config.dashboardEntityFilterIds.isNotEmpty()
        val effectiveFilter = when {
            filter == "candidate" -> "active"
            subscribed -> "all"
            else -> filter
        }
        val json = JSONObject(store.entitiesJson(
            instance(), dashboardPath(), query, effectiveFilter, limit, offset,
            includeIds = if ((subscribed || review) && filtered) config.dashboardEntityFilterIds.toSet() else null,
        ))
        if (subscribed) {
            val catalogCount = store.snapshot(instance(), dashboardPath()).catalogCount
            json.put("stream_mode", if (filtered) "filtered" else "unfiltered")
                .put("configured_count", if (filtered) config.dashboardEntityFilterIds.size else 0)
                .put("catalog_present_count", if (filtered) json.optInt("total") else catalogCount)
        }
        return json.toString()
    }

    fun exportJson(): String = store.exportJson(instance(), dashboardPath())

    private fun instance(): String = EntityLearningProtocol.hash(normalizedOrigin(config.haUrl))
    private fun dashboardPath(): String = config.homeDashboard.ifBlank { "/" }

    private data class WsSnapshot(val configJson: String, val metadata: Map<String, String>)

    private fun fetchStates(base: String, token: String): List<EntityCatalogStore.StateRow> {
        val c = (URL(base.trimEnd('/') + "/api/states").openConnection() as HttpURLConnection).apply {
            connectTimeout = HTTP_TIMEOUT_MS; readTimeout = HTTP_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token"); setRequestProperty("Accept", "application/json")
        }
        try {
            if (c.responseCode !in 200..299) error("states request failed: HTTP ${c.responseCode}")
            val array = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("entity_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                EntityCatalogStore.StateRow(id, o.optString("state"), o.optJSONObject("attributes")?.toString() ?: "{}")
            }
        } finally { c.disconnect() }
    }

    private suspend fun fetchDashboardAndRegistry(base: String, token: String, urlPath: String): WsSnapshot {
        var configJson: String? = null
        val metadata = mutableMapOf<String, String>()
        withHaSocket(base, token) { request ->
            val registry = request(JSONObject().put("type", "config/entity_registry/list_for_display"))
            val root = registry.optJSONObject("result")
            val entries = root?.optJSONArray("entities") ?: registry.optJSONArray("result") ?: JSONArray()
            for (i in 0 until entries.length()) entries.optJSONObject(i)?.let { e ->
                e.optString("ei").takeIf { it.isNotBlank() }?.let { metadata[it] = e.toString() }
            }
            val command = JSONObject().put("type", "lovelace/config")
            if (urlPath.isNotBlank()) command.put("url_path", urlPath)
            val response = request(command)
            val result = response.opt("result")
            configJson = when (result) {
                is JSONObject -> result.toString()
                else -> null
            }
        }
        return WsSnapshot(configJson ?: error("dashboard configuration unavailable"), metadata)
    }

    private suspend fun expandTargets(base: String, token: String, targets: List<String>): Set<String> {
        if (targets.isEmpty()) return emptySet()
        val out = linkedSetOf<String>()
        withHaSocket(base, token) { request ->
            for (target in targets.take(MAX_TARGETS)) {
                val response = request(JSONObject().put("type", "extract_from_target").put("target", JSONObject(target)).put("expand_group", true))
                val entities = response.optJSONObject("result")?.optJSONArray("referenced_entities") ?: continue
                for (i in 0 until entities.length()) entities.optString(i).takeIf { it.isNotBlank() }?.let(out::add)
            }
        }
        return out
    }

    private suspend fun withHaSocket(
        base: String,
        token: String,
        block: suspend (suspend (JSONObject) -> JSONObject) -> Unit,
    ) {
        val ws = normalizedOrigin(base).replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/websocket"
        val client = HttpClient(CIO) { install(WebSockets) { maxFrameSize = MAX_WS_FRAME } }
        try {
            client.webSocket(ws) {
                (incoming.receive() as? Frame.Text)?.readText()
                send(Frame.Text(JSONObject().put("type", "auth").put("access_token", token).toString()))
                val auth = (incoming.receive() as? Frame.Text)?.readText().orEmpty()
                require(auth.contains("\"type\":\"auth_ok\"")) { "Home Assistant WebSocket authentication failed" }
                var id = 0
                suspend fun request(command: JSONObject): JSONObject {
                    val current = ++id; command.put("id", current); send(Frame.Text(command.toString()))
                    repeat(MAX_RESPONSE_FRAMES) {
                        val frame = incoming.receive() as? Frame.Text ?: return@repeat
                        val obj = JSONObject(frame.readText())
                        if (obj.optInt("id") == current && obj.optString("type") == "result") {
                            require(obj.optBoolean("success")) { obj.optJSONObject("error")?.optString("message") ?: "HA command failed" }
                            return obj
                        }
                    }
                    error("Home Assistant command timed out")
                }
                block(::request)
            }
        } finally { client.close() }
    }

    companion object {
        private const val TAG = "EntityLearning"
        private const val HTTP_TIMEOUT_MS = 30_000
        private const val MAX_RESPONSE_FRAMES = 32
        private const val MAX_TARGETS = 500
        private const val MAX_WS_FRAME = 32L * 1024 * 1024
        private const val HOURLY_CHECK_MS = 60L * 60_000
        private const val DAILY_SYNC_MS = 24L * HOURLY_CHECK_MS
        private const val PROMOTION_DEBOUNCE_MS = 2_000L
        private const val PROMOTION_WINDOW_MS = 10L * 60_000
        private const val MAX_PROMOTIONS = 2

        private fun normalizedOrigin(raw: String): String {
            val u = URI(raw.trim().trimEnd('/'))
            return URI(u.scheme.lowercase(), null, u.host.lowercase(), u.port, null, null, null).toString().trimEnd('/')
        }
    }
}

/** Process-local rendezvous between the service-owned learner and DashboardActivity's JS bridge. */
object EntityLearningRuntime {
    @Volatile private var current: EntityLearningManager? = null
    fun attach(manager: EntityLearningManager) { current = manager }
    fun detach(manager: EntityLearningManager) { if (current === manager) current = null }
    fun recordAccessBatch(text: String) { current?.recordAccessBatch(text) }
    fun recordMetricBatch(text: String) { current?.recordMetricBatch(text) }
}
