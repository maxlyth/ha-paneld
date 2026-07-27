package io.github.maxlyth.hapaneld.persistence

/**
 * Which durable `app_state` rows a backup carries, and which of them a restore may write back.
 *
 * The backup archive used to derive its configuration from `SettingsRegistry.settable()` — a whitelist of
 * declared settings. That captures settings well and everything else not at all: any namespace the app
 * persists but never declares as a setting was silently absent from every backup, and adding one in future
 * would be silently absent too. Measured on a real panel, the `config` namespace was 59 of 76 `app_state`
 * rows; the other 17 were invisible.
 *
 * So the archive now carries a complete dump of the table, and this policy decides what a *restore* is
 * allowed to do with it. Completeness and restorability are deliberately separate questions: the record
 * should be whole even where writing it back would be wrong.
 *
 * Every namespace must be classified. An unclassified one is not restorable, and
 * `StateBackupPolicyTest` fails the build rather than letting a new namespace inherit that silently.
 */
object StateBackupPolicy {
    enum class Disposition {
        /**
         * Restored by the manifest's own validated path (the settings registry, the profile catalog).
         * Writing these rows raw as well would put two authorities on one key and skip validation.
         */
        MANIFEST_OWNED,

        /**
         * Describes the hardware or environment of the panel that wrote it — prior device audio state,
         * sensor calibration, a learned sleep model, a consent grant. Valuable across a reinstall of the
         * same panel, wrong on a different one, so it returns only to the panel of origin.
         */
        DEVICE_LOCAL,

        /** Rebuilt at runtime. Restoring a stale copy is worse than starting clean. */
        TRANSIENT,
    }

    private val DISPOSITIONS: Map<String, Disposition> = mapOf(
        // Already in the manifest, through paths that validate and reconfigure.
        "config" to Disposition.MANIFEST_OWNED,
        "device-profiles" to Disposition.MANIFEST_OWNED,

        // Same panel only.
        "controller-state" to Disposition.DEVICE_LOCAL,
        "auto-sleep-learning" to Disposition.DEVICE_LOCAL,
        "profile-calibration" to Disposition.DEVICE_LOCAL,
        "performance-binding" to Disposition.DEVICE_LOCAL,
        "shizuku-consent" to Disposition.DEVICE_LOCAL,

        // Runtime scratch: a manual brightness override in progress, the live-setting journal, and
        // crash-loop counters whose whole purpose is to describe *this* boot.
        "auto-brightness-runtime" to Disposition.TRANSIENT,
        "live-setting-journal" to Disposition.TRANSIENT,
        "startup-recovery" to Disposition.TRANSIENT,
    )

    val KNOWN_NAMESPACES: Set<String> get() = DISPOSITIONS.keys

    fun disposition(namespace: String): Disposition? = DISPOSITIONS[namespace]

    /**
     * The rows a restore may apply. [samePanel] must be true only when the archive's `panel_id` matches
     * the target's, which is what makes [Disposition.DEVICE_LOCAL] safe: the common real case is
     * reinstalling onto the panel that produced the backup.
     *
     * Unknown namespaces are withheld. Withholding a row loses a recoverable convenience; writing an
     * unreviewed one can strand hardware state, so the conservative direction is the correct default.
     */
    fun restorableRows(rows: List<ConfigVault.StateRow>, samePanel: Boolean): List<ConfigVault.StateRow> =
        rows.filter { row ->
            when (disposition(row.namespace)) {
                Disposition.DEVICE_LOCAL -> samePanel
                Disposition.MANIFEST_OWNED, Disposition.TRANSIENT, null -> false
            }
        }
}
