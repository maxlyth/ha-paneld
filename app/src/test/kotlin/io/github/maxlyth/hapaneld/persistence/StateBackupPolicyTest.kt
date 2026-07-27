package io.github.maxlyth.hapaneld.persistence

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateBackupPolicyTest {
    private fun row(namespace: String, key: String = "k") =
        ConfigVault.StateRow(namespace, key, "string", "v", 1L)

    /**
     * The point of the policy. The old backup derived config from a whitelist of declared settings, so a
     * namespace nobody declared was silently absent; this test makes the same omission a build failure by
     * requiring every namespace the app actually persists to have a stated disposition.
     */
    @Test fun everyNamespaceTheAppPersistsIsClassified() {
        val sources = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first(File::isDirectory)
        // Namespaces are opened through AppState.preferences in two shapes — positional and with the
        // argument named — and missing either shape would make this test quietly weaker than it looks.
        val positional = Regex("""AppState\.preferences\(\s*[^,()]+,\s*"([a-z0-9-]+)"""", RegexOption.DOT_MATCHES_ALL)
        val named = Regex("""namespace\s*=\s*"([a-z0-9-]+)"""")
        val namespaces = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                (positional.findAll(text) + named.findAll(text)).map { it.groupValues[1] }
            }
            .toSortedSet()

        // Guards the scan itself: on the tree that shipped this test there were ten.
        assertTrue("the scan found only $namespaces — the patterns have drifted", namespaces.size >= 10)
        assertTrue("the positional shape must still be found", "config" in namespaces)
        assertTrue("the named-argument shape must still be found", "auto-brightness-runtime" in namespaces)
        val unclassified = namespaces.filter { StateBackupPolicy.disposition(it) == null }
        assertEquals(
            "every app_state namespace needs a StateBackupPolicy disposition — add one, do not let a " +
                "new namespace default to being withheld from restore silently",
            emptyList<String>(),
            unclassified,
        )
    }

    @Test fun manifestOwnedNamespacesAreNeverWrittenRawByAStateRestore() {
        val rows = listOf(row("config"), row("device-profiles"))
        // Even on the origin panel: the settings registry and profile catalog own these keys, and a raw
        // second writer would skip their validation.
        assertEquals(emptyList<ConfigVault.StateRow>(), StateBackupPolicy.restorableRows(rows, samePanel = true))
        assertEquals(emptyList<ConfigVault.StateRow>(), StateBackupPolicy.restorableRows(rows, samePanel = false))
    }

    @Test fun deviceLocalNamespacesReturnOnlyToTheirOwnPanel() {
        val rows = listOf(
            row("controller-state"),
            row("auto-sleep-learning"),
            row("profile-calibration"),
            row("performance-binding"),
            row("shizuku-consent"),
        )
        assertEquals(rows, StateBackupPolicy.restorableRows(rows, samePanel = true))
        assertEquals(
            "another panel's hardware state must not be written here",
            emptyList<ConfigVault.StateRow>(),
            StateBackupPolicy.restorableRows(rows, samePanel = false),
        )
    }

    @Test fun transientNamespacesAreNeverRestored() {
        val rows = listOf(
            row("auto-brightness-runtime"),
            row("live-setting-journal"),
            // Restoring crash-loop counters would make a healthy panel inherit a stale backoff.
            row("startup-recovery"),
        )
        assertEquals(emptyList<ConfigVault.StateRow>(), StateBackupPolicy.restorableRows(rows, samePanel = true))
    }

    @Test fun anUnknownNamespaceIsWithheldRatherThanTrusted() {
        val rows = listOf(row("namespace-from-a-future-release"))
        assertEquals(emptyList<ConfigVault.StateRow>(), StateBackupPolicy.restorableRows(rows, samePanel = true))
    }

    /** The archive is the complete record even where restoring a row would be wrong. */
    @Test fun theCodecCarriesEveryNamespaceRegardlessOfDisposition() {
        val rows = StateBackupPolicy.KNOWN_NAMESPACES.map { row(it) }
        val decoded = ConfigVault.decode(ConfigVault.encode(ConfigVault.Export(rows, emptyMap())))
        assertEquals(
            rows.map { it.namespace }.toSortedSet(),
            decoded?.rows?.map { it.namespace }?.toSortedSet(),
        )
    }
}
