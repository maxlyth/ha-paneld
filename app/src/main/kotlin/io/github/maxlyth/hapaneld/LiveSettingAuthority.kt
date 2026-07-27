package io.github.maxlyth.hapaneld

import android.content.Context
import io.github.maxlyth.hapaneld.persistence.AppState
import java.util.UUID
import org.json.JSONObject

internal enum class LiveSettingApplyResult { APPLIED, DEFERRED, FAILED }

/** Immediate request disposition plus an optional observable terminal result for admitted work that
 * outlived the bounded response wait. */
internal class LiveSettingApplication(
    val initial: LiveSettingApplyResult,
    private val lateCompletion: (((LiveSettingApplyResult) -> Unit) -> Unit)? = null,
) {
    val hasLateCompletion: Boolean get() = lateCompletion != null
    fun observeLateCompletion(observer: (LiveSettingApplyResult) -> Unit) {
        lateCompletion?.invoke(observer)
    }

    companion object {
        fun immediate(result: LiveSettingApplyResult) = LiveSettingApplication(result)
    }
}

/** Request-level disposition after the desired value has (or has not) entered durable ownership. */
internal enum class LiveSettingRequestOutcome {
    APPLIED,
    DEFERRED,
    FAILED_PENDING,
    REJECTED;

    val legacyAcknowledged: Boolean get() = this == APPLIED || this == DEFERRED
    val pending: Boolean get() = this == DEFERRED || this == FAILED_PENDING
    val durablyAccepted: Boolean get() = this != REJECTED
}

/** Keep the desired value immediately durable/readable while preserving the previous value for handlers
 * whose side effects depend on detecting a transition. */
internal fun durableLiveSettingApply(
    previousValue: String,
    transient: Boolean,
    actuationOwnsPersistence: Boolean = false,
    persist: () -> Boolean,
    apply: (String?) -> LiveSettingApplyResult,
): LiveSettingApplyResult {
    if (!transient && !actuationOwnsPersistence && !persist()) return LiveSettingApplyResult.FAILED
    return apply(previousValue.takeUnless { transient })
}

/**
 * Keeps HTTP-originated live settings authoritative while the replaceable MQTT runtime is draining.
 *
 * A bridge can reject an otherwise valid setting because reconfiguration has closed its command
 * admission. Journal the latest accepted value synchronously before trying the bridge, then remove it
 * only after the side effect succeeds. A process death or failed startup therefore replays acknowledged
 * work instead of losing it with the retiring bridge.
 */
internal class LiveSettingAuthority(
    private val supportedKeys: Set<String>,
    private val journal: Journal = MemoryJournal(),
) {
    internal data class Pending(
        val value: String,
        val previousValue: String?,
        val fence: Long? = null,
        val generation: String = UUID.randomUUID().toString(),
        /** Failed REPLAY attempts so far. A fresh user intent always starts at zero. */
        val attempts: Int = 0,
    )

    internal interface Journal {
        fun load(): Map<String, Pending>
        fun put(key: String, value: Pending): Boolean
        fun remove(key: String): Boolean
    }

    private class MemoryJournal : Journal {
        private val values = linkedMapOf<String, Pending>()
        override fun load(): Map<String, Pending> = values.toMap()
        override fun put(key: String, value: Pending): Boolean { values[key] = value; return true }
        override fun remove(key: String): Boolean { values.remove(key); return true }
    }

    private val pending = journal.load().filterKeys(supportedKeys::contains).toMutableMap()
    /** Pending generations already admitted to a dispatcher. Replays must not enqueue the same desired
     * value behind its original execution, because a newer external command may already follow it. */
    private val inFlight = mutableMapOf<String, Pending>()
    /** Serializes runtime application of one setting without blocking journal inspection or discard. */
    private val applyLocks = supportedKeys.associateWith { Any() }

    fun applyOrQueue(
        key: String,
        value: String,
        apply: (String, String) -> LiveSettingApplyResult,
    ): Boolean = applyOrQueueOutcome(key, value, null) { appliedKey, appliedValue, _ ->
        apply(appliedKey, appliedValue)
    }.legacyAcknowledged

    fun applyOrQueue(
        key: String,
        value: String,
        previousValue: String?,
        apply: (String, String, String?) -> LiveSettingApplyResult,
    ): Boolean = applyOrQueueOutcome(key, value, previousValue, apply).legacyAcknowledged

    fun applyOrQueueOutcome(
        key: String,
        value: String,
        previousValue: String?,
        apply: (String, String, String?) -> LiveSettingApplyResult,
    ): LiveSettingRequestOutcome = applyOrQueueOutcomeIf(
        key, value, previousValue, expected = { true },
        apply = { appliedKey, appliedValue, previous ->
            LiveSettingApplication.immediate(apply(appliedKey, appliedValue, previous))
        },
    )

    fun applyOrQueueOutcomeObserved(
        key: String,
        value: String,
        previousValue: String?,
        apply: (String, String, String?) -> LiveSettingApplication,
    ): LiveSettingRequestOutcome = applyOrQueueOutcomeIf(
        key, value, previousValue, expected = { true }, apply = apply,
    )

    /** Atomically rejects a stale safety intent before it can supersede a newer queued value. */
    fun applyOrQueueIf(
        key: String,
        value: String,
        previousValue: String?,
        fence: Long? = null,
        expected: () -> Boolean,
        apply: (String, String, String?) -> LiveSettingApplyResult,
    ): Boolean = applyOrQueueOutcomeIf(
        key, value, previousValue, fence, expected,
    ) { appliedKey, appliedValue, previous ->
        LiveSettingApplication.immediate(apply(appliedKey, appliedValue, previous))
    }.legacyAcknowledged

    private fun applyOrQueueOutcomeIf(
        key: String,
        value: String,
        previousValue: String?,
        fence: Long? = null,
        expected: () -> Boolean,
        apply: (String, String, String?) -> LiveSettingApplication,
    ): LiveSettingRequestOutcome {
        val applyLock = applyLocks[key] ?: return LiveSettingRequestOutcome.REJECTED
        return synchronized(applyLock) {
            val queued = synchronized(this) {
                if (!expected()) return LiveSettingRequestOutcome.REJECTED
                // A latest-wins update retains provenance from before the first unapplied desired value.
                Pending(value, pending[key]?.previousValue ?: previousValue, fence).also {
                    if (!journal.put(key, it)) return LiveSettingRequestOutcome.REJECTED
                    pending[key] = it
                }
            }
            val application = runCatching { apply(key, value, queued.previousValue) }
                .getOrDefault(LiveSettingApplication.immediate(LiveSettingApplyResult.FAILED))
            if (application.hasLateCompletion) {
                synchronized(this) {
                    if (pending[key] == queued) inFlight[key] = queued
                }
                application.observeLateCompletion { terminal ->
                    settleLateCompletion(key, queued, terminal)
                }
            }
            when (application.initial) {
                LiveSettingApplyResult.APPLIED -> {
                    val cleared = synchronized(this) {
                        // Discard may establish a newer external truth while application is blocked.
                        if (pending[key] != queued) true else if (journal.remove(key)) {
                            pending.remove(key)
                            true
                        } else false
                    }
                    if (cleared) LiveSettingRequestOutcome.APPLIED else LiveSettingRequestOutcome.FAILED_PENDING
                }
                LiveSettingApplyResult.DEFERRED -> LiveSettingRequestOutcome.DEFERRED
                LiveSettingApplyResult.FAILED -> LiveSettingRequestOutcome.FAILED_PENDING
            }
        }
    }

    private fun settleLateCompletion(key: String, queued: Pending, terminal: LiveSettingApplyResult) {
        val applyLock = applyLocks[key] ?: return
        synchronized(applyLock) {
            synchronized(this) {
                if (inFlight[key] == queued) inFlight.remove(key)
                if (terminal == LiveSettingApplyResult.APPLIED && pending[key] == queued && journal.remove(key)) {
                    pending.remove(key)
                }
            }
        }
    }

    /** Supersede any queued HTTP intent without replaying it. Safety-sensitive settings use this when
     * another authority (for example MQTT or the boot escape window) establishes a newer truth. */
    @Synchronized
    fun discard(key: String): Boolean {
        if (key !in supportedKeys) return false
        if (pending.remove(key) == null) return true
        return journal.remove(key)
    }

    fun replay(apply: (String, String) -> LiveSettingApplyResult) {
        replay { key, value, _ -> apply(key, value) }
    }

    fun replay(apply: (String, String, String?) -> LiveSettingApplyResult) {
        replay { key, value, previous, _ -> apply(key, value, previous) }
    }

    fun replay(apply: (String, String, String?, Long?) -> LiveSettingApplyResult) {
        replayKeys(supportedKeys, apply)
    }

    fun replayKeys(keys: Set<String>, apply: (String, String, String?, Long?) -> LiveSettingApplyResult) {
        replayKeysObserved(keys) { key, value, previous, fence ->
            LiveSettingApplication.immediate(apply(key, value, previous, fence))
        }
    }

    fun replayKeysObserved(keys: Set<String>, apply: (String, String, String?, Long?) -> LiveSettingApplication) {
        val snapshot = synchronized(this) { pending.filterKeys(keys::contains).toList() }
        snapshot.forEach { (key, queued) ->
            synchronized(checkNotNull(applyLocks[key])) {
                // A newer apply may have superseded this snapshot while replay waited for the key.
                if (synchronized(this) { pending[key] == queued && inFlight[key] != queued }) {
                    val application = runCatching {
                        apply(key, queued.value, queued.previousValue, queued.fence)
                    }.getOrDefault(LiveSettingApplication.immediate(LiveSettingApplyResult.FAILED))
                    if (application.hasLateCompletion) {
                        synchronized(this) {
                            if (pending[key] == queued) inFlight[key] = queued
                        }
                        application.observeLateCompletion { terminal ->
                            settleLateCompletion(key, queued, terminal)
                        }
                    }
                    if (application.initial == LiveSettingApplyResult.APPLIED) synchronized(this) {
                        if (pending[key] == queued && journal.remove(key)) {
                            pending.remove(key)
                        }
                    } else if (application.initial == LiveSettingApplyResult.FAILED &&
                        !application.hasLateCompletion
                    ) synchronized(this) {
                        // Replay durability is for TRANSIENT failure (a draining bridge, a root hiccup) —
                        // but a value this hardware can never apply replayed and failed on every boot
                        // forever, keeping a permanent "waiting to apply" warning over an untouched
                        // setting (fleet report: silence_boot_chime on a panel whose root path is gone).
                        // Three strikes across replays and the journal gives the value up: the SAVED
                        // setting stands, only the endless retry and its warning end. A new user intent
                        // starts a fresh count.
                        if (pending[key] == queued) {
                            val struck = queued.copy(attempts = queued.attempts + 1)
                            if (struck.attempts >= MAX_REPLAY_ATTEMPTS) {
                                if (journal.remove(key)) pending.remove(key)
                                android.util.Log.w(
                                    "ha-paneld/livesetting",
                                    "giving up replaying $key after ${struck.attempts} failed attempts; " +
                                        "the saved value stands but this hardware could not apply it",
                                )
                            } else if (journal.put(key, struck)) {
                                pending[key] = struck
                            }
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    internal fun pendingSnapshot(): Map<String, String> = pending.mapValues { it.value.value }
    @Synchronized
    internal fun pendingGenerationSnapshot(): Map<String, String> = pending.mapValues { it.value.generation }
    internal fun pendingPreviousSnapshot(): Map<String, String?> = pending.mapValues { it.value.previousValue }

    companion object {
        /** Failed replays tolerated before the journal stops retrying a value (one replay per boot). */
        internal const val MAX_REPLAY_ATTEMPTS = 3
        private const val JOURNAL = "ha-paneld-live-setting-journal"

        fun persistent(context: Context, supportedKeys: Set<String>): LiveSettingAuthority {
            val preferences = AppState.preferences(context, "live-setting-journal", JOURNAL)
            val store = object : Journal {
                override fun load(): Map<String, Pending> = preferences.all.mapNotNull { (key, raw) ->
                    (raw as? String)?.let { encoded ->
                        val decoded = runCatching { JSONObject(encoded) }.getOrNull()
                        val pending = if (decoded?.has("value") == true) {
                            Pending(
                                decoded.getString("value"),
                                decoded.optString("previous").takeIf { decoded.has("previous") },
                                decoded.optLong("fence").takeIf { decoded.has("fence") },
                                decoded.optString("generation").takeIf(String::isNotBlank)
                                    ?: UUID.randomUUID().toString(),
                                decoded.optInt("attempts"),
                            )
                        } else Pending(encoded, null) // legacy desired-only journal
                        key to pending
                    }
                }.toMap()

                override fun put(key: String, value: Pending): Boolean =
                    preferences.edit().putString(
                        key,
                        JSONObject().put("value", value.value).put("attempts", value.attempts).apply {
                            value.previousValue?.let { put("previous", it) }
                            value.fence?.let { put("fence", it) }
                            put("generation", value.generation)
                        }.toString(),
                    ).commit()

                override fun remove(key: String): Boolean = preferences.edit().remove(key).commit()
            }
            return LiveSettingAuthority(supportedKeys, store)
        }
    }
}
