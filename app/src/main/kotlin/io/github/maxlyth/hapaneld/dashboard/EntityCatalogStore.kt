package io.github.maxlyth.hapaneld.dashboard

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteException
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.Writer

/** Bounded, derived entity/catalog evidence. Credentials are never stored here. */
class EntityCatalogStore(context: Context) : SQLiteOpenHelper(context, databaseName(context), null, VERSION) {
    private val maintenanceGate = MaintenanceIntervalGate(MAINTENANCE_INTERVAL_MS)
    private val performanceMaintenanceGate = MaintenanceIntervalGate(PERFORMANCE_MAINTENANCE_INTERVAL_MS)
    private val databaseBytesCacheLock = Any()
    private var databaseBytesCachedAt = Long.MIN_VALUE
    private var databaseBytesCachedValue = 0L

    init {
        // Use the helper-level API so WAL is enabled before the database is opened. Calling
        // SQLiteDatabase.enableWriteAheadLogging() from onConfigure is not supported consistently on
        // the Android 8-era SQLite builds used by several target panels.
        setWriteAheadLoggingEnabled(true)
    }

    data class StateRow(val entityId: String, val state: String, val friendlyName: String = "")
    data class Snapshot(
        val state: String,
        val lastSyncAt: Long,
        val catalogCount: Int,
        val activeCount: Int,
        val unresolvedCount: Int,
        val issueCount: Int,
        val blockingIssueCount: Int,
        val ignoredIssueCount: Int,
        val error: String,
        val dbBytes: Long,
    )

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE entity(
            instance TEXT NOT NULL, entity_id TEXT NOT NULL, state TEXT NOT NULL DEFAULT '',
            attributes_json TEXT NOT NULL DEFAULT '{}', metadata_json TEXT NOT NULL DEFAULT '{}',
            first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, missing_streak INTEGER NOT NULL DEFAULT 0,
            tombstone_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(instance,entity_id))""")
        db.execSQL("""CREATE TABLE dashboard(
            instance TEXT NOT NULL, path TEXT NOT NULL, config_hash TEXT NOT NULL DEFAULT '',
            config_json TEXT NOT NULL DEFAULT '{}', status TEXT NOT NULL DEFAULT 'disabled',
            last_sync INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '',
            unresolved_json TEXT NOT NULL DEFAULT '[]', sync_generation INTEGER NOT NULL DEFAULT 0,
            issues_json TEXT NOT NULL DEFAULT '[]',
            PRIMARY KEY(instance,path))""")
        db.execSQL("""CREATE TABLE membership(
            instance TEXT NOT NULL, path TEXT NOT NULL, entity_id TEXT NOT NULL,
            static_ref INTEGER NOT NULL DEFAULT 0, runtime_ref INTEGER NOT NULL DEFAULT 0,
            pinned INTEGER NOT NULL DEFAULT 0, excluded INTEGER NOT NULL DEFAULT 0,
            reasons TEXT NOT NULL DEFAULT '', first_access INTEGER NOT NULL DEFAULT 0,
            last_access INTEGER NOT NULL DEFAULT 0, access_count INTEGER NOT NULL DEFAULT 0,
            update_count INTEGER NOT NULL DEFAULT 0, update_bytes INTEGER NOT NULL DEFAULT 0,
            rate_window_start INTEGER NOT NULL DEFAULT 0, rate_update_bytes INTEGER NOT NULL DEFAULT 0,
            last_update_at INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(instance,path,entity_id))""")
        db.execSQL("""CREATE TABLE minute_rollup(
            instance TEXT NOT NULL, path TEXT NOT NULL, entity_id TEXT NOT NULL, minute INTEGER NOT NULL,
            access_count INTEGER NOT NULL DEFAULT 0, update_count INTEGER NOT NULL DEFAULT 0,
            update_bytes INTEGER NOT NULL DEFAULT 0, span_start INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(instance,path,entity_id,minute))""")
        db.execSQL("CREATE INDEX entity_missing ON entity(instance,missing_streak)")
        db.execSQL("CREATE INDEX membership_load ON membership(instance,path,update_bytes DESC)")
        db.execSQL("CREATE INDEX minute_rollup_age ON minute_rollup(instance,path,minute)")
        db.execSQL("""CREATE TABLE dashboard_issue_ignore(
            instance TEXT NOT NULL, path TEXT NOT NULL, fingerprint TEXT NOT NULL, ignored_at INTEGER NOT NULL,
            PRIMARY KEY(instance,path,fingerprint),
            FOREIGN KEY(instance,path) REFERENCES dashboard(instance,path) ON DELETE CASCADE)""")
        db.execSQL(PERFORMANCE_HISTORY_TABLE_SQL)
        db.execSQL("CREATE INDEX dashboard_performance_age ON dashboard_performance(minute)")
        db.execSQL(APP_STATE_REVISION_TABLE_SQL)
        db.execSQL(APP_STATE_NAMESPACE_TABLE_SQL)
        db.execSQL(APP_STATE_TABLE_SQL)
        db.execSQL("CREATE INDEX app_state_updated ON app_state(namespace,updated_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for (step in EntityCatalogSchema.plan(oldVersion, newVersion)) {
            for (sql in step.sql) db.execSQL(sql)
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("entity catalog downgrade is unsupported ($oldVersion -> $newVersion)")
    }

    fun markStatus(instance: String, path: String, status: String, error: String = "") {
        writableDatabase.execSQL("INSERT OR IGNORE INTO dashboard(instance,path) VALUES(?,?)", arrayOf(instance, path))
        writableDatabase.execSQL("UPDATE dashboard SET status=?,error=? WHERE instance=? AND path=?", arrayOf(status, error.take(500), instance, path))
    }

    /** Commit only a complete successful HA snapshot; failures never age current rows. */
    fun commitSync(
        instance: String,
        path: String,
        states: List<StateRow>,
        metadata: Map<String, String>,
        configJson: String,
        derived: Set<String>,
        unresolved: List<String>,
        status: String,
        now: Long,
        issues: List<JSONObject> = emptyList(),
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE entity SET missing_streak=missing_streak+1 WHERE instance=?", arrayOf(instance))
            // The catalogue UI and promotion policy never read state attributes. Persisting them copied
            // the full /api/states payload into SQLite and made attribute-heavy installations spend
            // minutes allocating JSON strings and writing megabytes. A compiled upsert keeps the useful
            // state/registry projection and preserves first_seen in one statement per entity.
            val upsert = db.compileStatement(
                """INSERT OR REPLACE INTO entity(instance,entity_id,state,attributes_json,metadata_json,
                   first_seen,last_seen,missing_streak,tombstone_at)
                   VALUES(?,?,?,'{}',?,coalesce((SELECT first_seen FROM entity WHERE instance=? AND entity_id=?),?),?,0,0)""",
            )
            try {
                for (row in states) {
                    upsert.clearBindings()
                    upsert.bindString(1, instance)
                    upsert.bindString(2, row.entityId)
                    upsert.bindString(3, row.state)
                    upsert.bindString(4, metadata[row.entityId] ?: "{}")
                    upsert.bindString(5, instance)
                    upsert.bindString(6, row.entityId)
                    upsert.bindLong(7, now)
                    upsert.bindLong(8, now)
                    upsert.executeInsert()
                }
            } finally { upsert.close() }
            db.execSQL("UPDATE entity SET tombstone_at=? WHERE instance=? AND missing_streak>=3 AND tombstone_at=0", arrayOf(now, instance))
            db.execSQL("UPDATE membership SET static_ref=0 WHERE instance=? AND path=?", arrayOf(instance, path))
            for (id in derived) {
                db.execSQL("INSERT OR IGNORE INTO membership(instance,path,entity_id) VALUES(?,?,?)", arrayOf(instance, path, id))
                db.execSQL(
                    "UPDATE membership SET static_ref=1,reasons=CASE WHEN instr(reasons,'dashboard')=0 THEN trim(reasons||',dashboard',',') ELSE reasons END WHERE instance=? AND path=? AND entity_id=?",
                    arrayOf(instance, path, id),
                )
            }
            db.execSQL("INSERT OR IGNORE INTO dashboard(instance,path) VALUES(?,?)", arrayOf(instance, path))
            db.execSQL(
                """UPDATE dashboard SET config_hash=?,config_json=?,status=?,last_sync=?,error='',
                   unresolved_json=?,issues_json=?,sync_generation=sync_generation+1 WHERE instance=? AND path=?""",
                arrayOf(
                    EntityLearningProtocol.hash(EntityLearningProtocol.canonical(JSONObject(configJson))),
                    configJson,
                    status,
                    now,
                    JSONArray(unresolved).toString(),
                    EntityCatalogIssuePersistence.boundedJson(issues),
                    instance,
                    path,
                ),
            )
            val currentFingerprints = issues.mapNotNull { issue ->
                issue.optString("fingerprint").takeIf(FINGERPRINT::matches)
            }.toSet()
            val staleIgnores = db.rawQuery(
                "SELECT fingerprint FROM dashboard_issue_ignore WHERE instance=? AND path=?",
                arrayOf(instance, path),
            ).use { cursor -> buildList {
                while (cursor.moveToNext()) cursor.getString(0).takeIf { it !in currentFingerprints }?.let(::add)
            } }
            staleIgnores.forEach { fingerprint ->
                db.delete(
                    "dashboard_issue_ignore", "instance=? AND path=? AND fingerprint=?",
                    arrayOf(instance, path, fingerprint),
                )
            }
            val cutoff = now - TOMBSTONE_RETENTION_MS
            db.execSQL("DELETE FROM entity WHERE instance=? AND tombstone_at>0 AND tombstone_at<?", arrayOf(instance, cutoff))
            db.execSQL("DELETE FROM minute_rollup WHERE minute<?", arrayOf((now - DAY_MS) / MINUTE_MS))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        maintainSoftLimit(now)
    }

    fun recordAccess(instance: String, path: String, counts: Map<String, Long>, now: Long) {
        if (counts.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((id, rawCount) in counts) {
                val count = rawCount.coerceIn(1, 1_000_000)
                db.execSQL("INSERT OR IGNORE INTO membership(instance,path,entity_id) VALUES(?,?,?)", arrayOf(instance, path, id))
                db.execSQL(
                    """UPDATE membership SET runtime_ref=1,last_access=?,access_count=access_count+?,
                       first_access=CASE WHEN first_access=0 THEN ? ELSE first_access END,
                       reasons=CASE WHEN instr(reasons,'runtime')=0 THEN trim(reasons||',runtime',',') ELSE reasons END
                       WHERE instance=? AND path=? AND entity_id=?""",
                    arrayOf(now, count, now, instance, path, id),
                )
                db.execSQL("INSERT OR IGNORE INTO minute_rollup(instance,path,entity_id,minute) VALUES(?,?,?,?)", arrayOf(instance, path, id, now / MINUTE_MS))
                db.execSQL(
                    "UPDATE minute_rollup SET access_count=access_count+? WHERE instance=? AND path=? AND entity_id=? AND minute=?",
                    arrayOf(count, instance, path, id, now / MINUTE_MS),
                )
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        maintainSoftLimit(now)
    }

    fun recordMetrics(instance: String, path: String, metrics: Map<String, Pair<Long, Long>>, now: Long) {
        if (metrics.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((id, metric) in metrics) {
                // Load evidence covers the complete working stream, not only entities already promoted
                // by static/runtime dependency evidence.
                db.execSQL("INSERT OR IGNORE INTO membership(instance,path,entity_id) VALUES(?,?,?)", arrayOf(instance, path, id))
                db.execSQL(
                    """UPDATE membership SET update_count=update_count+?,update_bytes=update_bytes+?,
                       rate_update_bytes=CASE WHEN rate_window_start=0 OR ?-rate_window_start>? THEN ? ELSE rate_update_bytes+? END,
                       rate_window_start=CASE WHEN rate_window_start=0 OR ?-rate_window_start>? THEN ? ELSE rate_window_start END,
                       last_update_at=? WHERE instance=? AND path=? AND entity_id=?""",
                    arrayOf(
                        metric.first, metric.second,
                        now, RATE_WINDOW_MS, metric.second, metric.second,
                        now, RATE_WINDOW_MS, now - METRIC_BATCH_MS, now,
                        instance, path, id,
                    ),
                )
                db.execSQL("INSERT OR IGNORE INTO minute_rollup(instance,path,entity_id,minute) VALUES(?,?,?,?)", arrayOf(instance, path, id, now / MINUTE_MS))
                db.execSQL(
                    "UPDATE minute_rollup SET update_count=update_count+?,update_bytes=update_bytes+? WHERE instance=? AND path=? AND entity_id=? AND minute=?",
                    arrayOf(metric.first, metric.second, instance, path, id, now / MINUTE_MS),
                )
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        maintainSoftLimit(now)
    }

    internal fun recordDashboardPerformance(sample: DashboardPerformanceSample) {
        val batch = sample.batch
        val slowest = batch.interactionMaxMicros
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                """INSERT OR IGNORE INTO dashboard_performance(
                   instance,path,minute,filter_active,entity_count
                   ) VALUES(?,?,?,?,?)""",
                arrayOf(sample.instance, sample.path, sample.minute, if (sample.filterActive) 1 else 0, sample.entityCount),
            )
            db.execSQL(
                """UPDATE dashboard_performance SET
                   filter_active=?,entity_count=?,sample_ms=sample_ms+?,frames=frames+?,
                   payload_bytes=payload_bytes+?,updates=updates+?,hydration_updates=hydration_updates+?,
                   observer_micros=observer_micros+?,dropped_frames=dropped_frames+?,
                   state_task_micros=state_task_micros+?,
                   state_task_max_micros=max(state_task_max_micros,?),
                   interaction_count=interaction_count+?,
                   interaction_max_micros=max(interaction_max_micros,?),
                   input_delay_micros=CASE WHEN ?>interaction_max_micros THEN ? ELSE input_delay_micros END,
                   interaction_processing_micros=CASE WHEN ?>interaction_max_micros THEN ? ELSE interaction_processing_micros END,
                   presentation_micros=CASE WHEN ?>interaction_max_micros THEN ? ELSE presentation_micros END,
                   loaf_count=loaf_count+?,blocking_micros=blocking_micros+?,
                   loaf_max_micros=max(loaf_max_micros,?),script_micros=script_micros+?,
                   render_micros=render_micros+?,long_task_count=long_task_count+?
                   WHERE instance=? AND path=? AND minute=?""",
                arrayOf(
                    if (sample.filterActive) 1 else 0, sample.entityCount, batch.sampleMs, batch.frames,
                    batch.payloadBytes, batch.entityUpdates, batch.hydrationUpdates,
                    batch.observerMicros, batch.droppedFrames, batch.stateTaskMicros,
                    batch.stateTaskMaxMicros, batch.interactionBins.sum(), slowest,
                    slowest, batch.inputDelayMicros,
                    slowest, batch.interactionProcessingMicros,
                    slowest, batch.presentationMicros,
                    batch.loafCount, batch.blockingMicros, batch.loafMaxMicros, batch.scriptMicros,
                    batch.renderMicros, batch.longTaskCount,
                    sample.instance, sample.path, sample.minute,
                ),
            )
            if (performanceMaintenanceGate.admit(sample.minute * MINUTE_MS)) {
                db.execSQL(
                    "DELETE FROM dashboard_performance WHERE minute<?",
                    arrayOf(sample.minute - PERFORMANCE_RETENTION_MINUTES),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    internal fun dashboardPerformanceHistory(
        instance: String,
        path: String,
        sinceMinute: Long,
    ): List<DashboardPerformanceMinute> = readableDatabase.rawQuery(
        """SELECT minute,filter_active,entity_count,sample_ms,frames,payload_bytes,updates,
           hydration_updates,observer_micros,dropped_frames,state_task_micros,state_task_max_micros,
           interaction_count,interaction_max_micros,input_delay_micros,interaction_processing_micros,
           presentation_micros,loaf_count,blocking_micros,loaf_max_micros,script_micros,render_micros,
           long_task_count
           FROM dashboard_performance WHERE instance=? AND path=? AND minute>=? ORDER BY minute""",
        arrayOf(instance, path, sinceMinute.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(DashboardPerformanceMinute(
                    minute = cursor.getLong(0),
                    filterActive = cursor.getInt(1) != 0,
                    entityCount = cursor.getInt(2),
                    sampleMs = cursor.getLong(3),
                    frames = cursor.getLong(4),
                    payloadBytes = cursor.getLong(5),
                    updates = cursor.getLong(6),
                    hydrationUpdates = cursor.getLong(7),
                    observerMicros = cursor.getLong(8),
                    droppedFrames = cursor.getLong(9),
                    stateTaskMicros = cursor.getLong(10),
                    stateTaskMaxMicros = cursor.getLong(11),
                    interactionCount = cursor.getLong(12),
                    interactionMaxMicros = cursor.getLong(13),
                    inputDelayMicros = cursor.getLong(14),
                    interactionProcessingMicros = cursor.getLong(15),
                    presentationMicros = cursor.getLong(16),
                    loafCount = cursor.getLong(17),
                    blockingMicros = cursor.getLong(18),
                    loafMaxMicros = cursor.getLong(19),
                    scriptMicros = cursor.getLong(20),
                    renderMicros = cursor.getLong(21),
                    longTaskCount = cursor.getLong(22),
                ))
            }
        }
    }

    fun setOverride(instance: String, path: String, entityId: String, override: String) {
        setOverrides(instance, path, listOf(entityId), override)
    }

    fun setOverrides(instance: String, path: String, entityIds: Collection<String>, override: String) {
        val pinned = if (override == "pinned") 1 else 0
        val excluded = if (override == "forced_exclude") 1 else 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (entityId in entityIds) {
                db.execSQL("INSERT OR IGNORE INTO membership(instance,path,entity_id) VALUES(?,?,?)", arrayOf(instance, path, entityId))
                db.execSQL("UPDATE membership SET pinned=?,excluded=?,reasons=CASE WHEN ?=0 AND ?=0 THEN replace(replace(reasons,'manual',''),',,',',') WHEN instr(reasons,'manual')=0 THEN trim(reasons||',manual',',') ELSE reasons END WHERE instance=? AND path=? AND entity_id=?",
                    arrayOf(pinned, excluded, pinned, excluded, instance, path, entityId))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Clear rebuildable evidence for one dashboard while retaining the instance-wide HA catalog. */
    fun resetEvidence(instance: String, path: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("minute_rollup", "instance=? AND path=?", arrayOf(instance, path))
            db.delete("membership", "instance=? AND path=?", arrayOf(instance, path))
            db.delete("dashboard", "instance=? AND path=?", arrayOf(instance, path))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        recentCache.invalidate(instance to path)
    }

    fun activeIds(
        instance: String,
        path: String,
        now: Long,
        includeStatic: Boolean = true,
        includeRuntime: Boolean = true,
    ): List<String> {
        val cutoff = now - RUNTIME_RETENTION_MS
        val evidence = buildList {
            add("m.pinned=1")
            if (includeStatic) add("m.static_ref=1")
            if (includeRuntime) add("m.last_access>=?")
        }.joinToString(" OR ")
        val args = mutableListOf(instance, path)
        if (includeRuntime) args += cutoff.toString()
        return readableDatabase.rawQuery(
            """SELECT m.entity_id FROM membership m LEFT JOIN entity e ON e.instance=m.instance AND e.entity_id=m.entity_id
               WHERE m.instance=? AND m.path=? AND m.excluded=0 AND ($evidence)
               AND (e.entity_id IS NULL OR e.missing_streak<3) ORDER BY m.entity_id""",
            args.toTypedArray(),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    }

    /** Unpinned dashboard evidence, independent of exclusion and automatic-promotion policy. */
    fun suggestedIds(instance: String, path: String): List<String> = readableDatabase.rawQuery(
        """SELECT m.entity_id FROM membership m LEFT JOIN entity e ON e.instance=m.instance AND e.entity_id=m.entity_id
           WHERE m.instance=? AND m.path=? AND m.pinned=0
           AND (m.static_ref=1 OR m.runtime_ref=1)
           AND (e.entity_id IS NULL OR e.missing_streak<3) ORDER BY m.entity_id""",
        arrayOf(instance, path),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** Resolve renderer evidence against the current catalog in bounded SQLite-parameter chunks. */
    fun existingEntityIds(instance: String, entityIds: Collection<String>): Set<String> {
        if (entityIds.isEmpty()) return emptySet()
        val result = mutableSetOf<String>()
        entityIds.distinct().chunked(MAX_SQL_ID_FILTER).forEach { chunk ->
            val placeholders = List(chunk.size) { "?" }.joinToString(",")
            readableDatabase.rawQuery(
                "SELECT entity_id FROM entity WHERE instance=? AND missing_streak<3 AND entity_id IN ($placeholders)",
                (listOf(instance) + chunk).toTypedArray(),
            ).use { cursor -> while (cursor.moveToNext()) result += cursor.getString(0) }
        }
        return result
    }

    fun snapshot(instance: String, path: String): Snapshot {
        val dashboard = readableDatabase.rawQuery(
            "SELECT status,last_sync,error,unresolved_json,issues_json FROM dashboard WHERE instance=? AND path=?",
            arrayOf(instance, path),
        ).use { c ->
            if (c.moveToFirst()) {
                DashboardRow(c.getString(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4))
            } else null
        }
        fun count(sql: String) = readableDatabase.rawQuery(sql, arrayOf(instance, path)).use { c -> c.moveToFirst(); c.getInt(0) }
        val issueCounts = EntityCatalogIssuePersistence.counts(dashboard?.issuesJson ?: "[]")
        return Snapshot(
            dashboard?.status ?: "disabled", dashboard?.lastSync ?: 0L,
            readableDatabase.rawQuery("SELECT count(*) FROM entity WHERE instance=? AND missing_streak<3", arrayOf(instance)).use { c -> c.moveToFirst(); c.getInt(0) },
            count("SELECT count(*) FROM membership WHERE instance=? AND path=? AND excluded=0 AND (pinned=1 OR static_ref=1 OR runtime_ref=1)"),
            dashboard?.unresolvedJson?.let { runCatching { JSONArray(it).length() }.getOrDefault(0) } ?: 0,
            issueCounts.first,
            issueCounts.second,
            EntityCatalogIssuePersistence.ignoredCount(dashboard?.issuesJson ?: "[]"),
            dashboard?.error ?: "",
            cachedDatabaseBytes(),
        )
    }

    /** Structured, bounded diagnostics only. The raw dashboard configuration is never returned. */
    fun issuesJson(instance: String, path: String): String {
        val stored = readableDatabase.rawQuery(
            "SELECT issues_json FROM dashboard WHERE instance=? AND path=?",
            arrayOf(instance, path),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else "[]" }
        return EntityCatalogIssuePersistence.boundExistingJson(stored)
    }

    fun ignoredIssueFingerprints(instance: String, path: String): Set<String> = readableDatabase.rawQuery(
        "SELECT fingerprint FROM dashboard_issue_ignore WHERE instance=? AND path=? ORDER BY fingerprint",
        arrayOf(instance, path),
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    /** Persist a dashboard-scoped diagnostic override only for an issue visible in the current scan. */
    fun setIssueIgnored(instance: String, path: String, fingerprint: String, ignored: Boolean, now: Long): Boolean {
        if (!FINGERPRINT.matches(fingerprint)) return false
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val stored = db.rawQuery(
                "SELECT issues_json FROM dashboard WHERE instance=? AND path=?",
                arrayOf(instance, path),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: return false
            val issues = JSONArray(EntityCatalogIssuePersistence.boundExistingJson(stored))
            val exists = (0 until issues.length()).any { issues.optJSONObject(it)?.optString("fingerprint") == fingerprint }
            if (!exists) return false
            if (ignored) {
                db.execSQL(
                    "INSERT OR REPLACE INTO dashboard_issue_ignore(instance,path,fingerprint,ignored_at) VALUES(?,?,?,?)",
                    arrayOf(instance, path, fingerprint, now),
                )
            } else {
                db.delete(
                    "dashboard_issue_ignore", "instance=? AND path=? AND fingerprint=?",
                    arrayOf(instance, path, fingerprint),
                )
            }
            val ignoredSet = db.rawQuery(
                "SELECT fingerprint FROM dashboard_issue_ignore WHERE instance=? AND path=?",
                arrayOf(instance, path),
            ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            db.execSQL(
                "UPDATE dashboard SET issues_json=? WHERE instance=? AND path=?",
                arrayOf(EntityCatalogIssuePersistence.applyIgnores(issues, ignoredSet), instance, path),
            )
            db.setTransactionSuccessful()
            true
        } finally {
            db.endTransaction()
        }
    }

    /** Internal-only source for rebuilding bounded derived diagnostics after process restart. */
    fun dashboardConfigJson(instance: String, path: String): String = readableDatabase.rawQuery(
        "SELECT config_json FROM dashboard WHERE instance=? AND path=?",
        arrayOf(instance, path),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "{}" }

    fun entitiesJson(
        instance: String,
        path: String,
        query: String,
        filter: String,
        limit: Int,
        offset: Int,
        sortKey: String = "entity_id",
        sortDirection: String = "asc",
        maxLimit: Int = 500,
        includeIds: Set<String>? = null,
        excludeIds: Set<String> = emptySet(),
    ): String {
        val span = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_LIST)
        var outputBytes = 0L
        var outputRows = 0L
        try {
        val where = mutableListOf("e.instance=?")
        val args = mutableListOf(instance)
        if (query.isNotBlank()) {
            val raw = query.trim().take(100)
            val slug = raw.lowercase().replace('-', '_').replace(' ', '_')
            if (slug == raw) {
                where += "e.entity_id LIKE ?"; args += "%$raw%"
            } else {
                where += "(e.entity_id LIKE ? OR e.entity_id LIKE ?)"
                args += "%$raw%"; args += "%$slug%"
            }
        }
        when (filter) {
            "active" -> where += "m.excluded=0 AND (m.pinned=1 OR m.static_ref=1 OR m.runtime_ref=1)"
            "excluded" -> where += "m.excluded=1"
            "missing" -> where += "e.missing_streak>0"
            "review" -> where += "(e.missing_streak>0 OR (coalesce(m.update_count,0)>0 AND coalesce(m.last_access,0)=0 AND coalesce(m.static_ref,0)=0 AND coalesce(m.pinned,0)=0))"
            "candidate" -> where += "coalesce(m.pinned,0)=0 AND (m.static_ref=1 OR m.runtime_ref=1)"
            "unpinned" -> where += "coalesce(m.pinned,0)=0"
            else -> Unit
        }
        // Every displayed ordering is executed by SQLite with LIMIT/OFFSET. In particular, recent
        // access/rate ordering must never materialize and sort the complete HA catalog in the app.
        val sqlIdFiltersFit = (includeIds?.size ?: 0) + excludeIds.size <= MAX_SQL_ID_FILTER
        val boundedIdFallback = !sqlIdFiltersFit
        if (includeIds != null) {
            if (includeIds.isEmpty()) {
                where += "0"
            } else if (sqlIdFiltersFit) {
                where += "e.entity_id IN (${includeIds.joinToString(",") { "?" }})"
                args += includeIds.sorted()
            }
        }
        if (excludeIds.isNotEmpty()) {
            if (sqlIdFiltersFit) {
                where += "e.entity_id NOT IN (${excludeIds.joinToString(",") { "?" }})"
                args += excludeIds.sorted()
            }
        }
        val join = "LEFT JOIN membership m ON m.instance=e.instance AND m.entity_id=e.entity_id AND m.path=?"
        val now = System.currentTimeMillis()
        val normalizedSort = EntityCatalogSorting.key(sortKey)
        val requiresRecentOrdering = normalizedSort == "access_1h" || normalizedSort == "rate_1h_bps"
        val recentJoin = if (requiresRecentOrdering) {
            "LEFT JOIN (${recentAggregateSql(now)}) r ON r.entity_id=e.entity_id"
        } else ""
        val observedMs = "max($METRIC_BATCH_MS,min($HOUR_MS,$now-max(coalesce(r.first_minute,0)*$MINUTE_MS,${now - HOUR_MS})))"
        val recentProjection = if (requiresRecentOrdering) {
            """coalesce(r.access_1m,0),coalesce(r.access_1h,0) AS recent_access_1h,coalesce(r.access_1d,0),
               coalesce(r.bytes_1m,0),coalesce(r.bytes_1h,0),coalesce(r.bytes_1d,0),coalesce(r.first_minute,0),
               (coalesce(r.bytes_1h,0)*1000.0/$observedMs) AS recent_rate_1h"""
        } else "0,0 AS recent_access_1h,0,0,0,0,0,0.0 AS recent_rate_1h"
        val baseSql = """SELECT e.entity_id,e.state,e.metadata_json,e.first_seen,e.last_seen,e.missing_streak,
            coalesce(m.static_ref,0),coalesce(m.runtime_ref,0),coalesce(m.pinned,0),coalesce(m.excluded,0),
            coalesce(m.reasons,''),coalesce(m.last_access,0),coalesce(m.access_count,0),coalesce(m.update_count,0),coalesce(m.update_bytes,0),
            coalesce(m.rate_window_start,0),coalesce(m.rate_update_bytes,0),coalesce(m.last_update_at,0),
            $recentProjection
            FROM entity e $join $recentJoin WHERE ${where.joinToString(" AND ")}"""
        val effectiveLimit = limit.coerceIn(1, maxLimit.coerceIn(1, MAX_SQL_ID_FILTER))
        val effectiveOffset = offset.coerceAtLeast(0)
        val recentSnapshot = recentSnapshot(instance, path, now)
        val ranks = recentSnapshot.ranks
        val pageArgs = if (requiresRecentOrdering) listOf(path, instance, path) + args else listOf(path) + args
        val orderBy = requireNotNull(EntityCatalogSorting.sqlOrder(sortKey, sortDirection))
        val total: Int
        val selected: List<CatalogRow>
        if (boundedIdFallback) {
            // SQLite cannot accept an arbitrary 50k-id IN/NOT IN predicate. Stream only globally
            // ordered IDs, apply the existing bounded sets in-process, and retain the requested page.
            val collector = BoundedEntityIdPageCollector(effectiveLimit, effectiveOffset, includeIds, excludeIds)
            val idProjection = if (requiresRecentOrdering) {
                """e.entity_id,coalesce(r.access_1h,0) AS recent_access_1h,
                   (coalesce(r.bytes_1h,0)*1000.0/$observedMs) AS recent_rate_1h"""
            } else "e.entity_id"
            val idSql = """SELECT $idProjection FROM entity e $join $recentJoin
                WHERE ${where.joinToString(" AND ")} ORDER BY $orderBy"""
            readableDatabase.rawQuery(idSql, pageArgs.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) collector.offer(cursor.getString(0))
            }
            total = collector.total
            val selectedIds = collector.pageIds
            if (selectedIds.isEmpty()) {
                selected = emptyList()
            } else {
                val placeholders = List(selectedIds.size) { "?" }.joinToString(",")
                val fetchSql = "$baseSql AND e.entity_id IN ($placeholders)"
                val fetched = HashMap<String, CatalogRow>(selectedIds.size)
                readableDatabase.rawQuery(fetchSql, (pageArgs + selectedIds).toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.catalogRow(recentSnapshot)?.let { fetched[it.entityId] = it }
                    }
                }
                selected = selectedIds.mapNotNull(fetched::get)
            }
        } else {
            total = readableDatabase.rawQuery(
                "SELECT count(*) FROM entity e $join WHERE ${where.joinToString(" AND ")}",
                (listOf(path) + args).toTypedArray(),
            ).use { c -> c.moveToFirst(); c.getInt(0) }
            val matched = ArrayList<CatalogRow>(effectiveLimit)
            val sql = "$baseSql ORDER BY $orderBy LIMIT $effectiveLimit OFFSET $effectiveOffset"
            readableDatabase.rawQuery(sql, pageArgs.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) cursor.catalogRow(recentSnapshot)?.let(matched::add)
            }
            selected = matched
        }
        val rows = JSONArray()
        val selectedRecent = if (requiresRecentOrdering) emptyMap() else recentStatsForIds(
            instance, path, now, selected.map { it.entityId },
        )
        selected.forEach { rawRow ->
            outputRows++
            val r = rawRow.recent ?: selectedRecent[rawRow.entityId]
            val row = if (r == null || rawRow.recent != null) rawRow else rawRow.copy(
                recent = r,
                rate1h = r.bytes1h / observedSeconds(recentSnapshot.generatedAt, r.firstMinute, HOUR_MS),
            )
            rows.put(catalogRowJson(row, now, recentSnapshot, ranks))
        }
        return JSONObject().put("items", rows).put("limit", effectiveLimit).put("offset", effectiveOffset)
            .put("total", total).put("sort", EntityCatalogSorting.key(sortKey))
            .put("direction", EntityCatalogSorting.direction(sortDirection)).toString().also {
                outputBytes = it.toByteArray(Charsets.UTF_8).size.toLong()
            }
        } catch (error: Exception) {
            span.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            span.work(units = outputRows, bytes = outputBytes).close()
        }
    }

    /** Small diagnostics projection for the performance card; SQLite ranks before the fixed LIMIT. */
    fun performanceSummaryJson(instance: String, path: String, now: Long = System.currentTimeMillis()): String {
        val oneMinute = (now - MINUTE_MS) / MINUTE_MS
        val rows = JSONArray()
        val sql = """SELECT entity_id,sum(update_count),sum(update_bytes),
            min(CASE WHEN span_start=0 THEN minute ELSE span_start END) AS first_minute
            FROM minute_rollup WHERE instance=? AND path=? AND minute>=?
            GROUP BY entity_id ORDER BY sum(update_bytes) DESC,entity_id COLLATE NOCASE LIMIT 3"""
        readableDatabase.rawQuery(sql, arrayOf(instance, path, oneMinute.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val observed = observedSeconds(now, cursor.getLong(3), MINUTE_MS)
                rows.put(JSONObject()
                    .put("entityId", cursor.getString(0))
                    .put("updates1m", cursor.getLong(1))
                    .put("payloadBps1m", cursor.getLong(2) / observed))
            }
        }
        return rows.toString()
    }

    private fun catalogRowJson(
        row: CatalogRow,
        now: Long,
        recentSnapshot: RecentSnapshot,
        ranks: RecentRanks = recentSnapshot.ranks,
    ): JSONObject = JSONObject().apply {
        put("entity_id", row.entityId); put("state", row.state); put("metadata", JSONObject(row.metadataJson))
        put("first_seen", row.firstSeen); put("last_seen", row.lastSeen); put("missing_streak", row.missingStreak)
        put("static", row.staticRef); put("runtime", row.runtimeRef); put("pinned", row.pinned)
        put("excluded", row.excluded); put("reasons", row.reasons); put("last_access", row.lastAccess)
        put("access_count", row.accessCount); put("update_count", row.updateCount); put("update_bytes", row.updateBytes)
        val rate = if (row.rateWindowStart == 0L || row.lastUpdateAt == 0L || now - row.lastUpdateAt > RATE_STALE_MS) 0.0
            else row.rateUpdateBytes * 1000.0 / (now - row.rateWindowStart).coerceAtLeast(1000L)
        put("data_rate_bps", rate)
        val r = row.recent
        if (r != null) {
            put("access_1m", r.access1m); put("access_1h", r.access1h); put("access_1d", r.access1d)
            put("rate_1m_bps", r.bytes1m / observedSeconds(recentSnapshot.generatedAt, r.firstMinute, MINUTE_MS))
            put("rate_1h_bps", row.rate1h); put("rate_1d_bps", r.bytes1d / observedSeconds(recentSnapshot.generatedAt, r.firstMinute, 24L * HOUR_MS))
            put("access_1m_rank", ranks.rank(r.access1m, ranks.access1m)); put("access_1h_rank", ranks.rank(r.access1h, ranks.access1h))
            put("access_1d_rank", ranks.rank(r.access1d, ranks.access1d)); put("rate_1m_rank", ranks.rank(r.bytes1m, ranks.bytes1m))
            put("rate_1h_rank", ranks.rank(r.bytes1h, ranks.bytes1h)); put("rate_1d_rank", ranks.rank(r.bytes1d, ranks.bytes1d))
        } else {
            put("access_1m", 0); put("access_1h", 0); put("access_1d", 0)
            put("rate_1m_bps", 0); put("rate_1h_bps", 0); put("rate_1d_bps", 0)
            put("access_1m_rank", 0); put("access_1h_rank", 0); put("access_1d_rank", 0)
            put("rate_1m_rank", 0); put("rate_1h_rank", 0); put("rate_1d_rank", 0)
        }
    }

    /** Stream a valid, self-describing export under hard row, UTF-8 byte, and elapsed-time limits. */
    internal fun writeExportJson(
        instance: String,
        path: String,
        writer: Writer,
        policy: EntityExportPolicy = EntityExportPolicy(),
        now: Long = System.currentTimeMillis(),
        monotonicNanos: () -> Long = System::nanoTime,
    ): EntityExportResult {
        val span = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_EXPORT)
        val startedAt = monotonicNanos()
        var bytes = 0L
        var rows = 0
        var reason: String? = null
        try {
            val header = entityExportHeader(EntityLearningProtocol.hash(instance), path, now)
            writer.write(header)
            bytes += header.utf8Size()
            val recentSnapshot = recentSnapshot(instance, path, now)
            val recentJoin = "LEFT JOIN (${recentAggregateSql(now)}) r ON r.entity_id=e.entity_id"
            val sql = """SELECT e.entity_id,e.state,e.metadata_json,e.first_seen,e.last_seen,e.missing_streak,
                coalesce(m.static_ref,0),coalesce(m.runtime_ref,0),coalesce(m.pinned,0),coalesce(m.excluded,0),
                coalesce(m.reasons,''),coalesce(m.last_access,0),coalesce(m.access_count,0),coalesce(m.update_count,0),coalesce(m.update_bytes,0),
                coalesce(m.rate_window_start,0),coalesce(m.rate_update_bytes,0),coalesce(m.last_update_at,0),
                coalesce(r.access_1m,0),coalesce(r.access_1h,0),coalesce(r.access_1d,0),
                coalesce(r.bytes_1m,0),coalesce(r.bytes_1h,0),coalesce(r.bytes_1d,0),coalesce(r.first_minute,0)
                FROM entity e LEFT JOIN membership m ON m.instance=e.instance AND m.entity_id=e.entity_id AND m.path=?
                $recentJoin WHERE e.instance=? ORDER BY e.entity_id COLLATE NOCASE,e.entity_id LIMIT ${policy.maxRows + 1}"""
            readableDatabase.rawQuery(sql, arrayOf(path, instance, path, instance)).use { cursor ->
                while (cursor.moveToNext()) {
                    val admissionReason = exportTruncationReason(
                        policy, rows, bytes, nextRowBytes = 0L,
                        elapsedNanos = forwardElapsedNanos(monotonicNanos(), startedAt),
                    )
                    if (admissionReason != null) { reason = admissionReason; break }
                    val recent = cursor.recentAt(18).takeIf { it.firstMinute > 0L }
                    val row = CatalogRow(
                        cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3),
                        cursor.getLong(4), cursor.getInt(5), cursor.getInt(6) != 0, cursor.getInt(7) != 0,
                        cursor.getInt(8) != 0, cursor.getInt(9) != 0, cursor.getString(10), cursor.getLong(11),
                        cursor.getLong(12), cursor.getLong(13), cursor.getLong(14), cursor.getLong(15),
                        cursor.getLong(16), cursor.getLong(17), recent,
                        recent?.let { it.bytes1h / observedSeconds(recentSnapshot.generatedAt, it.firstMinute, HOUR_MS) } ?: 0.0,
                    )
                    val encoded = catalogRowJson(row, now, recentSnapshot).toString()
                    val separatorBytes = if (rows == 0) 0L else 1L
                    val sizeReason = exportTruncationReason(
                        policy, rows, bytes, separatorBytes + encoded.utf8Size(), elapsedNanos = 0L,
                    )
                    if (sizeReason != null) { reason = sizeReason; break }
                    if (rows > 0) { writer.write(','.code); bytes++ }
                    writer.write(encoded)
                    bytes += encoded.utf8Size()
                    rows++
                }
            }
            val footer = entityExportFooter(rows, reason)
            writer.write(footer)
            bytes += footer.utf8Size()
            if (reason != null) FeatureCosts.registry.recordDropped(FeatureCostOperation.ENTITY_EXPORT)
            return EntityExportResult(rows, bytes, reason)
        } catch (error: Exception) {
            span.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            span.work(units = rows.toLong(), bytes = bytes).close()
        }
    }

    /**
     * Retention is evidence-first: old rollups and tombstones go before inactive entity detail. The
     * active set and all manual overrides are never removed by size maintenance. SQLite may keep freed
     * pages for reuse, so status reports live pages rather than the physical high-water mark.
     */
    private fun maintainSoftLimit(now: Long) {
        if (!maintenanceGate.admit(now)) return
        val span = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_DB_MAINTENANCE)
        var observedBytes = 0L
        var appliedTiers = 0
        try {
            val db = writableDatabase
            observedBytes = measureDatabaseBytes(db)
            updateDatabaseBytesCache(now, observedBytes)
            if (observedBytes <= SOFT_LIMIT_BYTES) return
            db.execSQL("DELETE FROM minute_rollup WHERE minute<?", arrayOf((now - DAY_MS) / MINUTE_MS))
            db.execSQL("DELETE FROM entity WHERE tombstone_at>0")
            db.execSQL(
                """UPDATE entity SET attributes_json='{}',metadata_json='{}' WHERE (instance,entity_id) NOT IN
                   (SELECT instance,entity_id FROM membership WHERE pinned=1 OR static_ref=1 OR runtime_ref=1)""",
            )
            observedBytes = measureDatabaseBytes(db)
            updateDatabaseBytesCache(now, observedBytes)
            if (observedBytes > SOFT_LIMIT_BYTES) {
                db.execSQL(
                    """UPDATE entity SET attributes_json='{}' WHERE (instance,entity_id) NOT IN
                       (SELECT instance,entity_id FROM membership WHERE pinned=1 OR static_ref=1 OR last_access>=?)""",
                    arrayOf(now - RUNTIME_RETENTION_MS),
                )
                observedBytes = measureDatabaseBytes(db)
                updateDatabaseBytesCache(now, observedBytes)
            }
            while (true) {
                val tier = RollupRetentionPolicy.nextTier(observedBytes, SOFT_LIMIT_BYTES, appliedTiers) ?: break
                applyRollupPressureTier(db, tier, now)
                appliedTiers++
                observedBytes = measureDatabaseBytes(db)
                updateDatabaseBytesCache(now, observedBytes)
            }
        } catch (error: Exception) {
            span.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            span.work(units = appliedTiers.toLong(), bytes = observedBytes).close()
        }
    }

    /** Rewrite only derived telemetry rows. Each tier is transactionally replace-or-delete so a
     * process death cannot leave a partially compacted window. */
    private fun applyRollupPressureTier(db: SQLiteDatabase, tier: RollupPressureTier, now: Long) {
        val window = RollupRetentionPolicy.window(tier, now)
        db.beginTransaction()
        try {
            if (window.drop) {
                db.delete("minute_rollup", window.whereSql, window.whereArgs)
            } else {
                db.execSQL("DROP TABLE IF EXISTS temp.entity_rollup_compact")
                db.execSQL(
                    """CREATE TEMP TABLE entity_rollup_compact(
                       instance TEXT NOT NULL,path TEXT NOT NULL,entity_id TEXT NOT NULL,minute INTEGER NOT NULL,
                       access_count INTEGER NOT NULL,update_count INTEGER NOT NULL,update_bytes INTEGER NOT NULL,
                       span_start INTEGER NOT NULL,
                       PRIMARY KEY(instance,path,entity_id,minute))""",
                )
                val minuteExpression = window.targetMinute?.toString() ?: "(minute/60)*60"
                db.execSQL(
                    """INSERT INTO entity_rollup_compact
                       SELECT instance,path,entity_id,$minuteExpression,sum(access_count),sum(update_count),sum(update_bytes),
                              min(CASE WHEN span_start=0 THEN minute ELSE span_start END)
                       FROM minute_rollup WHERE ${window.whereSql}
                       GROUP BY instance,path,entity_id,$minuteExpression""",
                    window.whereArgs,
                )
                db.delete("minute_rollup", window.whereSql, window.whereArgs)
                db.execSQL(
                    """INSERT OR REPLACE INTO minute_rollup(instance,path,entity_id,minute,access_count,update_count,update_bytes,span_start)
                       SELECT instance,path,entity_id,minute,access_count,update_count,update_bytes,span_start
                       FROM entity_rollup_compact""",
                )
                db.execSQL("DROP TABLE entity_rollup_compact")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            runCatching { db.execSQL("DROP TABLE IF EXISTS temp.entity_rollup_compact") }
        }
    }

    private data class Recent(
        val access1m: Long,
        val access1h: Long,
        val access1d: Long,
        val bytes1m: Long,
        val bytes1h: Long,
        val bytes1d: Long,
        val firstMinute: Long,
    )

    private data class RecentSnapshot(
        val generatedAt: Long,
        val ranks: RecentRanks,
    )

    private data class CatalogRow(
        val entityId: String,
        val state: String,
        val metadataJson: String,
        val firstSeen: Long,
        val lastSeen: Long,
        val missingStreak: Int,
        val staticRef: Boolean,
        val runtimeRef: Boolean,
        val pinned: Boolean,
        val excluded: Boolean,
        val reasons: String,
        val lastAccess: Long,
        val accessCount: Long,
        val updateCount: Long,
        val updateBytes: Long,
        val rateWindowStart: Long,
        val rateUpdateBytes: Long,
        val lastUpdateAt: Long,
        val recent: Recent?,
        val rate1h: Double,
    ) {
        val sortProjection get() = EntitySortProjection(
            entityId = entityId,
            access1h = recent?.access1h ?: 0,
            rate1h = rate1h,
            reasons = reasons,
            lastAccess = lastAccess,
            override = when { excluded -> "excluded"; pinned -> "pinned"; else -> "auto" },
        )
    }

    private data class DashboardRow(
        val status: String,
        val lastSync: Long,
        val error: String,
        val unresolvedJson: String,
        val issuesJson: String,
    )

    /** Constant-space approximate percentile ranks. Exact values do not justify six boxed/sorted
     * full-catalog arrays on memory-constrained panels; four buckets per power-of-two range preserve
     * useful heat-map ordering while bounding the snapshot to a few hundred counters. */
    private class RecentRanks {
        val access1m = LogRankHistogram()
        val access1h = LogRankHistogram()
        val access1d = LogRankHistogram()
        val bytes1m = LogRankHistogram()
        val bytes1h = LogRankHistogram()
        val bytes1d = LogRankHistogram()

        fun add(row: Recent) {
            access1m.add(row.access1m); access1h.add(row.access1h); access1d.add(row.access1d)
            bytes1m.add(row.bytes1m); bytes1h.add(row.bytes1h); bytes1d.add(row.bytes1d)
        }

        fun rank(value: Long, histogram: LogRankHistogram): Double = histogram.rank(value)
    }

    private fun recentAggregateSql(now: Long, entityIdCount: Int = 0): String {
        val oneMinute = (now - MINUTE_MS) / MINUTE_MS
        val oneHour = (now - HOUR_MS) / MINUTE_MS
        val oneDay = (now - 24L * HOUR_MS) / MINUTE_MS
        // All interpolated values originate from the local clock; request data remains bound separately.
        val entityFilter = if (entityIdCount > 0) " AND entity_id IN (${List(entityIdCount) { "?" }.joinToString(",")})" else ""
        return """SELECT entity_id,
               sum(CASE WHEN minute>=$oneMinute THEN access_count ELSE 0 END) AS access_1m,
               sum(CASE WHEN minute>=$oneHour THEN access_count ELSE 0 END) AS access_1h,
               sum(access_count) AS access_1d,
               sum(CASE WHEN minute>=$oneMinute THEN update_bytes ELSE 0 END) AS bytes_1m,
               sum(CASE WHEN minute>=$oneHour THEN update_bytes ELSE 0 END) AS bytes_1h,
               sum(update_bytes) AS bytes_1d,min(CASE WHEN span_start=0 THEN minute ELSE span_start END) AS first_minute
               FROM minute_rollup WHERE instance=? AND path=? AND minute>=$oneDay$entityFilter GROUP BY entity_id"""
    }

    private fun recentStatsForIds(
        instance: String,
        path: String,
        now: Long,
        entityIds: List<String>,
    ): Map<String, Recent> {
        if (entityIds.isEmpty()) return emptyMap()
        return readableDatabase.rawQuery(
            recentAggregateSql(now, entityIds.size),
            (listOf(instance, path) + entityIds).toTypedArray(),
        ).use { cursor -> buildMap {
            while (cursor.moveToNext()) put(cursor.getString(0), cursor.recentAt(1))
        } }
    }

    private fun Cursor.recentAt(index: Int): Recent = Recent(
        getLong(index), getLong(index + 1), getLong(index + 2), getLong(index + 3),
        getLong(index + 4), getLong(index + 5), getLong(index + 6),
    )

    private fun Cursor.catalogRow(recentSnapshot: RecentSnapshot): CatalogRow {
        val stats = recentAt(18).takeIf { it.firstMinute > 0L }
        return CatalogRow(
            getString(0), getString(1), getString(2), getLong(3), getLong(4), getInt(5),
            getInt(6) != 0, getInt(7) != 0, getInt(8) != 0, getInt(9) != 0,
            getString(10), getLong(11), getLong(12), getLong(13), getLong(14),
            getLong(15), getLong(16), getLong(17), stats,
            stats?.let { it.bytes1h / observedSeconds(recentSnapshot.generatedAt, it.firstMinute, HOUR_MS) } ?: 0.0,
        )
    }

    private val recentCache = BoundedSnapshotCache<Pair<String, String>, RecentSnapshot>(
        windowMs = ENTITY_RANKING_REFRESH_MS,
        maxEntries = MAX_RANKING_CACHE_ENTRIES,
    )

    /** One immutable rollup/ranking snapshot serves all entity tables across the diagnostic refresh interval. */
    private fun recentSnapshot(instance: String, path: String, now: Long): RecentSnapshot =
        recentCache.get(instance to path, now) {
            val ranks = RecentRanks()
            readableDatabase.rawQuery(recentAggregateSql(now), arrayOf(instance, path)).use { cursor ->
                while (cursor.moveToNext()) ranks.add(cursor.recentAt(1))
            }
            RecentSnapshot(now, ranks)
        }

    private fun observedSeconds(now: Long, firstMinute: Long, windowMs: Long): Double {
        val start = maxOf(firstMinute * MINUTE_MS, now - windowMs)
        return (now - start).coerceIn(METRIC_BATCH_MS, windowMs) / 1000.0
    }

    private fun cachedDatabaseBytes(now: Long = System.currentTimeMillis()): Long {
        synchronized(databaseBytesCacheLock) {
            if (databaseBytesCachedAt != Long.MIN_VALUE && now >= databaseBytesCachedAt &&
                now - databaseBytesCachedAt < DATABASE_BYTES_CACHE_MS) {
                return databaseBytesCachedValue
            }
        }
        return measureDatabaseBytes().also { updateDatabaseBytesCache(now, it) }
    }

    private fun updateDatabaseBytesCache(now: Long, bytes: Long) = synchronized(databaseBytesCacheLock) {
        databaseBytesCachedAt = now
        databaseBytesCachedValue = bytes
    }

    private fun measureDatabaseBytes(db: SQLiteDatabase = readableDatabase): Long {
        fun pragma(name: String): Long = db.rawQuery("PRAGMA $name", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
        return (pragma("page_count") - pragma("freelist_count")).coerceAtLeast(0) * pragma("page_size")
    }

    companion object {
        internal const val DATABASE_NAME = "ha-paneld.db"
        internal const val LEGACY_DATABASE_NAME = "entity-learning.db"
        private const val VERSION = EntityCatalogSchema.CURRENT_VERSION
        private const val HOUR_MS = 3_600_000L
        private const val MINUTE_MS = 60_000L
        private const val RUNTIME_RETENTION_MS = 30L * 24 * HOUR_MS
        private const val TOMBSTONE_RETENTION_MS = 30L * 24 * HOUR_MS
        private const val SOFT_LIMIT_BYTES = 128L * 1024 * 1024
        private const val RATE_WINDOW_MS = 5L * 60_000
        private const val RATE_STALE_MS = 90_000L
        private const val METRIC_BATCH_MS = 5_000L
        private const val DAY_MS = 24L * HOUR_MS
        private const val MAX_RANKING_CACHE_ENTRIES = 4
        private const val DATABASE_BYTES_CACHE_MS = 10_000L
        private const val MAINTENANCE_INTERVAL_MS = 10L * 60_000
        private const val MAX_SQL_ID_FILTER = 800
        internal const val PERFORMANCE_RETENTION_DAYS = 7
        private const val PERFORMANCE_RETENTION_MINUTES = PERFORMANCE_RETENTION_DAYS * 24L * 60L
        private const val PERFORMANCE_MAINTENANCE_INTERVAL_MS = HOUR_MS
        internal val PERFORMANCE_HISTORY_TABLE_SQL = """CREATE TABLE dashboard_performance(
            instance TEXT NOT NULL,path TEXT NOT NULL,minute INTEGER NOT NULL,
            filter_active INTEGER NOT NULL DEFAULT 0,entity_count INTEGER NOT NULL DEFAULT 0,
            sample_ms INTEGER NOT NULL DEFAULT 0,frames INTEGER NOT NULL DEFAULT 0,
            payload_bytes INTEGER NOT NULL DEFAULT 0,updates INTEGER NOT NULL DEFAULT 0,
            hydration_updates INTEGER NOT NULL DEFAULT 0,observer_micros INTEGER NOT NULL DEFAULT 0,
            dropped_frames INTEGER NOT NULL DEFAULT 0,state_task_micros INTEGER NOT NULL DEFAULT 0,
            state_task_max_micros INTEGER NOT NULL DEFAULT 0,interaction_count INTEGER NOT NULL DEFAULT 0,
            interaction_max_micros INTEGER NOT NULL DEFAULT 0,input_delay_micros INTEGER NOT NULL DEFAULT 0,
            interaction_processing_micros INTEGER NOT NULL DEFAULT 0,presentation_micros INTEGER NOT NULL DEFAULT 0,
            loaf_count INTEGER NOT NULL DEFAULT 0,blocking_micros INTEGER NOT NULL DEFAULT 0,
            loaf_max_micros INTEGER NOT NULL DEFAULT 0,script_micros INTEGER NOT NULL DEFAULT 0,
            render_micros INTEGER NOT NULL DEFAULT 0,long_task_count INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(instance,path,minute))"""
        internal val APP_STATE_REVISION_TABLE_SQL = """CREATE TABLE app_state_revision(
            revision INTEGER PRIMARY KEY AUTOINCREMENT,
            committed_at INTEGER NOT NULL,
            namespace TEXT NOT NULL,
            source TEXT NOT NULL DEFAULT 'app')"""
        internal val APP_STATE_NAMESPACE_TABLE_SQL = """CREATE TABLE app_state_namespace(
            namespace TEXT PRIMARY KEY,
            imported_at INTEGER NOT NULL,
            legacy_name TEXT NOT NULL DEFAULT '')"""
        internal val APP_STATE_TABLE_SQL = """CREATE TABLE app_state(
            namespace TEXT NOT NULL,
            state_key TEXT NOT NULL,
            value_type TEXT NOT NULL,
            value_text TEXT,
            updated_at INTEGER NOT NULL,
            revision INTEGER NOT NULL,
            PRIMARY KEY(namespace,state_key),
            FOREIGN KEY(revision) REFERENCES app_state_revision(revision))"""
        private val FINGERPRINT = Regex("^[a-f0-9]{16}$")
        private val DATABASE_MIGRATION_LOCK = Any()

        // TODO(v1.0): Remove LEGACY_DATABASE_NAME, databaseName(), checkpointLegacyDatabase(),
        // migrateDatabaseFiles() and their tests. The 0.9.x line is the supported rename runway;
        // 1.0 should open ha-paneld.db directly and treat a missing database as a fresh install.
        private fun databaseName(context: Context): String = synchronized(DATABASE_MIGRATION_LOCK) {
            val target = context.getDatabasePath(DATABASE_NAME)
            if (target.exists()) return@synchronized DATABASE_NAME
            val legacy = context.getDatabasePath(LEGACY_DATABASE_NAME)
            if (!legacy.exists()) return@synchronized DATABASE_NAME
            if (checkpointLegacyDatabase(legacy) && migrateDatabaseFiles(legacy, target)) {
                DATABASE_NAME
            } else {
                LEGACY_DATABASE_NAME
            }
        }

        private fun checkpointLegacyDatabase(database: File): Boolean = runCatching {
            SQLiteDatabase.openDatabase(
                database.path,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ).use { opened ->
                opened.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(0) == 0
                }
            }
        }.getOrDefault(false)
    }
}

/**
 * Rename a closed SQLite database and all live sidecars as one best-effort file set. A failed move
 * rolls completed renames back and leaves callers on the legacy name rather than creating an empty DB.
 */
internal fun migrateDatabaseFiles(legacy: File, target: File): Boolean {
    val suffixes = listOf("", "-wal", "-shm", "-journal")
    val moves = suffixes.mapNotNull { suffix ->
        val source = File(legacy.path + suffix)
        if (source.exists()) source to File(target.path + suffix) else null
    }
    if (moves.isEmpty() || moves.any { (_, destination) -> destination.exists() }) return false
    if (target.parentFile?.let { it.isDirectory || it.mkdirs() } == false) return false
    val completed = ArrayList<Pair<File, File>>(moves.size)
    for ((source, destination) in moves) {
        if (!source.renameTo(destination)) {
            completed.asReversed().forEach { (old, renamed) -> renamed.renameTo(old) }
            return false
        }
        completed += source to destination
    }
    return true
}

/** Heat-map percentiles are diagnostic context, not control state. Recompute them at most once per
 * five minutes while the Entities tab is open; exact per-page values remain current. */
internal const val ENTITY_RANKING_REFRESH_MS = 5L * 60_000

internal data class EntityExportPolicy(
    val maxRows: Int = 50_000,
    val maxBytes: Long = 16L * 1024 * 1024,
    val maxDurationNanos: Long = 30_000_000_000L,
    val footerReserveBytes: Long = 256L,
) {
    init {
        require(maxRows in 1..50_000)
        require(maxBytes in 1_024L..64L * 1024 * 1024)
        require(maxDurationNanos > 0L)
        require(footerReserveBytes in 64L until maxBytes)
    }
}

internal data class EntityExportResult(val rows: Int, val bytes: Long, val truncationReason: String?)

/** Retains only one requested page while scanning a globally ordered ID cursor. The caller-owned
 * include/exclude sets are referenced rather than copied, so heap growth is O(existing sets + page). */
internal class BoundedEntityIdPageCollector(
    private val limit: Int,
    private val offset: Int,
    private val includeIds: Set<String>?,
    private val excludeIds: Set<String>,
) {
    init { require(limit > 0); require(offset >= 0) }
    private val retained = ArrayList<String>(limit)
    var total: Int = 0
        private set

    val pageIds: List<String> get() = retained.toList()
    internal fun retainedCountForTest(): Int = retained.size

    fun offer(entityId: String) {
        if ((includeIds != null && entityId !in includeIds) || entityId in excludeIds) return
        if (total >= offset && retained.size < limit) retained += entityId
        total++
    }
}

internal fun entityExportHeader(instanceHash: String, path: String, now: Long): String =
    """{"kind":"ha-paneld-entity-catalog","exported_at":$now,"instance_hash":${JSONObject.quote(instanceHash)},"dashboard":${JSONObject.quote(path)},"entities":["""

internal fun entityExportFooter(rows: Int, truncationReason: String?): String =
    """],"exported_count":$rows,"truncated":${truncationReason != null},"truncation_reason":${truncationReason?.let(JSONObject::quote) ?: "null"}}"""

internal fun exportTruncationReason(
    policy: EntityExportPolicy,
    rows: Int,
    bytesWritten: Long,
    nextRowBytes: Long,
    elapsedNanos: Long,
): String? = when {
    rows >= policy.maxRows -> "row_limit"
    elapsedNanos > policy.maxDurationNanos -> "time_limit"
    bytesWritten + nextRowBytes + policy.footerReserveBytes > policy.maxBytes -> "byte_limit"
    else -> null
}

private fun String.utf8Size(): Long = toByteArray(Charsets.UTF_8).size.toLong()

private fun forwardElapsedNanos(now: Long, started: Long): Long =
    if (now >= started) now - started else Long.MAX_VALUE

internal enum class RollupPressureTier {
    HOURLY_DETAIL,
    DAY_SUMMARY,
    HOUR_SUMMARY,
    DROP_DAY_HISTORY,
    DROP_HOUR_HISTORY,
}

internal data class RollupRewriteWindow(
    val whereSql: String,
    val whereArgs: Array<String>,
    val targetMinute: Long? = null,
    val drop: Boolean = false,
)

/** Deterministic degradation order for derived telemetry. Recent precision is surrendered before a
 * longer window is discarded, and 1-minute evidence is the last tier retained. */
internal object RollupRetentionPolicy {
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60L * MINUTE_MS

    fun nextTier(observedBytes: Long, softLimitBytes: Long, appliedTiers: Int): RollupPressureTier? =
        if (observedBytes > softLimitBytes) RollupPressureTier.entries.getOrNull(appliedTiers) else null

    fun window(tier: RollupPressureTier, now: Long): RollupRewriteWindow {
        val oneMinute = ((now - MINUTE_MS) / MINUTE_MS).coerceAtLeast(0L)
        val oneHour = ((now - HOUR_MS) / MINUTE_MS).coerceAtLeast(0L)
        return when (tier) {
            RollupPressureTier.HOURLY_DETAIL -> RollupRewriteWindow("minute<?", arrayOf(oneHour.toString()))
            RollupPressureTier.DAY_SUMMARY -> RollupRewriteWindow(
                "minute<?", arrayOf(oneHour.toString()), targetMinute = (oneHour - 1L).coerceAtLeast(0L),
            )
            RollupPressureTier.HOUR_SUMMARY -> RollupRewriteWindow(
                "minute>=? AND minute<?", arrayOf(oneHour.toString(), oneMinute.toString()),
                targetMinute = (oneMinute - 1L).coerceAtLeast(0L),
            )
            RollupPressureTier.DROP_DAY_HISTORY -> RollupRewriteWindow(
                "minute<?", arrayOf(oneHour.toString()), drop = true,
            )
            RollupPressureTier.DROP_HOUR_HISTORY -> RollupRewriteWindow(
                "minute<?", arrayOf(oneMinute.toString()), drop = true,
            )
        }
    }
}

/** A bounded cumulative distribution over positive Long values. */
internal class LogRankHistogram {
    private val buckets = LongArray(Long.SIZE_BITS * BUCKETS_PER_OCTAVE)
    private var total = 0L

    fun add(value: Long) {
        if (value <= 0L) return
        buckets[bucket(value)]++
        total++
    }

    fun rank(value: Long): Double {
        if (value <= 0L || total == 0L) return 0.0
        var cumulative = 0L
        for (index in 0..bucket(value)) cumulative += buckets[index]
        return cumulative.toDouble() / total
    }

    internal fun sampleCountForTest(): Long = total
    internal fun storageSlotsForTest(): Int = buckets.size

    private fun bucket(value: Long): Int {
        val exponent = Long.SIZE_BITS - 1 - java.lang.Long.numberOfLeadingZeros(value)
        val base = 1L shl exponent
        val step = (base / BUCKETS_PER_OCTAVE).coerceAtLeast(1L)
        val subBucket = ((value - base) / step).coerceAtMost((BUCKETS_PER_OCTAVE - 1).toLong()).toInt()
        return exponent * BUCKETS_PER_OCTAVE + subBucket
    }

    private companion object { const val BUCKETS_PER_OCTAVE = 4 }
}

/** Wall-clock maintenance admission. Clock rollback is treated as due rather than suppressing work. */
internal class MaintenanceIntervalGate(private val intervalMs: Long) {
    init { require(intervalMs > 0L) }

    private var lastAdmittedAt = Long.MIN_VALUE

    @Synchronized fun admit(now: Long): Boolean {
        if (lastAdmittedAt != Long.MIN_VALUE && now >= lastAdmittedAt && now - lastAdmittedAt < intervalMs) {
            return false
        }
        lastAdmittedAt = now
        return true
    }
}

internal data class EntitySortProjection(
    val entityId: String,
    val access1h: Long,
    val rate1h: Double,
    val reasons: String,
    val lastAccess: Long,
    val override: String,
)

/** Public API sort policy: unknown keys/directions never become SQL or reflection input. */
internal object EntityCatalogSorting {
    private val keys = setOf("entity_id", "access_1h", "rate_1h_bps", "reasons", "last_access", "override")
    fun key(raw: String): String = raw.takeIf(keys::contains) ?: "entity_id"
    fun direction(raw: String): String = raw.lowercase().takeIf { it == "asc" || it == "desc" } ?: "asc"

    /** Validated SQL order. Recent aliases are produced by the bounded aggregate join in the list
     * query, so every ordering is globally ranked before LIMIT/OFFSET. */
    fun sqlOrder(rawKey: String, rawDirection: String): String? {
        val normalizedKey = key(rawKey)
        val dir = direction(rawDirection).uppercase()
        val expression = when (normalizedKey) {
            "entity_id" -> return "e.entity_id COLLATE NOCASE $dir, e.entity_id $dir"
            "reasons" -> "coalesce(m.reasons,'') COLLATE NOCASE"
            "last_access" -> "coalesce(m.last_access,0)"
            "override" -> "CASE WHEN coalesce(m.excluded,0)=1 THEN 'excluded' WHEN coalesce(m.pinned,0)=1 THEN 'pinned' ELSE 'auto' END COLLATE NOCASE"
            "access_1h" -> "recent_access_1h"
            "rate_1h_bps" -> "recent_rate_1h"
            else -> return null
        }
        return "$expression $dir, e.entity_id COLLATE NOCASE ASC, e.entity_id ASC"
    }

    fun <T> comparator(rawKey: String, rawDirection: String, projection: (T) -> EntitySortProjection): Comparator<T> {
        val key = key(rawKey)
        val sign = if (direction(rawDirection) == "desc") -1 else 1
        return Comparator { left, right ->
            val a = projection(left); val b = projection(right)
            val primary = when (key) {
                "access_1h" -> a.access1h.compareTo(b.access1h)
                "rate_1h_bps" -> a.rate1h.compareTo(b.rate1h)
                "reasons" -> a.reasons.compareTo(b.reasons, ignoreCase = true)
                "last_access" -> a.lastAccess.compareTo(b.lastAccess)
                "override" -> a.override.compareTo(b.override, ignoreCase = true)
                else -> compareEntityIds(a.entityId, b.entityId)
            } * sign
            if (primary != 0) primary else compareEntityIds(a.entityId, b.entityId)
        }
    }

    private fun compareEntityIds(a: String, b: String): Int =
        a.compareTo(b, ignoreCase = true).takeIf { it != 0 } ?: a.compareTo(b)
}

/** Thread-safe fixed-window cache. Loader executes once per key/window and old keys are strictly bounded. */
internal class BoundedSnapshotCache<K, V>(private val windowMs: Long, private val maxEntries: Int) {
    init { require(windowMs > 0); require(maxEntries > 0) }
    private data class Entry<V>(val createdAt: Long, val value: V)
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)

    @Synchronized fun get(key: K, now: Long, loader: () -> V): V {
        // A dashboard switch must not retain every other dashboard's full-catalog rollup/rank arrays
        // after their 10-second window. Clock rollback also expires rather than pinning stale entries.
        entries.entries.removeAll { (_, entry) ->
            now < entry.createdAt || now - entry.createdAt >= windowMs
        }
        entries[key]?.takeIf { now >= it.createdAt && now - it.createdAt < windowMs }?.let { return it.value }
        val value = loader()
        entries[key] = Entry(now, value)
        while (entries.size > maxEntries) entries.remove(entries.entries.first().key)
        return value
    }

    @Synchronized fun invalidate(key: K) { entries.remove(key) }
    @Synchronized internal fun sizeForTest(): Int = entries.size
}

/** Sequential forward-only schema contract. Never add an ad-hoc conditional to [onUpgrade]. */
object EntityCatalogSchema {
    const val CURRENT_VERSION = 9
    data class Step(val from: Int, val to: Int, val sql: List<String>)

    private val steps = mapOf(
        1 to Step(
            1, 2,
            listOf("ALTER TABLE dashboard ADD COLUMN sync_generation INTEGER NOT NULL DEFAULT 0"),
        ),
        2 to Step(
            2, 3,
            listOf(
                "ALTER TABLE membership ADD COLUMN rate_window_start INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE membership ADD COLUMN rate_update_bytes INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE membership ADD COLUMN last_update_at INTEGER NOT NULL DEFAULT 0",
            ),
        ),
        3 to Step(
            3, 4,
            listOf(
                """CREATE TABLE minute_rollup(
                    instance TEXT NOT NULL, path TEXT NOT NULL, entity_id TEXT NOT NULL, minute INTEGER NOT NULL,
                    access_count INTEGER NOT NULL DEFAULT 0, update_count INTEGER NOT NULL DEFAULT 0,
                    update_bytes INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(instance,path,entity_id,minute))""",
                "CREATE INDEX minute_rollup_age ON minute_rollup(instance,path,minute)",
            ),
        ),
        4 to Step(
            4, 5,
            listOf("ALTER TABLE dashboard ADD COLUMN issues_json TEXT NOT NULL DEFAULT '[]'"),
        ),
        5 to Step(
            5, 6,
            listOf(
                """CREATE TABLE dashboard_issue_ignore(
                    instance TEXT NOT NULL, path TEXT NOT NULL, fingerprint TEXT NOT NULL, ignored_at INTEGER NOT NULL,
                    PRIMARY KEY(instance,path,fingerprint),
                    FOREIGN KEY(instance,path) REFERENCES dashboard(instance,path) ON DELETE CASCADE)""",
            ),
        ),
        6 to Step(
            6, 7,
            listOf(
                "DROP TABLE IF EXISTS hourly",
                "ALTER TABLE minute_rollup ADD COLUMN span_start INTEGER NOT NULL DEFAULT 0",
            ),
        ),
        7 to Step(
            7, 8,
            listOf(
                EntityCatalogStore.PERFORMANCE_HISTORY_TABLE_SQL,
                "CREATE INDEX dashboard_performance_age ON dashboard_performance(minute)",
            ),
        ),
        8 to Step(
            8, 9,
            listOf(
                EntityCatalogStore.APP_STATE_REVISION_TABLE_SQL,
                EntityCatalogStore.APP_STATE_NAMESPACE_TABLE_SQL,
                EntityCatalogStore.APP_STATE_TABLE_SQL,
                "CREATE INDEX app_state_updated ON app_state(namespace,updated_at)",
            ),
        ),
    )

    fun plan(oldVersion: Int, newVersion: Int): List<Step> {
        require(oldVersion >= 1) { "invalid old schema version" }
        require(newVersion >= oldVersion) { "schema downgrade is unsupported" }
        val out = mutableListOf<Step>()
        var current = oldVersion
        while (current < newVersion) {
            val step = steps[current] ?: error("missing entity catalog migration $current -> ${current + 1}")
            require(step.to == current + 1) { "non-sequential entity catalog migration" }
            out += step; current = step.to
        }
        return out
    }
}

/** Keeps derived issue diagnostics small even when a dashboard or parser produces hostile input. */
internal object EntityCatalogIssuePersistence {
    const val MAX_ISSUE_GROUPS = 64
    const val MAX_SOURCES_PER_GROUP = 8
    const val MAX_PAYLOAD_BYTES = 64 * 1024
    private const val MAX_ARRAY_ITEMS = 16
    private const val MAX_OBJECT_KEYS = 24
    private const val MAX_KEY_CHARS = 100
    private const val MAX_STRING_CHARS = 500
    private const val MAX_DEPTH = 4

    fun boundedJson(issues: List<JSONObject>): String {
        val out = JSONArray()
        for (issue in issues.take(MAX_ISSUE_GROUPS)) {
            val sanitized = sanitizeObject(issue, 0)
            out.put(sanitized)
            if (out.toString().toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) {
                out.remove(out.length() - 1)
            }
        }
        return out.toString()
    }

    fun boundExistingJson(raw: String): String = runCatching {
        val array = JSONArray(raw)
        boundedJson(buildList {
            for (index in 0 until array.length()) array.optJSONObject(index)?.let(::add)
        })
    }.getOrDefault("[]")

    fun counts(raw: String): Pair<Int, Int> = runCatching {
        val array = JSONArray(boundExistingJson(raw))
        var blocking = 0
        for (index in 0 until array.length()) {
            if (array.optJSONObject(index)?.optBoolean("blocking", false) == true) blocking++
        }
        array.length() to blocking
    }.getOrDefault(0 to 0)

    fun ignoredCount(raw: String): Int = runCatching {
        val array = JSONArray(boundExistingJson(raw))
        (0 until array.length()).count { array.optJSONObject(it)?.optBoolean("ignored", false) == true }
    }.getOrDefault(0)

    fun applyIgnores(issues: JSONArray, ignoredFingerprints: Set<String>): String {
        val effective = buildList {
            for (index in 0 until issues.length()) issues.optJSONObject(index)?.let { source ->
                val issue = JSONObject(source.toString())
                val ignored = issue.optString("fingerprint") in ignoredFingerprints
                val wouldBlock = issue.optBoolean("would_block", issue.optBoolean("blocking", false))
                issue.put("ignored", ignored)
                issue.put("would_block", wouldBlock)
                issue.put("blocking", wouldBlock && !ignored)
                if (wouldBlock) issue.put("severity", if (ignored) "warning" else "error")
                add(issue)
            }
        }
        return boundedJson(effective)
    }

    private fun sanitizeObject(input: JSONObject, depth: Int): JSONObject = JSONObject().apply {
        if (depth >= MAX_DEPTH) return@apply
        input.keys().asSequence().toList().sorted().take(MAX_OBJECT_KEYS).forEach { rawKey ->
            val key = rawKey.take(MAX_KEY_CHARS)
            sanitizeValue(input.opt(rawKey), depth + 1, key)?.let { put(key, it) }
        }
    }

    private fun sanitizeValue(value: Any?, depth: Int, key: String): Any? = when {
        value == null || value === JSONObject.NULL -> JSONObject.NULL
        value is String -> value.take(MAX_STRING_CHARS)
        value is Boolean || value is Number -> value
        depth >= MAX_DEPTH -> null
        value is JSONObject -> sanitizeObject(value, depth)
        value is JSONArray -> JSONArray().apply {
            val limit = if (key == "sources" || key == "source_locations") MAX_SOURCES_PER_GROUP else MAX_ARRAY_ITEMS
            for (index in 0 until minOf(value.length(), limit)) {
                sanitizeValue(value.opt(index), depth + 1, key)?.let { put(it) }
            }
        }
        else -> value.toString().take(MAX_STRING_CHARS)
    }
}
