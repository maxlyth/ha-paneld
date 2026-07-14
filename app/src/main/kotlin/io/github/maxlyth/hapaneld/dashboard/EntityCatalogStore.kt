package io.github.maxlyth.hapaneld.dashboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteException
import org.json.JSONArray
import org.json.JSONObject

/** Bounded, derived entity/catalog evidence. Credentials are never stored here. */
class EntityCatalogStore(context: Context) : SQLiteOpenHelper(context, "entity-learning.db", null, VERSION) {
    init {
        // Use the helper-level API so WAL is enabled before the database is opened. Calling
        // SQLiteDatabase.enableWriteAheadLogging() from onConfigure is not supported consistently on
        // the Android 8-era SQLite builds used by several target panels.
        setWriteAheadLoggingEnabled(true)
    }

    data class StateRow(val entityId: String, val state: String)
    data class Snapshot(
        val state: String,
        val lastSyncAt: Long,
        val catalogCount: Int,
        val activeCount: Int,
        val unresolvedCount: Int,
        val issueCount: Int,
        val blockingIssueCount: Int,
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
        db.execSQL("""CREATE TABLE hourly(
            instance TEXT NOT NULL, path TEXT NOT NULL, entity_id TEXT NOT NULL, hour INTEGER NOT NULL,
            access_count INTEGER NOT NULL DEFAULT 0, update_count INTEGER NOT NULL DEFAULT 0,
            update_bytes INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(instance,path,entity_id,hour))""")
        db.execSQL("""CREATE TABLE minute_rollup(
            instance TEXT NOT NULL, path TEXT NOT NULL, entity_id TEXT NOT NULL, minute INTEGER NOT NULL,
            access_count INTEGER NOT NULL DEFAULT 0, update_count INTEGER NOT NULL DEFAULT 0,
            update_bytes INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(instance,path,entity_id,minute))""")
        db.execSQL("CREATE INDEX entity_missing ON entity(instance,missing_streak)")
        db.execSQL("CREATE INDEX membership_load ON membership(instance,path,update_bytes DESC)")
        db.execSQL("CREATE INDEX hourly_age ON hourly(hour)")
        db.execSQL("CREATE INDEX minute_rollup_age ON minute_rollup(instance,path,minute)")
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
            val cutoff = now - TOMBSTONE_RETENTION_MS
            db.execSQL("DELETE FROM entity WHERE instance=? AND tombstone_at>0 AND tombstone_at<?", arrayOf(instance, cutoff))
            db.execSQL("DELETE FROM hourly WHERE hour<?", arrayOf((now - ROLLUP_RETENTION_MS) / HOUR_MS))
            db.execSQL("DELETE FROM minute_rollup WHERE minute<?", arrayOf((now - MINUTE_RETENTION_MS) / MINUTE_MS))
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
                db.execSQL("INSERT OR IGNORE INTO hourly(instance,path,entity_id,hour) VALUES(?,?,?,?)", arrayOf(instance, path, id, now / HOUR_MS))
                db.execSQL(
                    "UPDATE hourly SET access_count=access_count+? WHERE instance=? AND path=? AND entity_id=? AND hour=?",
                    arrayOf(count, instance, path, id, now / HOUR_MS),
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
                db.execSQL("INSERT OR IGNORE INTO hourly(instance,path,entity_id,hour) VALUES(?,?,?,?)", arrayOf(instance, path, id, now / HOUR_MS))
                db.execSQL("UPDATE hourly SET update_count=update_count+?,update_bytes=update_bytes+? WHERE instance=? AND path=? AND entity_id=? AND hour=?",
                    arrayOf(metric.first, metric.second, instance, path, id, now / HOUR_MS))
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
            db.delete("hourly", "instance=? AND path=?", arrayOf(instance, path))
            db.delete("membership", "instance=? AND path=?", arrayOf(instance, path))
            db.delete("dashboard", "instance=? AND path=?", arrayOf(instance, path))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
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

    fun hasEntity(instance: String, entityId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM entity WHERE instance=? AND entity_id=? AND missing_streak<3", arrayOf(instance, entityId),
    ).use { it.moveToFirst() }

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
            dashboard?.error ?: "",
            databaseBytes(),
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
        maxLimit: Int = 500,
        includeIds: Set<String>? = null,
        excludeIds: Set<String> = emptySet(),
    ): String {
        val where = mutableListOf("e.instance=?")
        val args = mutableListOf(path, instance)
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
        val join = "LEFT JOIN membership m ON m.instance=e.instance AND m.entity_id=e.entity_id AND m.path=?"
        val sql = """SELECT e.entity_id,e.state,e.metadata_json,e.first_seen,e.last_seen,e.missing_streak,
            coalesce(m.static_ref,0),coalesce(m.runtime_ref,0),coalesce(m.pinned,0),coalesce(m.excluded,0),
            coalesce(m.reasons,''),coalesce(m.last_access,0),coalesce(m.access_count,0),coalesce(m.update_count,0),coalesce(m.update_bytes,0),
            coalesce(m.rate_window_start,0),coalesce(m.rate_update_bytes,0),coalesce(m.last_update_at,0)
            FROM entity e $join WHERE ${where.joinToString(" AND ")}
            ORDER BY m.update_bytes DESC,e.entity_id LIMIT ? OFFSET ?"""
        val effectiveLimit = limit.coerceIn(1, maxLimit)
        val effectiveOffset = offset.coerceAtLeast(0)
        val queryArgs = args.toTypedArray()
        val filterInMemory = includeIds != null || excludeIds.isNotEmpty()
        val queryTotal = if (!filterInMemory) {
            readableDatabase.rawQuery(
                "SELECT count(*) FROM entity e $join WHERE ${where.joinToString(" AND ")}",
                queryArgs,
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        } else null
        if (!filterInMemory) {
            args += effectiveLimit.toString(); args += effectiveOffset.toString()
        }
        val effectiveSql = if (!filterInMemory) sql else sql.substringBefore(" LIMIT ? OFFSET ?")
        val rows = JSONArray()
        val now = System.currentTimeMillis()
        val recent = recentStats(instance, path, now)
        val ranks = RecentRanks(recent.values)
        var matched = 0
        readableDatabase.rawQuery(effectiveSql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                if (includeIds != null && c.getString(0) !in includeIds) continue
                if (c.getString(0) in excludeIds) continue
                val position = matched++
                if (filterInMemory && (position < effectiveOffset || rows.length() >= effectiveLimit)) continue
                rows.put(JSONObject().apply {
                put("entity_id", c.getString(0)); put("state", c.getString(1)); put("metadata", JSONObject(c.getString(2)))
                put("first_seen", c.getLong(3)); put("last_seen", c.getLong(4)); put("missing_streak", c.getInt(5))
                put("static", c.getInt(6) != 0); put("runtime", c.getInt(7) != 0); put("pinned", c.getInt(8) != 0)
                put("excluded", c.getInt(9) != 0); put("reasons", c.getString(10)); put("last_access", c.getLong(11))
                put("access_count", c.getLong(12)); put("update_count", c.getLong(13)); put("update_bytes", c.getLong(14))
                val windowStart = c.getLong(15); val windowBytes = c.getLong(16); val lastUpdate = c.getLong(17)
                val rate = if (windowStart == 0L || lastUpdate == 0L || now - lastUpdate > RATE_STALE_MS) 0.0
                    else windowBytes * 1000.0 / (now - windowStart).coerceAtLeast(1000L)
                put("data_rate_bps", rate)
                recent[c.getString(0)]?.let { r ->
                    put("access_1m", r.access1m); put("access_1h", r.access1h); put("access_1d", r.access1d)
                    put("rate_1m_bps", r.bytes1m / observedSeconds(now, r.firstMinute, MINUTE_MS))
                    put("rate_1h_bps", r.bytes1h / observedSeconds(now, r.firstMinute, HOUR_MS))
                    put("rate_1d_bps", r.bytes1d / observedSeconds(now, r.firstMinute, 24L * HOUR_MS))
                    put("access_1m_rank", ranks.rank(r.access1m, ranks.access1m))
                    put("access_1h_rank", ranks.rank(r.access1h, ranks.access1h))
                    put("access_1d_rank", ranks.rank(r.access1d, ranks.access1d))
                    put("rate_1m_rank", ranks.rank(r.bytes1m, ranks.bytes1m))
                    put("rate_1h_rank", ranks.rank(r.bytes1h, ranks.bytes1h))
                    put("rate_1d_rank", ranks.rank(r.bytes1d, ranks.bytes1d))
                } ?: run {
                    put("access_1m", 0); put("access_1h", 0); put("access_1d", 0)
                    put("rate_1m_bps", 0); put("rate_1h_bps", 0); put("rate_1d_bps", 0)
                    put("access_1m_rank", 0); put("access_1h_rank", 0); put("access_1d_rank", 0)
                    put("rate_1m_rank", 0); put("rate_1h_rank", 0); put("rate_1d_rank", 0)
                }
                })
            }
        }
        return JSONObject().put("items", rows).put("limit", effectiveLimit).put("offset", effectiveOffset)
            .put("total", queryTotal ?: matched).toString()
    }

    fun exportJson(instance: String, path: String): String = JSONObject()
        .put("kind", "ha-paneld-entity-catalog").put("exported_at", System.currentTimeMillis())
        .put("instance_hash", EntityLearningProtocol.hash(instance)).put("dashboard", path)
        .put("entities", JSONObject(entitiesJson(instance, path, "", "all", 50_000, 0, 50_000)).getJSONArray("items"))
        .toString()

    /**
     * Retention is evidence-first: old rollups and tombstones go before inactive entity detail. The
     * active set and all manual overrides are never removed by size maintenance. SQLite may keep freed
     * pages for reuse, so status reports live pages rather than the physical high-water mark.
     */
    private fun maintainSoftLimit(now: Long) {
        val db = writableDatabase
        if (databaseBytes(db) <= SOFT_LIMIT_BYTES) return
        db.execSQL("DELETE FROM hourly WHERE hour<?", arrayOf((now - 7L * 24 * HOUR_MS) / HOUR_MS))
        db.execSQL("DELETE FROM minute_rollup WHERE minute<?", arrayOf((now - MINUTE_RETENTION_MS) / MINUTE_MS))
        db.execSQL("DELETE FROM entity WHERE tombstone_at>0")
        db.execSQL(
            """UPDATE entity SET attributes_json='{}',metadata_json='{}' WHERE (instance,entity_id) NOT IN
               (SELECT instance,entity_id FROM membership WHERE pinned=1 OR static_ref=1 OR runtime_ref=1)""",
        )
        if (databaseBytes(db) > SOFT_LIMIT_BYTES) {
            db.execSQL("DELETE FROM hourly")
            db.execSQL(
                """UPDATE entity SET attributes_json='{}' WHERE (instance,entity_id) NOT IN
                   (SELECT instance,entity_id FROM membership WHERE pinned=1 OR static_ref=1 OR last_access>=?)""",
                arrayOf(now - RUNTIME_RETENTION_MS),
            )
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

    private data class DashboardRow(
        val status: String,
        val lastSync: Long,
        val error: String,
        val unresolvedJson: String,
        val issuesJson: String,
    )

    /** Percentile ranks across every entity with evidence, independently for each time window. */
    private class RecentRanks(rows: Collection<Recent>) {
        val access1m = rows.map { it.access1m }.filter { it > 0 }.sorted()
        val access1h = rows.map { it.access1h }.filter { it > 0 }.sorted()
        val access1d = rows.map { it.access1d }.filter { it > 0 }.sorted()
        val bytes1m = rows.map { it.bytes1m }.filter { it > 0 }.sorted()
        val bytes1h = rows.map { it.bytes1h }.filter { it > 0 }.sorted()
        val bytes1d = rows.map { it.bytes1d }.filter { it > 0 }.sorted()

        fun rank(value: Long, sorted: List<Long>): Double {
            if (value <= 0 || sorted.isEmpty()) return 0.0
            var low = 0; var high = sorted.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (sorted[mid] <= value) low = mid + 1 else high = mid
            }
            return low.toDouble() / sorted.size
        }
    }

    private fun recentStats(instance: String, path: String, now: Long): Map<String, Recent> {
        val minute = now / MINUTE_MS
        val oneMinute = (now - MINUTE_MS) / MINUTE_MS
        val oneHour = (now - HOUR_MS) / MINUTE_MS
        val oneDay = (now - 24L * HOUR_MS) / MINUTE_MS
        return readableDatabase.rawQuery(
            """SELECT entity_id,
               sum(CASE WHEN minute>=? THEN access_count ELSE 0 END),
               sum(CASE WHEN minute>=? THEN access_count ELSE 0 END),sum(access_count),
               sum(CASE WHEN minute>=? THEN update_bytes ELSE 0 END),
               sum(CASE WHEN minute>=? THEN update_bytes ELSE 0 END),sum(update_bytes),min(minute)
               FROM minute_rollup WHERE instance=? AND path=? AND minute>=? GROUP BY entity_id""",
            arrayOf(oneMinute.toString(), oneHour.toString(), oneMinute.toString(), oneHour.toString(), instance, path, oneDay.toString()),
        ).use { c -> buildMap {
            while (c.moveToNext()) put(c.getString(0), Recent(c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5), c.getLong(6), c.getLong(7)))
        } }
    }

    private fun observedSeconds(now: Long, firstMinute: Long, windowMs: Long): Double {
        val start = maxOf(firstMinute * MINUTE_MS, now - windowMs)
        return (now - start).coerceIn(METRIC_BATCH_MS, windowMs) / 1000.0
    }

    private fun databaseBytes(db: SQLiteDatabase = readableDatabase): Long {
        fun pragma(name: String): Long = db.rawQuery("PRAGMA $name", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
        return (pragma("page_count") - pragma("freelist_count")).coerceAtLeast(0) * pragma("page_size")
    }

    companion object {
        private const val VERSION = EntityCatalogSchema.CURRENT_VERSION
        private const val HOUR_MS = 3_600_000L
        private const val MINUTE_MS = 60_000L
        private const val ROLLUP_RETENTION_MS = 30L * 24 * HOUR_MS
        private const val RUNTIME_RETENTION_MS = 30L * 24 * HOUR_MS
        private const val TOMBSTONE_RETENTION_MS = 30L * 24 * HOUR_MS
        private const val SOFT_LIMIT_BYTES = 128L * 1024 * 1024
        private const val RATE_WINDOW_MS = 5L * 60_000
        private const val RATE_STALE_MS = 90_000L
        private const val METRIC_BATCH_MS = 5_000L
        private const val MINUTE_RETENTION_MS = 2L * 24 * HOUR_MS
    }
}

/** Sequential forward-only schema contract. Never add an ad-hoc conditional to [onUpgrade]. */
object EntityCatalogSchema {
    const val CURRENT_VERSION = 5
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
