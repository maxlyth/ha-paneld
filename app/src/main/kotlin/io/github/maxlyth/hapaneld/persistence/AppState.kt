package io.github.maxlyth.hapaneld.persistence

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import io.github.maxlyth.hapaneld.dashboard.EntityCatalogStore
import org.json.JSONArray
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Durable application state, partitioned into explicit namespaces inside ha-paneld.db.
 *
 * The SharedPreferences interface is retained as a compatibility boundary for existing typed callers;
 * XML preferences are retained as a small compatibility journal for supported 0.9.x downgrades.
 * Runtime reads use SQLite-backed state; startup imports the journal so edits made while temporarily
 * running an older build also survive the return upgrade.
 */
object AppState {
    // TODO(v1.0): Remove legacyName, getSharedPreferences(), the bridge metadata, importLegacyOnce(),
    // app_state_namespace and the legacy shared-preference backup exclusions. Every supported upgrade
    // should have crossed a 0.9.x release which performs this bridge before SQLite-only startup is strict.
    fun preferences(context: Context, namespace: String, legacyName: String): SharedPreferences {
        val appContext = context.applicationContext
        val process = process(appContext)
        return process.stores.getOrCreate(namespace) {
            val legacy = appContext.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
            SqliteStatePreferences(
                DowngradeCompatibleStatePersistence(
                    SqliteNamespacePersistence(process.helper, namespace, legacyName, legacy),
                    SharedPreferencesLegacyStateMirror(legacy),
                    SharedPreferencesBridgeMetadata(
                        appContext.getSharedPreferences(BRIDGE_PREFS, Context.MODE_PRIVATE),
                        namespace,
                    ),
                ),
                process.executor,
                process.admission,
            )
        }
    }

    /**
     * Wait until every state write admitted before this call is durable. This is used at orderly
     * service teardown and immediately before replacing this APK; it does not close the process-wide
     * writer, so Android may recreate the service in the same process.
     */
    fun flush(context: Context, timeoutMs: Long): Boolean =
        processes[context.applicationContext.packageName]?.let {
            it.flush(timeoutMs)
        } ?: true

    fun quiesceForSelfReplace(context: Context, timeoutMs: Long): StateQuiescence? =
        process(context.applicationContext).quiesce(timeoutMs)

    private fun process(appContext: Context): ProcessState =
        processes.getOrCreate(appContext.packageName) {
            ProcessState(
                helper = EntityCatalogStore(appContext),
                executor = Executors.newSingleThreadExecutor(StateWriterThreadFactory()),
                admission = StateMutationAdmission(),
            )
        }

    private data class ProcessState(
        val helper: EntityCatalogStore,
        val executor: ExecutorService,
        val admission: StateMutationAdmission,
        val stores: AtomicFactoryCache<String, SqliteStatePreferences> = AtomicFactoryCache(),
    ) {
        fun quiesce(timeoutMs: Long): StateQuiescence? =
            quiesceStateWrites(admission) { flush(timeoutMs) }

        fun flush(timeoutMs: Long): Boolean {
            if (timeoutMs < 0) return false
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            var succeeded = true
            for (store in stores.snapshotValues()) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos < 0) {
                    succeeded = false
                    continue
                }
                if (!store.flush(TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L))) {
                    succeeded = false
                }
            }
            return succeeded
        }
    }

    private class StateWriterThreadFactory : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "ha-paneld-state-writer").apply { isDaemon = true }
    }

    private val processes = AtomicFactoryCache<String, ProcessState>()
    private const val BRIDGE_PREFS = "ha-paneld-state-bridge"
}

internal class AtomicFactoryCache<K : Any, V : Any> {
    private val values = ConcurrentHashMap<K, V>()

    operator fun get(key: K): V? = values[key]

    fun snapshotValues(): List<V> = values.values.toList()

    fun getOrCreate(key: K, factory: () -> V): V =
        values.computeIfAbsent(key) { factory() }
}

internal class SqliteStatePreferences(
    private val persistence: StateNamespacePersistence,
    executor: ExecutorService,
    private val admission: StateMutationAdmission = StateMutationAdmission(),
) : SharedPreferences {
    constructor(
        helper: EntityCatalogStore,
        namespace: String,
        legacyName: String,
        legacy: SharedPreferences,
        bridge: SharedPreferences,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        DowngradeCompatibleStatePersistence(
            SqliteNamespacePersistence(helper, namespace, legacyName, legacy, clock),
            SharedPreferencesLegacyStateMirror(legacy),
            SharedPreferencesBridgeMetadata(
                bridge,
                namespace,
            ),
        ),
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ha-paneld-state-test-writer").apply { isDaemon = true }
        },
        StateMutationAdmission(),
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

    internal fun flush(timeoutMs: Long): Boolean {
        val future = synchronized(stateLock) {
            writeQueue.flush(values.toMap())
        } ?: return false
        return future.awaitResult(timeoutMs)
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
            publish(waitForDisk = false)
        }

        override fun commit(): Boolean = publish(waitForDisk = true)

        private fun publish(waitForDisk: Boolean): Boolean {
            if (!clear && changes.isEmpty()) return true
            return admission.admit { synchronousDurability ->
                if (synchronousDurability) {
                    admission.serializeFrozenWrite {
                        publishAdmitted(waitForDisk = true, synchronousDurability = true)
                    }
                } else {
                    publishAdmitted(waitForDisk, synchronousDurability = false)
                }
            }
        }

        private fun publishAdmitted(
            waitForDisk: Boolean,
            synchronousDurability: Boolean,
        ): Boolean {
            val pending = synchronized(stateLock) {
                val before = values.toMap()
                val candidate = before.toMutableMap()
                if (clear) candidate.clear()
                changes.forEach { (key, value) ->
                    if (value == null) candidate.remove(key) else candidate[key] = value
                }
                val changed = (before.keys + candidate.keys)
                    .filterTo(linkedSetOf()) { before[it] != candidate[it] }
                val future = writeQueue.enqueue(
                    StateMutation(clear, changes.toMap()),
                    candidate.toMap(),
                )
                if (!synchronousDurability) {
                    values.clear()
                    values.putAll(candidate)
                }
                PendingStateWrite(
                    changedKeys = changed,
                    future = future,
                    candidate = candidate,
                    synchronousDurability = synchronousDurability,
                )
            }
            if (pending.synchronousDurability) {
                val durable = pending.future?.awaitResult() ?: false
                if (!durable) return false
                synchronized(stateLock) {
                    values.clear()
                    values.putAll(pending.candidate)
                }
            }
            pending.changedKeys.forEach { key ->
                listeners.forEach { it.onSharedPreferenceChanged(this@SqliteStatePreferences, key) }
            }
            return if (waitForDisk && !pending.synchronousDurability) {
                pending.future?.awaitResult() ?: false
            } else true
        }

        private fun stage(key: String, value: Any?): SharedPreferences.Editor = apply {
            changes[key] = value
        }
    }

    private fun typeError(key: String, expected: String, value: Any): Nothing =
        throw ClassCastException("$key is ${value.javaClass.simpleName}, expected $expected")
}

class StateQuiescence internal constructor(
    private val reopen: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() {
        if (closed.compareAndSet(false, true)) reopen()
    }
}

internal class StateMutationAdmission {
    private val lock = ReentrantLock()
    private val unfrozen = lock.newCondition()
    private val frozenWriteLock = Any()
    private var frozen = false

    fun <T> admit(block: (synchronousDurability: Boolean) -> T): T = lock.withLock {
        while (frozen) unfrozen.awaitUninterruptibly()
        block(false)
    }

    fun <T> serializeFrozenWrite(block: () -> T): T = synchronized(frozenWriteLock) { block() }

    fun freeze(): Boolean = lock.withLock {
        if (frozen) false else {
            frozen = true
            true
        }
    }

    fun unfreeze() = lock.withLock {
        frozen = false
        unfrozen.signalAll()
    }
}

internal fun quiesceStateWrites(
    admission: StateMutationAdmission,
    flush: () -> Boolean,
): StateQuiescence? {
    if (!admission.freeze()) return null
    val flushed = runCatching {
        admission.serializeFrozenWrite(flush)
    }.getOrDefault(false)
    if (!flushed) {
        admission.unfreeze()
        return null
    }
    return StateQuiescence(admission::unfreeze)
}

internal data class StateMutation(
    val clear: Boolean,
    val changes: Map<String, Any?>,
)

private data class PendingStateWrite(
    val changedKeys: Set<String>,
    val future: Future<Boolean>?,
    val candidate: Map<String, Any>,
    val synchronousDurability: Boolean,
)

internal interface StateNamespacePersistence {
    fun initialize(): Map<String, Any>
    fun persist(mutation: StateMutation): Boolean
    fun replace(snapshot: Map<String, Any>): Boolean
}

/**
 * Keeps the pre-SQLite XML namespace current for the supported 0.9.x downgrade window.
 *
 * A separate hash marker distinguishes supported downgrade edits after the bridge is established.
 * Markerless divergence is possible only after unpublished SQLite-only builds: neither side has
 * reliable ordering evidence, so SQLite stays active, XML is left untouched, and metadata records
 * the conflict for diagnostics rather than destroying either state.
 */
internal class DowngradeCompatibleStatePersistence(
    private val primary: StateNamespacePersistence,
    private val legacy: LegacyStateMirror,
    private val metadata: BridgeMetadata,
) : StateNamespacePersistence {
    private var snapshot: Map<String, Any> = emptyMap()
    private var markerlessConflict = false

    override fun initialize(): Map<String, Any> {
        val current = primary.initialize()
        val journal = legacy.snapshot()
        val journalHash = stateSnapshotHash(journal)
        val marker = metadata.readHash()
        snapshot = when {
            marker == null -> {
                if (journal == current) {
                    check(legacy.replace(current)) { "could not seed legacy compatibility journal" }
                    check(metadata.writeHash(stateSnapshotHash(current))) { "could not mark legacy compatibility journal" }
                } else {
                    check(metadata.writeConflict(stateSnapshotHash(current), journalHash)) {
                        "could not record markerless compatibility conflict"
                    }
                    Log.w(TAG, "markerless SQLite/XML state conflict preserved; SQLite remains active")
                    markerlessConflict = true
                }
                current
            }
            marker == journalHash -> {
                if (journal != current) {
                    check(legacy.replace(current)) { "could not refresh legacy compatibility journal" }
                    check(metadata.writeHash(stateSnapshotHash(current))) { "could not refresh compatibility marker" }
                }
                current
            }
            else -> {
                check(primary.replace(journal)) { "could not import legacy compatibility journal" }
                check(metadata.writeHash(journalHash)) { "could not mark imported compatibility journal" }
                journal
            }
        }
        return snapshot
    }

    override fun persist(mutation: StateMutation): Boolean {
        val candidate = snapshot.toMutableMap().applyMutation(mutation)
        val succeeded = if (markerlessConflict) {
            legacy.replace(candidate) &&
                primary.replace(candidate) &&
                metadata.writeHash(stateSnapshotHash(candidate))
        } else {
            legacy.persist(mutation) &&
                primary.persist(mutation) &&
                metadata.writeHash(stateSnapshotHash(candidate))
        }
        if (succeeded) {
            snapshot = candidate
            markerlessConflict = false
        }
        return succeeded
    }

    override fun replace(snapshot: Map<String, Any>): Boolean {
        val succeeded = legacy.replace(snapshot) &&
            primary.replace(snapshot) &&
            metadata.writeHash(stateSnapshotHash(snapshot))
        if (succeeded) {
            this.snapshot = snapshot.toMap()
            markerlessConflict = false
        }
        return succeeded
    }

    private companion object {
        const val TAG = "ha-paneld/state"
    }
}

internal interface LegacyStateMirror {
    fun snapshot(): Map<String, Any>
    fun persist(mutation: StateMutation): Boolean
    fun replace(snapshot: Map<String, Any>): Boolean
}

internal interface BridgeMetadata {
    fun readHash(): String?
    fun writeHash(hash: String): Boolean
    fun writeConflict(sqliteHash: String, legacyHash: String): Boolean
}

private class SharedPreferencesBridgeMetadata(
    private val preferences: SharedPreferences,
    private val key: String,
) : BridgeMetadata {
    override fun readHash(): String? = preferences.getString(key, null)
    override fun writeHash(hash: String): Boolean = preferences.edit()
        .putString(key, hash)
        .remove("$key.conflict")
        .commit()

    override fun writeConflict(sqliteHash: String, legacyHash: String): Boolean =
        preferences.edit()
            .putString("$key.conflict", "$sqliteHash:$legacyHash")
            .commit()
}

private fun MutableMap<String, Any>.applyMutation(mutation: StateMutation): Map<String, Any> = apply {
    if (mutation.clear) clear()
    mutation.changes.forEach { (key, value) ->
        if (value == null) remove(key) else put(key, value)
    }
}.toMap()

internal fun stateSnapshotHash(snapshot: Map<String, Any>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(':'.code.toByte())
        digest.update(bytes)
    }
    snapshot.toSortedMap().forEach { (key, value) ->
        add(key)
        when (value) {
            is String -> { add("string"); add(value) }
            is Int -> { add("int"); add(value.toString()) }
            is Long -> { add("long"); add(value.toString()) }
            is Float -> { add("float"); add(value.toRawBits().toString()) }
            is Boolean -> { add("boolean"); add(if (value) "1" else "0") }
            is Set<*> -> {
                require(value.all { it is String }) { "only string sets are supported" }
                add("string_set")
                value.map { it as String }.sorted().forEach(::add)
            }
            else -> error("unsupported state type ${value.javaClass.name}")
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private class SharedPreferencesLegacyStateMirror(
    private val preferences: SharedPreferences,
) : LegacyStateMirror {
    override fun snapshot(): Map<String, Any> = buildMap {
        preferences.all.forEach { (key, value) ->
            if (value != null) {
                @Suppress("UNCHECKED_CAST")
                put(key, if (value is Set<*>) (value as Set<String>).toSet() else value)
            }
        }
    }

    override fun persist(mutation: StateMutation): Boolean =
        preferences.edit().also { editor ->
            if (mutation.clear) editor.clear()
            mutation.changes.forEach { (key, value) -> editor.putStateValue(key, value) }
        }.commit()

    override fun replace(snapshot: Map<String, Any>): Boolean =
        preferences.edit().clear().also { editor ->
            snapshot.forEach { (key, value) -> editor.putStateValue(key, value) }
        }.commit()

    private fun SharedPreferences.Editor.putStateValue(
        key: String,
        value: Any?,
    ): SharedPreferences.Editor = when (value) {
        null -> remove(key)
        is String -> putString(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is Boolean -> putBoolean(key, value)
        is Set<*> -> {
            require(value.all { it is String }) { "only string sets are supported" }
            @Suppress("UNCHECKED_CAST")
            putStringSet(key, value as Set<String>)
        }
        else -> error("unsupported state type ${value.javaClass.name}")
    }
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

    /**
     * Queue a complete snapshot behind all admitted mutations. Replacing even after successful writes
     * makes shutdown a durability operation rather than only an executor-idle observation.
     */
    fun flush(snapshot: Map<String, Any>): Future<Boolean>? = runCatching {
        executor.submit<Boolean> {
            val succeeded = runCatching { persistence.replace(snapshot) }.getOrDefault(false)
            reconcileRequired = !succeeded
            succeeded
        }
    }.getOrNull()
}

private fun Future<Boolean>.awaitResult(): Boolean =
    runCatching { get() }.getOrDefault(false)

private fun Future<Boolean>.awaitResult(timeoutMs: Long): Boolean =
    runCatching { get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)

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
