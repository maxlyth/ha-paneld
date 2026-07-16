package io.github.maxlyth.hapaneld.persistence

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import io.github.maxlyth.hapaneld.dashboard.EntityCatalogStore
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory

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
        val process = processes.getOrCreate(appContext.packageName) {
            ProcessState(
                helper = EntityCatalogStore(appContext),
                executor = Executors.newSingleThreadExecutor(StateWriterThreadFactory()),
            )
        }
        return process.stores.getOrCreate(namespace) {
            val legacy = appContext.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
            SqliteStatePreferences(
                SqliteNamespacePersistence(process.helper, namespace, legacyName, legacy),
                process.executor,
            )
        }
    }

    private data class ProcessState(
        val helper: EntityCatalogStore,
        val executor: ExecutorService,
        val stores: AtomicFactoryCache<String, SharedPreferences> = AtomicFactoryCache(),
    )

    private class StateWriterThreadFactory : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "ha-paneld-state-writer").apply { isDaemon = true }
    }

    private val processes = AtomicFactoryCache<String, ProcessState>()
}

internal class AtomicFactoryCache<K : Any, V : Any> {
    private val values = ConcurrentHashMap<K, V>()

    fun getOrCreate(key: K, factory: () -> V): V =
        values.computeIfAbsent(key) { factory() }
}

internal class SqliteStatePreferences(
    private val persistence: StateNamespacePersistence,
    executor: ExecutorService,
) : SharedPreferences {
    constructor(
        helper: EntityCatalogStore,
        namespace: String,
        legacyName: String,
        legacy: SharedPreferences,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        SqliteNamespacePersistence(helper, namespace, legacyName, legacy, clock),
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ha-paneld-state-test-writer").apply { isDaemon = true }
        },
    )

    private val writeQueue = OrderedStateWriteQueue(executor, persistence)
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val stateLock = Any()
    private val values: MutableMap<String, Any> = persistence.initialize().toMutableMap()

    override fun getAll(): Map<String, *> = synchronized(stateLock) { values.toMap() }

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
            publish(waitForDisk = false)
        }

        override fun commit(): Boolean = publish(waitForDisk = true)

        private fun publish(waitForDisk: Boolean): Boolean {
            if (!clear && changes.isEmpty()) return true
            val pending = synchronized(stateLock) {
                val before = values.toMap()
                if (clear) values.clear()
                changes.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
                val changed = (before.keys + values.keys)
                    .filterTo(linkedSetOf()) { before[it] != values[it] }
                PendingStateWrite(
                    changedKeys = changed,
                    future = writeQueue.enqueue(
                        StateMutation(clear, changes.toMap()),
                        values.toMap(),
                    ),
                )
            }
            pending.changedKeys.forEach { key ->
                listeners.forEach { it.onSharedPreferenceChanged(this@SqliteStatePreferences, key) }
            }
            return if (waitForDisk) pending.future?.awaitResult() ?: false else true
        }

        private fun stage(key: String, value: Any?): SharedPreferences.Editor = apply {
            changes[key] = value
        }
    }

    private fun typeError(key: String, expected: String, value: Any): Nothing =
        throw ClassCastException("$key is ${value.javaClass.simpleName}, expected $expected")
}

internal data class StateMutation(
    val clear: Boolean,
    val changes: Map<String, Any?>,
)

private data class PendingStateWrite(
    val changedKeys: Set<String>,
    val future: Future<Boolean>?,
)

internal interface StateNamespacePersistence {
    fun initialize(): Map<String, Any>
    fun persist(mutation: StateMutation): Boolean
    fun replace(snapshot: Map<String, Any>): Boolean
}

internal class OrderedStateWriteQueue(
    private val executor: ExecutorService,
    private val persistence: StateNamespacePersistence,
) {
    // Accessed only by the single ordered executor. A failed asynchronous write leaves memory ahead
    // of disk; the next queued write repairs the namespace from its complete post-mutation snapshot.
    private var reconcileRequired = false

    fun enqueue(mutation: StateMutation, snapshot: Map<String, Any>): Future<Boolean>? = runCatching {
        executor.submit<Boolean> {
            val succeeded = runCatching {
                if (reconcileRequired) persistence.replace(snapshot) else persistence.persist(mutation)
            }.getOrDefault(false)
            reconcileRequired = !succeeded
            succeeded
        }
    }.getOrNull()
}

private fun Future<Boolean>.awaitResult(): Boolean =
    runCatching { get() }.getOrDefault(false)

private class SqliteNamespacePersistence(
    private val helper: EntityCatalogStore,
    private val namespace: String,
    private val legacyName: String,
    private val legacy: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) : StateNamespacePersistence {
    override fun initialize(): Map<String, Any> {
        require(namespace.isNotBlank()) { "state namespace must not be blank" }
        importLegacyOnce()
        return loadAll()
    }

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

    override fun persist(mutation: StateMutation): Boolean = writeTransaction {
        val revision = insertRevision(this, "app")
        if (mutation.clear) delete("app_state", "namespace=?", arrayOf(namespace))
        mutation.changes.forEach { (key, value) ->
            if (value == null) {
                delete("app_state", "namespace=? AND state_key=?", arrayOf(namespace, key))
            } else {
                putValue(this, key, value, revision)
            }
        }
        pruneRevisions(this)
    }

    override fun replace(snapshot: Map<String, Any>): Boolean = writeTransaction {
        val revision = insertRevision(this, "app-reconcile")
        delete("app_state", "namespace=?", arrayOf(namespace))
        snapshot.forEach { (key, value) -> putValue(this, key, value, revision) }
        pruneRevisions(this)
    }

    private fun writeTransaction(block: SQLiteDatabase.() -> Unit): Boolean = runCatching {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        true
    }.getOrDefault(false)

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

    private fun pruneRevisions(db: SQLiteDatabase) {
        db.execSQL(
            """DELETE FROM app_state_revision
               WHERE namespace=? AND revision NOT IN (
                   SELECT revision FROM app_state WHERE namespace=?
               ) AND revision < (
                   SELECT coalesce(max(revision),0)-? FROM app_state_revision WHERE namespace=?
               )""",
            arrayOf(namespace, namespace, REVISION_RETENTION, namespace),
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

    private companion object {
        const val REVISION_RETENTION = 256
    }
}
