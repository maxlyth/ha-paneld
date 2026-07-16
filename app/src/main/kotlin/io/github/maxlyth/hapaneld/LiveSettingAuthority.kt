package io.github.maxlyth.hapaneld

import android.content.Context
import io.github.maxlyth.hapaneld.persistence.AppState
import org.json.JSONObject

internal enum class LiveSettingApplyResult { APPLIED, DEFERRED, FAILED }

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
    internal data class Pending(val value: String, val previousValue: String?)

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

    @Synchronized
    fun applyOrQueue(
        key: String,
        value: String,
        apply: (String, String) -> LiveSettingApplyResult,
    ): Boolean = applyOrQueue(key, value, null) { appliedKey, appliedValue, _ ->
        apply(appliedKey, appliedValue)
    }

    @Synchronized
    fun applyOrQueue(
        key: String,
        value: String,
        previousValue: String?,
        apply: (String, String, String?) -> LiveSettingApplyResult,
    ): Boolean {
        if (key !in supportedKeys) return false
        // A latest-wins update must retain the provenance from before the first unapplied desired value.
        val queued = Pending(value, pending[key]?.previousValue ?: previousValue)
        if (!journal.put(key, queued)) return false
        pending[key] = queued
        return when (runCatching { apply(key, value, queued.previousValue) }.getOrDefault(LiveSettingApplyResult.FAILED)) {
            LiveSettingApplyResult.APPLIED -> {
                if (journal.remove(key)) pending.remove(key)
                true
            }
            LiveSettingApplyResult.DEFERRED -> true
            LiveSettingApplyResult.FAILED -> false
        }
    }

    @Synchronized
    fun replay(apply: (String, String) -> LiveSettingApplyResult) {
        replay { key, value, _ -> apply(key, value) }
    }

    @Synchronized
    fun replay(apply: (String, String, String?) -> LiveSettingApplyResult) {
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val (key, queued) = iterator.next()
            if (runCatching { apply(key, queued.value, queued.previousValue) }.getOrDefault(LiveSettingApplyResult.FAILED) ==
                LiveSettingApplyResult.APPLIED && journal.remove(key)
            ) {
                iterator.remove()
            }
        }
    }

    @Synchronized
    internal fun pendingSnapshot(): Map<String, String> = pending.mapValues { it.value.value }
    internal fun pendingPreviousSnapshot(): Map<String, String?> = pending.mapValues { it.value.previousValue }

    companion object {
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
                            )
                        } else Pending(encoded, null) // legacy desired-only journal
                        key to pending
                    }
                }.toMap()

                override fun put(key: String, value: Pending): Boolean =
                    preferences.edit().putString(
                        key,
                        JSONObject().put("value", value.value).apply {
                            value.previousValue?.let { put("previous", it) }
                        }.toString(),
                    ).commit()

                override fun remove(key: String): Boolean = preferences.edit().remove(key).commit()
            }
            return LiveSettingAuthority(supportedKeys, store)
        }
    }
}
