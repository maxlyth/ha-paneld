package io.github.maxlyth.hapaneld.persistence

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import io.github.maxlyth.hapaneld.dashboard.EntityCatalogStore
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Durable application state, partitioned into explicit namespaces inside ha-paneld.db.
 *
 * The SharedPreferences interface is retained as a compatibility boundary for existing typed callers;
 * XML preferences are read only for the first transactional import of each namespace.
 */
object AppState {
    // TODO(v1.0): Remove legacyName, getSharedPreferences(), importLegacyOnce(), app_state_namespace
    // and the legacy shared-preference backup exclusions. Every supported upgrade should have crossed
    // a 0.9.x release which performs this one-time import before 1.0 makes SQLite-only startup strict.
    fun preferences(context: Context, namespace: String, legacyName: String): SharedPreferences {
        val appContext = context.applicationContext
        val cacheKey = "${appContext.packageName}:$namespace"
        return stores.getOrPut(cacheKey) {
            val legacy = appContext.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
            SqliteStatePreferences(EntityCatalogStore(appContext), namespace, legacyName, legacy)
        }
    }

    private val stores = ConcurrentHashMap<String, SharedPreferences>()
}

internal class SqliteStatePreferences(
    private val helper: EntityCatalogStore,
    private val namespace: String,
    private val legacyName: String,
    private val legacy: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) : SharedPreferences {
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val stateLock = Any()
    private val values: MutableMap<String, Any>

    init {
        require(namespace.isNotBlank()) { "state namespace must not be blank" }
        importLegacyOnce()
        values = loadAll().toMutableMap()
    }

    override fun getAll(): Map<String, *> = synchronized(stateLock) { values.toMap() }

    private fun loadAll(): Map<String, Any> {
        val values = linkedMapOf<String, Any>()
        helper.readableDatabase.rawQuery(
            "SELECT state_key,value_type,value_text FROM app_state WHERE namespace=?",
            arrayOf(namespace),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                values[cursor.getString(0)] = decode(cursor.getString(1), cursor.getString(2))
            }
        }
        return values
    }

    override fun getString(key: String, defValue: String?): String? =
        value(key)?.let { it as? String ?: typeError(key, "String", it) } ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        value(key)?.let { it as? Set<String> ?: typeError(key, "Set<String>", it) } ?: defValues

    override fun getInt(key: String, defValue: Int): Int =
        value(key)?.let { it as? Int ?: typeError(key, "Int", it) } ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        value(key)?.let { it as? Long ?: typeError(key, "Long", it) } ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        value(key)?.let { it as? Float ?: typeError(key, "Float", it) } ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        value(key)?.let { it as? Boolean ?: typeError(key, "Boolean", it) } ?: defValue

    override fun contains(key: String): Boolean = synchronized(stateLock) { key in values }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners -= listener
    }

    private fun value(key: String): Any? = synchronized(stateLock) { values[key] }

    private fun importLegacyOnce() {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val imported = db.rawQuery(
                "SELECT 1 FROM app_state_namespace WHERE namespace=?",
                arrayOf(namespace),
            ).use { it.moveToFirst() }
            if (!imported) {
                val revision = insertRevision(db, "legacy:$legacyName")
                legacy.all.forEach { (key, value) -> value?.let { putValue(db, key, it, revision) } }
                db.execSQL(
                    "INSERT INTO app_state_namespace(namespace,imported_at,legacy_name) VALUES(?,?,?)",
                    arrayOf(namespace, clock(), legacyName),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private inner class Editor : SharedPreferences.Editor {
        private val changes = linkedMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = stage(key, value)
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor =
            stage(key, values?.toSet())
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = stage(key, value)
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = stage(key, value)
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = stage(key, value)
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = stage(key, value)
        override fun remove(key: String): SharedPreferences.Editor = stage(key, null)
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun apply() {
            commit()
        }

        override fun commit(): Boolean = runCatching {
            if (!clear && changes.isEmpty()) return@runCatching true
            val changed = linkedSetOf<String>()
            synchronized(stateLock) {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    val revision = insertRevision(db, "app")
                    if (clear) {
                        changed += values.keys
                        db.delete("app_state", "namespace=?", arrayOf(namespace))
                    }
                    changes.forEach { (key, value) ->
                        changed += key
                        if (value == null) {
                            db.delete("app_state", "namespace=? AND state_key=?", arrayOf(namespace, key))
                        } else {
                            putValue(db, key, value, revision)
                        }
                    }
                    db.execSQL(
                        """DELETE FROM app_state_revision
                           WHERE namespace=? AND revision NOT IN (
                               SELECT revision FROM app_state WHERE namespace=?
                           ) AND revision < (
                               SELECT coalesce(max(revision),0)-? FROM app_state_revision WHERE namespace=?
                           )""",
                        arrayOf(namespace, namespace, REVISION_RETENTION, namespace),
                    )
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                if (clear) values.clear()
                changes.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }
            changed.forEach { key ->
                listeners.forEach { it.onSharedPreferenceChanged(this@SqliteStatePreferences, key) }
            }
            true
        }.getOrDefault(false)

        private fun stage(key: String, value: Any?): SharedPreferences.Editor = apply {
            changes[key] = value
        }
    }

    private fun insertRevision(db: SQLiteDatabase, source: String): Long {
        db.execSQL(
            "INSERT INTO app_state_revision(committed_at,namespace,source) VALUES(?,?,?)",
            arrayOf(clock(), namespace, source),
        )
        return db.rawQuery("SELECT last_insert_rowid()", null).use {
            check(it.moveToFirst())
            it.getLong(0)
        }
    }

    private fun putValue(db: SQLiteDatabase, key: String, value: Any, revision: Long) {
        val (type, text) = encode(value)
        db.execSQL(
            """INSERT OR REPLACE INTO app_state(
                namespace,state_key,value_type,value_text,updated_at,revision
            ) VALUES(?,?,?,?,?,?)""",
            arrayOf(namespace, key, type, text, clock(), revision),
        )
    }

    private fun encode(value: Any): Pair<String, String> = when (value) {
        is String -> "string" to value
        is Int -> "int" to value.toString()
        is Long -> "long" to value.toString()
        is Float -> "float" to value.toString()
        is Boolean -> "boolean" to if (value) "1" else "0"
        is Set<*> -> {
            require(value.all { it is String }) { "only string sets are supported" }
            "string_set" to JSONArray(value.toList()).toString()
        }
        else -> error("unsupported state type ${value.javaClass.name}")
    }

    private fun decode(type: String, text: String?): Any = when (type) {
        "string" -> text.orEmpty()
        "int" -> text.orEmpty().toInt()
        "long" -> text.orEmpty().toLong()
        "float" -> text.orEmpty().toFloat()
        "boolean" -> text == "1"
        "string_set" -> JSONArray(text.orEmpty()).let { json ->
            buildSet { repeat(json.length()) { add(json.getString(it)) } }
        }
        else -> error("unsupported persisted state type $type")
    }

    private fun typeError(key: String, expected: String, value: Any): Nothing =
        throw ClassCastException("$key is ${value.javaClass.simpleName}, expected $expected")

    private companion object {
        const val REVISION_RETENTION = 256
    }
}
