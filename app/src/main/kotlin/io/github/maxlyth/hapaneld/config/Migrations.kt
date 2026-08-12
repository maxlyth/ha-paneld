package io.github.maxlyth.hapaneld.config

/**
 * Ordered schema-migration chain for config bundles. Each [Migration] upgrades a values map from
 * schema N to N+1 (rename a key, retype a value, supply a default for a newly-added setting). Pure.
 *
 * Policy (a fleet updates asynchronously, so be lenient):
 *  - older bundle (schema < current): run the chain to upgrade it to the current shape.
 *  - newer bundle (schema > current): tolerate-and-warn; apply what is recognised, never hard-fail.
 */
object Migrations {

    fun interface Migration {
        /** Transform the values map in place from schema N to N+1. */
        fun apply(values: MutableMap<String, String>)
    }

    /**
     * CHAIN[i] migrates schema (i+1) → (i+2). When the persisted shape next changes, bump
     * [SettingsRegistry.SCHEMA] and append the transform here.
     */
    val CHAIN: List<Migration> = listOf(
        Migration { values -> values.putIfAbsent("auto_brightness_minimum_percent", "4") },
        Migration { values ->
            values.putIfAbsent("auto_sleep", "false")
        },
        Migration { values ->
            // Schema-3 bundles encoded the old implicit defaults. Preserve those when absent; new
            // schema-4 stores use the safer registry defaults directly.
            values.putIfAbsent("wake_on_wave", "true")
            SettingsRegistry.LEGACY_DEFAULT_ON_HA_EXPOSURES.forEach { key ->
                values.putIfAbsent("${SettingsRegistry.HA_EXPOSE_PREFIX}$key", "true")
            }
        },
        Migration { values ->
            values.putIfAbsent("mqtt_address_family", SettingsRegistry.DEFAULT_MQTT_ADDRESS_FAMILY)
        },
    )

    /**
     * Upgrade [values] from [fromSchema] to [SettingsRegistry.SCHEMA]. Returns the migrated map plus
     * any warnings (e.g. a newer-than-current bundle). Never throws on version mismatch.
     */
    fun migrate(fromSchema: Int, values: Map<String, String>): Pair<Map<String, String>, List<String>> {
        val current = SettingsRegistry.SCHEMA
        val warnings = mutableListOf<String>()
        val m = LinkedHashMap(values)
        when {
            fromSchema == current -> Unit
            fromSchema < current -> {
                // Apply CHAIN[fromSchema-1 .. current-2] in order.
                for (i in (fromSchema - 1) until (current - 1)) {
                    CHAIN.getOrNull(i)?.apply(m)
                        ?: warnings.add("no migration registered for schema ${i + 1}→${i + 2}; values left as-is")
                }
            }
            else -> warnings.add(
                "bundle schema $fromSchema is newer than this panel (schema $current); " +
                    "unrecognised-shape values may be ignored",
            )
        }
        return m to warnings
    }
}
