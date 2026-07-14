package io.github.maxlyth.hapaneld.dashboard

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
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
import java.io.InputStreamReader
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
    @Volatile private var bootstrapBlockingIssues = 0
    @Volatile private var resetBootstrapPending = false
    @Volatile private var dynamicExpressionsJson = "[]"
    private var promotionWindowStart = 0L
    private var promotionsInWindow = 0

    fun start() {
        bootstrapBlockingIssues = runCatching {
            store.snapshot(instance(), dashboardPath()).blockingIssueCount
        }.getOrDefault(0)
        dynamicExpressionsJson = runCatching {
            encodeDynamicExpressions(EntityLearningProtocol.scanDashboard(
                store.dashboardConfigJson(instance(), dashboardPath()),
            ).dynamicExpressions)
        }.getOrDefault("[]")
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

    fun blockingIssueCount(): Int = bootstrapBlockingIssues

    fun setEnabled(enabled: Boolean): Boolean {
        val wasEnabled = config.dashboardEntityLearningEnabled
        val committed = config.applyBatch {
            config.setDashboardEntityLearningEnabled(enabled)
            // A new opt-in clears a stale activation latch. Synchronization may bootstrap an empty
            // installation narrowly, but it never silently replaces a stored manual list.
            if (!enabled || !wasEnabled) config.setDashboardEntityLearningApplied(false)
        }
        if (!committed) return false
        if (enabled) {
            syncNow("enable")
            // Always notify the renderer. A populated set rebuilds with its observer; an empty fresh
            // bootstrap tears down any pre-existing unfiltered WebView and waits on the native bootstrap
            // screen until synchronization commits the first narrow set.
            onFilterChanged()
        } else {
            resetBootstrapPending = false
            config.setDashboardEntityFilter(false, config.dashboardEntityFilterIds)
            store.markStatus(instance(), dashboardPath(), "disabled")
            onFilterChanged()
        }
        return true
    }

    /** Explicitly promote the policy-selected evidence set, primarily for existing manual filters. */
    fun activate(confirm: Boolean): String {
        require(config.dashboardEntityLearningEnabled) { "automatic learning is not enabled" }
        require(store.snapshot(instance(), dashboardPath()).blockingIssueCount == 0) {
            "automatic activation is blocked by dashboard configuration issues"
        }
        if (!confirm) return JSONObject().put("ok", false).put("confirmation_required", true).toString()
        val active = desiredIds(System.currentTimeMillis())
        require(active.isNotEmpty()) { "learned candidate set is empty" }
        val preview = subscriptionPreview(active)
        check(config.commitDashboardEntityLearningApplied(true)) { "failed to commit activation latch" }
        if (preview.streamChange) {
            if (!config.setDashboardEntityFilter(true, active)) {
                config.commitDashboardEntityLearningApplied(false)
                error("failed to commit learned entity set")
            }
            onFilterChanged()
        }
        resetBootstrapPending = false
        store.markStatus(instance(), dashboardPath(), "active")
        return JSONObject().put("ok", true).put("entity_count", active.size)
            .put("previous_count", preview.currentCount)
            .put("added_count", preview.additions).put("removed_count", preview.removals)
            .put("stream_changed", preview.streamChange)
            .put("automatic_updates_enabled", true)
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
        dynamicExpressionsJson = encodeDynamicExpressions(scan.dynamicExpressions)
        val expanded = expandTargets(config.haUrl, token, scan.targets)
        val catalogIds = states.asSequence().map { it.entityId }.toHashSet()
        val lint = DashboardConfigurationLint.analyze(ws.configJson, catalogIds, ws.metadata)
        val derived = (scan.entityIds + expanded + lint.safeEntityIds).filterTo(sortedSetOf()) { it in catalogIds }
        val now = System.currentTimeMillis()
        val apply = config.dashboardEntityLearningApplied
        val bootstrap = resetBootstrapPending || shouldBootstrapEntityLearning(
            learningEnabled = config.dashboardEntityLearningEnabled,
            applied = apply,
            configuredIds = config.dashboardEntityFilterIds,
        )
        val decision = automaticSyncDecision(
            learningEnabled = config.dashboardEntityLearningEnabled,
            applied = apply,
            configuredIds = config.dashboardEntityFilterIds,
            blockingIssues = lint.blocking,
            forceBootstrap = resetBootstrapPending,
        )
        store.commitSync(
            instance, path, states, ws.metadata, ws.configJson, derived, scan.unresolved,
            when (decision) {
                AutomaticSyncDecision.BLOCKED -> "blocked"
                AutomaticSyncDecision.APPLY -> "active"
                AutomaticSyncDecision.BOOTSTRAP -> "learning"
                AutomaticSyncDecision.OBSERVE -> "observing"
            },
            now,
            issues = lint.issues.map(DashboardConfigurationLint.Issue::toJson),
        )
        bootstrapBlockingIssues = lint.issues.count { it.blocking }
        applyStoredOverrides(instance, path)
        // A blocking dashboard rule invalidates the proposed set as a whole. Keep an existing safe
        // filter byte-for-byte, or keep a fresh renderer on its native diagnostic screen. Never apply
        // the bounded fragments of a partially unsafe dashboard and never fall back to an unfiltered
        // WebSocket while automatic learning is enabled.
        if (lint.blocking) return@withContext
        require(derived.isNotEmpty()) { "dashboard analysis found no visible entity dependencies" }
        val active = desiredIds(now, instance, path)
        require(active.isNotEmpty()) { "automatic entity set is empty" }
        if (bootstrap) check(config.commitDashboardEntityLearningApplied(true)) { "failed to commit bootstrap latch" }
        if (apply || bootstrap) {
            val changed = active != config.dashboardEntityFilterIds || !config.dashboardEntityFilterEnabled
            if (!config.setDashboardEntityFilter(true, active)) {
                if (bootstrap) config.commitDashboardEntityLearningApplied(false)
                error("failed to commit automatic entity set")
            }
            if (changed) onFilterChanged()
        }
        resetBootstrapPending = false
    }

    fun recordAccessBatch(text: String) {
        if (!config.dashboardEntityLearningEnabled) return
        val (accessed, missing) = runCatching { EntityLearningProtocol.parseAccessBatch(text) }.getOrElse { return }
        val instance = instance(); val path = dashboardPath(); val now = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            val knownAccessed = accessed.filterKeys { store.hasEntity(instance, it) }
            val knownMissing = missing.filterTo(mutableSetOf()) { store.hasEntity(instance, it) }
            store.recordAccess(instance, path, knownAccessed + knownMissing.associateWith { 1L }, now)
            if (knownMissing.isNotEmpty() && config.dashboardEntityLearningApplied && config.dashboardEntityAutoRuntime) queuePromotion()
        }
    }

    fun recordMetricBatch(text: String) {
        if (!config.dashboardEntityLearningEnabled) return
        val metrics = runCatching { EntityLearningProtocol.parseMetricBatch(text) }.getOrElse { return }
        scope.launch(Dispatchers.IO) { store.recordMetrics(instance(), dashboardPath(), metrics, System.currentTimeMillis()) }
    }

    private fun queuePromotion() {
        if (bootstrapBlockingIssues > 0) return
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
            val active = withContext(Dispatchers.IO) { desiredIds(now) }
            if (active != config.dashboardEntityFilterIds && active.isNotEmpty()) {
                promotionsInWindow++
                if (config.setDashboardEntityFilter(true, active)) onFilterChanged()
            }
        }
    }

    fun setOverride(entityId: String, override: String, force: Boolean): String {
        val id = EntityFilterProtocol.normalize(listOf(entityId)).single()
        if (override == "forced_exclude" && !force) {
            return JSONObject().put("ok", false).put("confirmation_required", true).put("entity_id", id).toString()
        }
        applyOverrides(listOf(id), override, force)
        return JSONObject().put("ok", true).put("entity_id", id).put("override", override).toString()
    }

    fun setOverrides(entityIds: List<String>, allCandidates: Boolean, override: String, force: Boolean): String {
        require(allCandidates || entityIds.isNotEmpty()) { "entity_ids or all_candidates required" }
        val ids = if (allCandidates) {
            val subscribed = config.dashboardEntityFilterIds.toSet()
            store.suggestedIds(instance(), dashboardPath()).filterNot(subscribed::contains)
        } else EntityFilterProtocol.normalize(entityIds)
        require(ids.isNotEmpty()) { "selected entity set is empty" }
        if (override == "forced_exclude" && !force) {
            return JSONObject().put("ok", false).put("confirmation_required", true).put("entity_count", ids.size).toString()
        }
        applyOverrides(ids, override, force)
        return JSONObject().put("ok", true).put("entity_count", ids.size).put("override", override).toString()
    }

    private fun applyOverrides(ids: List<String>, override: String, force: Boolean) {
        require(override in setOf("auto", "pinned", "forced_exclude")) { "invalid override" }
        require(override != "forced_exclude" || force) { "force confirmation required" }
        if (config.dashboardEntityLearningApplied && override == "forced_exclude") {
            val current = desiredIds(System.currentTimeMillis()).toSet()
            require((current - ids.toSet()).isNotEmpty()) { "cannot exclude the complete active subscription" }
        }
        val encoded = config.dashboardEntityOverrides.toMutableMap().apply {
            for (id in ids) if (override == "auto") remove(id) else put(id, override)
        }
        check(config.setDashboardEntityOverrides(encoded)) { "override commit failed" }
        store.setOverrides(instance(), dashboardPath(), ids, override)
        val active = desiredIds(System.currentTimeMillis())
        val changed = active != config.dashboardEntityFilterIds
        if (config.dashboardEntityLearningApplied && active.isNotEmpty()) config.setDashboardEntityFilter(true, active)
        if (config.dashboardEntityLearningApplied && changed) onFilterChanged()
    }

    /** Tester recovery/reset: discard derived dashboard evidence, never credentials or the HA catalog. */
    fun resetEvidence(confirm: Boolean): String {
        if (!confirm) return JSONObject().put("ok", false).put("confirmation_required", true).toString()
        check(syncJob?.isActive != true) { "synchronization is running" }
        check(config.setDashboardEntityOverrides(emptyMap())) { "failed to clear entity overrides" }
        check(config.commitDashboardEntityLearningApplied(false)) { "failed to clear activation latch" }
        if (config.dashboardEntityLearningEnabled) {
            // Preserve a known-good live set until the replacement scan succeeds. A blocking or failed
            // scan must never turn an explicit reset into an unfiltered renderer or a partial set.
            resetBootstrapPending = true
        }
        store.resetEvidence(instance(), dashboardPath())
        val started = if (config.dashboardEntityLearningEnabled) syncNow("reset") else false
        return JSONObject().put("ok", true).put("sync_started", started).toString()
    }

    private fun applyStoredOverrides(instance: String, path: String) {
        config.dashboardEntityOverrides.forEach { (id, override) -> store.setOverride(instance, path, id, override) }
    }

    /** Change which evidence sources may promote entities. Collection and review continue regardless. */
    fun setPromotionPolicy(staticRefs: Boolean, runtimeRefs: Boolean): String {
        val previousStatic = config.dashboardEntityAutoStatic
        val previousRuntime = config.dashboardEntityAutoRuntime
        check(config.setDashboardEntityAutoPolicy(staticRefs, runtimeRefs)) { "policy commit failed" }
        val active = desiredIds(System.currentTimeMillis())
        if (config.dashboardEntityLearningApplied && active.isEmpty()) {
            config.setDashboardEntityAutoPolicy(previousStatic, previousRuntime)
            error("policy would leave the live subscription empty; pin at least one entity first")
        }
        val changed = active != config.dashboardEntityFilterIds
        if (config.dashboardEntityLearningApplied && changed) {
            check(config.setDashboardEntityFilter(true, active)) { "failed to apply promotion policy" }
            onFilterChanged()
        }
        return JSONObject().put("ok", true).put("auto_static", staticRefs)
            .put("auto_runtime", runtimeRefs).put("entity_count", active.size).toString()
    }

    fun statusJson(): String {
        val s = store.snapshot(instance(), dashboardPath())
        bootstrapBlockingIssues = s.blockingIssueCount
        val suggestions = store.suggestedIds(instance(), dashboardPath())
        val candidates = suggestions.count { it !in config.dashboardEntityFilterIds.toSet() }
        val filtered = config.dashboardEntityFilterEnabled && config.dashboardEntityFilterIds.isNotEmpty()
        val held = shouldHoldRendererForEntityBootstrap(
            config.dashboardEntityLearningEnabled,
            config.dashboardEntityFilterEnabled,
            config.dashboardEntityFilterIds,
        )
        val desired = desiredIds(System.currentTimeMillis())
        val preview = subscriptionPreview(desired, s.catalogCount)
        return JSONObject()
            .put("requested_enabled", config.dashboardEntityLearningEnabled)
            .put("mode", if (config.dashboardEntityLearningEnabled) "automatic" else "manual")
            .put("state", when {
                !config.dashboardEntityLearningEnabled -> "disabled"
                s.blockingIssueCount > 0 -> "blocked"
                else -> s.state
            })
            .put("applied", config.dashboardEntityLearningApplied)
            .put("auto_static", config.dashboardEntityAutoStatic)
            .put("auto_runtime", config.dashboardEntityAutoRuntime)
            .put("last_sync_at", s.lastSyncAt).put("catalog_count", s.catalogCount)
            .put("active_count", config.dashboardEntityFilterIds.size)
            .put("candidate_count", candidates).put("suggested_count", candidates)
            .put("desired_count", desired.size)
            .put("pending_additions", if (s.blockingIssueCount > 0) 0 else preview.additions)
            .put("pending_removals", if (s.blockingIssueCount > 0) 0 else preview.removals)
            .put("stream_change_required", s.blockingIssueCount == 0 && preview.streamChange)
            .put("activation_required", !config.dashboardEntityLearningApplied)
            .put("apply_required", s.blockingIssueCount == 0 && (preview.streamChange || !config.dashboardEntityLearningApplied))
            .put("dashboard_issue_count", s.issueCount)
            .put("blocking_issue_count", s.blockingIssueCount)
            .put("automatic_activation_blocked", s.blockingIssueCount > 0)
            .put("stream_mode", when { held -> "held"; filtered -> "filtered"; else -> "unfiltered" })
            .put("stream_entity_count", when { held -> 0; filtered -> config.dashboardEntityFilterIds.size; else -> s.catalogCount })
            .put("stream_filter_hash", if (filtered) EntityFilterProtocol.hash(config.dashboardEntityFilterIds) else "")
            .put("unresolved_count", s.unresolvedCount)
            .put("error", s.error).put("db_bytes", s.dbBytes).put("sync_running", syncJob?.isActive == true)
            .toString()
    }

    fun issuesJson(): String {
        val s = store.snapshot(instance(), dashboardPath())
        return JSONObject()
            .put("items", JSONArray(store.issuesJson(instance(), dashboardPath())))
            .put("dashboard_issue_count", s.issueCount)
            .put("blocking_issue_count", s.blockingIssueCount)
            .put("dynamic_expressions", JSONArray(dynamicExpressionsJson))
            .toString()
    }

    private fun encodeDynamicExpressions(expressions: List<EntityLearningProtocol.DynamicExpression>): String =
        JSONArray(expressions.take(MAX_DYNAMIC_EXPRESSIONS).map(EntityLearningProtocol.DynamicExpression::toJson)).toString()

    fun entitiesJson(query: String, filter: String, limit: Int, offset: Int): String {
        val subscribed = filter == "subscribed"
        val review = filter == "review"
        val filtered = config.dashboardEntityFilterEnabled && config.dashboardEntityFilterIds.isNotEmpty()
        val held = shouldHoldRendererForEntityBootstrap(
            config.dashboardEntityLearningEnabled,
            config.dashboardEntityFilterEnabled,
            config.dashboardEntityFilterIds,
        )
        val effectiveFilter = when {
            filter == "candidate" && query.isBlank() -> "candidate"
            filter == "candidate" -> "unpinned"
            subscribed -> "all"
            else -> filter
        }
        val currentIds = if (filtered) config.dashboardEntityFilterIds.toSet() else emptySet()
        val json = JSONObject(store.entitiesJson(
            instance(), dashboardPath(), query, effectiveFilter, limit, offset,
            includeIds = when {
                (subscribed || review) && held -> emptySet()
                (subscribed || review) && filtered -> config.dashboardEntityFilterIds.toSet()
                else -> null
            },
            excludeIds = if (filter == "candidate") currentIds else emptySet(),
        ))
        if (subscribed) {
            val catalogCount = store.snapshot(instance(), dashboardPath()).catalogCount
            json.put("stream_mode", when { held -> "held"; filtered -> "filtered"; else -> "unfiltered" })
                .put("configured_count", if (filtered) config.dashboardEntityFilterIds.size else 0)
                .put("catalog_present_count", when { held -> 0; filtered -> json.optInt("total"); else -> catalogCount })
        }
        return json.toString()
    }

    fun exportJson(): String = store.exportJson(instance(), dashboardPath())

    private fun desiredIds(
        now: Long,
        instance: String = instance(),
        path: String = dashboardPath(),
    ): List<String> = store.activeIds(
        instance, path, now,
        includeStatic = config.dashboardEntityAutoStatic,
        includeRuntime = config.dashboardEntityAutoRuntime,
    )

    private fun subscriptionPreview(
        desired: List<String>,
        catalogCount: Int = store.snapshot(instance(), dashboardPath()).catalogCount,
    ): EntitySubscriptionPreview {
        val filtered = config.dashboardEntityFilterEnabled && config.dashboardEntityFilterIds.isNotEmpty()
        return previewEntitySubscription(
            filtered = filtered,
            currentIds = config.dashboardEntityFilterIds,
            catalogCount = catalogCount,
            desiredIds = desired,
        )
    }

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
            // /api/states includes every attribute payload. The learner only needs entity_id/state,
            // so stream over the response and skip attributes instead of hydrating a multi-megabyte
            // JSONArray (and then serializing each attributes object again for SQLite).
            return JsonReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { reader ->
                buildList {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        var entityId = ""
                        var state = ""
                        reader.beginObject()
                        while (reader.hasNext()) when (reader.nextName()) {
                            "entity_id" -> entityId = reader.readScalar()
                            "state" -> state = reader.readScalar()
                            else -> reader.skipValue()
                        }
                        reader.endObject()
                        if (entityId.isNotBlank()) add(EntityCatalogStore.StateRow(entityId, state))
                    }
                    reader.endArray()
                }
            }
        } finally { c.disconnect() }
    }

    private fun JsonReader.readScalar(): String = when (peek()) {
        JsonToken.NULL -> { nextNull(); "" }
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        else -> { skipValue(); "" }
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
            // Floor and label selectors inherit through entity -> device -> area in Home Assistant.
            // list_for_display is intentionally compact and does not carry that complete ancestry, so
            // enrich its rows before linting; otherwise a floor selector could incorrectly look empty
            // and be accepted as a safe zero-entity subscription.
            val areas = runCatching { request(JSONObject().put("type", "config/area_registry/list")) }.getOrNull()
                ?.optJSONArray("result") ?: JSONArray()
            val devices = runCatching { request(JSONObject().put("type", "config/device_registry/list")) }.getOrNull()
                ?.optJSONArray("result") ?: JSONArray()
            val areaRows = mutableMapOf<String, JSONObject>()
            for (i in 0 until areas.length()) areas.optJSONObject(i)?.let { row ->
                row.optString("area_id").ifBlank { row.optString("id") }.takeIf(String::isNotBlank)?.let { areaRows[it] = row }
            }
            val deviceRows = mutableMapOf<String, JSONObject>()
            for (i in 0 until devices.length()) devices.optJSONObject(i)?.let { row ->
                row.optString("id").ifBlank { row.optString("device_id") }.takeIf(String::isNotBlank)?.let { deviceRows[it] = row }
            }
            fun labels(row: JSONObject?, vararg keys: String): Set<String> = keys.flatMapTo(sortedSetOf()) { key ->
                when (val value = row?.opt(key)) {
                    is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }
                    is String -> listOf(value).filter(String::isNotBlank)
                    else -> emptyList()
                }
            }
            metadata.replaceAll { _, raw ->
                val entity = JSONObject(raw)
                val device = deviceRows[entity.optString("di").ifBlank { entity.optString("device_id") }]
                val areaId = entity.optString("ai").ifBlank { entity.optString("area_id") }
                    .ifBlank { device?.optString("area_id").orEmpty() }
                val area = areaRows[areaId]
                if (areaId.isNotBlank()) entity.put("ai", areaId)
                area?.optString("floor_id")?.takeIf(String::isNotBlank)?.let { entity.put("fi", it) }
                val inheritedLabels = labels(entity, "lb", "labels", "label_ids") +
                    labels(device, "labels", "label_ids") + labels(area, "labels", "label_ids")
                if (inheritedLabels.isNotEmpty()) entity.put("lb", JSONArray(inheritedLabels.sorted()))
                entity.toString()
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
                // Dashboard configuration is extensible and third-party cards sometimes put selector
                // syntax in target-shaped fields. A single target rejected by HA must not discard the
                // complete catalogue/static scan; unresolved dependencies can still be learned at runtime.
                val response = runCatching {
                    request(JSONObject().put("type", "extract_from_target").put("target", JSONObject(target)).put("expand_group", true))
                }.getOrNull() ?: continue
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
        private const val MAX_DYNAMIC_EXPRESSIONS = 128

        private fun normalizedOrigin(raw: String): String {
            val u = URI(raw.trim().trimEnd('/'))
            return URI(u.scheme.lowercase(), null, u.host.lowercase(), u.port, null, null, null).toString().trimEnd('/')
        }
    }
}

/** An empty installation starts narrow; any stored manual list is preserved for explicit review. */
internal fun shouldBootstrapEntityLearning(
    learningEnabled: Boolean,
    applied: Boolean,
    configuredIds: Collection<String>,
): Boolean = learningEnabled && !applied && configuredIds.isEmpty()

internal enum class AutomaticSyncDecision { BLOCKED, OBSERVE, APPLY, BOOTSTRAP }

/** Pure safety policy: a blocking scan is atomic rejection, and disabled learning is scan-only. */
internal fun automaticSyncDecision(
    learningEnabled: Boolean,
    applied: Boolean,
    configuredIds: Collection<String>,
    blockingIssues: Boolean,
    forceBootstrap: Boolean = false,
): AutomaticSyncDecision = when {
    blockingIssues -> AutomaticSyncDecision.BLOCKED
    !learningEnabled -> AutomaticSyncDecision.OBSERVE
    applied -> AutomaticSyncDecision.APPLY
    forceBootstrap || configuredIds.isEmpty() -> AutomaticSyncDecision.BOOTSTRAP
    else -> AutomaticSyncDecision.OBSERVE
}

internal data class EntitySubscriptionPreview(
    val currentCount: Int,
    val additions: Int,
    val removals: Int,
    val streamChange: Boolean,
)

/** Pure current→policy preview used by both status and the confirmed apply result. */
internal fun previewEntitySubscription(
    filtered: Boolean,
    currentIds: Collection<String>,
    catalogCount: Int,
    desiredIds: Collection<String>,
): EntitySubscriptionPreview {
    val desired = desiredIds.toSet()
    if (!filtered) return EntitySubscriptionPreview(
        currentCount = catalogCount,
        additions = 0,
        removals = (catalogCount - desired.size).coerceAtLeast(0),
        streamChange = desired.isNotEmpty(),
    )
    val current = currentIds.toSet()
    return EntitySubscriptionPreview(
        currentCount = current.size,
        additions = (desired - current).size,
        removals = (current - desired).size,
        streamChange = current != desired,
    )
}

/** Process-local rendezvous between the service-owned learner and DashboardActivity's JS bridge. */
object EntityLearningRuntime {
    @Volatile private var current: EntityLearningManager? = null
    fun attach(manager: EntityLearningManager) { current = manager }
    fun detach(manager: EntityLearningManager) { if (current === manager) current = null }
    fun recordAccessBatch(text: String) { current?.recordAccessBatch(text) }
    fun recordMetricBatch(text: String) { current?.recordMetricBatch(text) }
    fun blockingIssueCount(): Int = current?.blockingIssueCount() ?: 0
}
