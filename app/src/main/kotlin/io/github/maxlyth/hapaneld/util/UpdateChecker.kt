package io.github.maxlyth.hapaneld.util

import android.content.Context
import io.github.maxlyth.hapaneld.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub releases/latest for available updates to ha-paneld and the installed HA Companion
 * app. Results are cached; [checkIfStale] is called on a schedule so the web UI always reflects the
 * most recently known state without hitting the API on every page load.
 *
 * `releases/latest` only returns non-prerelease, non-draft releases — so an rc build will correctly
 * show the next stable release as an available update once one is published.
 */
object UpdateChecker {

    data class UpdateInfo(
        val label: String,
        val currentVersion: String,
        val latestVersion: String,
        val releaseUrl: String,
    )

    @Volatile var available: List<UpdateInfo> = emptyList()
        private set
    @Volatile private var lastCheckMs = 0L

    /** Check if [staleMs] have elapsed since the last check; if so, run a new one. */
    suspend fun checkIfStale(context: Context, channel: String = "stable", staleMs: Long = 3_600_000L) {
        if (System.currentTimeMillis() - lastCheckMs > staleMs) check(context, channel)
    }

    /** Unconditional check against the GitHub releases API; updates [available] in place. The ha-paneld
     *  entry follows [channel] ("stable" | "prerelease") so the banner matches the self-update target. */
    suspend fun check(context: Context, channel: String = "stable") = withContext(Dispatchers.IO) {
        lastCheckMs = System.currentTimeMillis()
        val found = mutableListOf<UpdateInfo>()

        val paneldLatest = if (channel == "prerelease") SelfUpdater.resolve("prerelease")?.first
                           else fetchLatest("maxlyth/ha-paneld")?.first?.removePrefix("v")
        paneldLatest?.let { latest ->
            val current = BuildConfig.VERSION_NAME
            if (isNewer(latest, current))
                found += UpdateInfo("ha-paneld", current, latest, "https://github.com/maxlyth/ha-paneld/releases")
        }

        // HA Companion (either full or minimal variant, if installed on this no-Play-Store panel)
        val companionPkg = COMPANION_PKGS
            .firstOrNull { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess }
        if (companionPkg != null) {
            val installed = runCatching {
                context.packageManager.getPackageInfo(companionPkg, 0).versionName ?: ""
            }.getOrElse { "" }
            fetchLatest("home-assistant/android")?.let { (tag, url) ->
                // HA Android tags: "3.3.2-full", "2024.11.1-minimal", etc. — strip variant suffix
                val latest = tag.removePrefix("v").let { Regex("-(?:full|minimal|wear)$").replace(it, "") }
                if (installed.isNotBlank() && isNewer(latest, stripVariant(installed))) {
                    found += UpdateInfo("HA Companion", installed, latest, url)
                }
            }
        }

        available = found
    }

    /** Available updates minus any the user has dismissed at their *current* latest version. [ignored]
     *  maps a component label ("HA Companion") to the exact version string that was ignored, so an ignore
     *  only silences that one release — when a newer version is later published [latestVersion] no longer
     *  matches and the entry re-surfaces ("ticks again"). Used to filter the dashboard banner only; the
     *  Install tab always lists [available] in full. Pure — unit-tested in UpdateVisibilityTest. */
    fun visible(ignored: Map<String, String>): List<UpdateInfo> = filterIgnored(available, ignored)

    /**
     * [available] revalidated against the CURRENT install state. The hourly cache can go stale within
     * its window: a "HA Companion" entry recorded while a Companion was installed would otherwise
     * outlive its uninstall and render an update for an absent app (seen in a GitHub issue #24 /diag
     * dump: `[packages] … not installed` alongside `[updates] HA Companion: …`). Cheap PackageManager
     * probe at render time; ha-paneld's own entry never needs revalidating (we are always installed).
     */
    fun current(context: Context, ignored: Map<String, String> = emptyMap()): List<UpdateInfo> =
        filterAbsent(
            filterIgnored(available, ignored),
            companionInstalled = COMPANION_PKGS.any {
                runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
            },
        )

    /** Pure core of [current] — unit-tested in UpdateVisibilityTest. */
    internal fun filterAbsent(list: List<UpdateInfo>, companionInstalled: Boolean): List<UpdateInfo> =
        if (companionInstalled) list else list.filterNot { it.label == "HA Companion" }

    internal val COMPANION_PKGS = listOf(
        "io.homeassistant.companion.android",
        "io.homeassistant.companion.android.minimal",
    )

    /** Pure core of [visible] — unit-tested in UpdateVisibilityTest. */
    internal fun filterIgnored(list: List<UpdateInfo>, ignored: Map<String, String>): List<UpdateInfo> =
        list.filterNot { ignored[it.label] == it.latestVersion }

    /** HA Companion versionNames/tags carry a VARIANT suffix ("-minimal"/"-full"/"-wear") that is not a
     *  prerelease marker — "2026.6.5-minimal" IS version 2026.6.5. Strip it before any comparison, else
     *  [isNewer]'s suffix ordering treats an installed minimal build as older than its own version and
     *  offers a same-version "upgrade" (GitHub issue #17). */
    internal fun stripVariant(v: String): String = Regex("-(?:full|minimal|wear)$").replace(v.trim(), "")

    internal fun fetchLatest(repo: String): Pair<String, String>? = runCatching {
        val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        if (conn.responseCode != 200) return@runCatching null
        val json = conn.inputStream.bufferedReader().readText()
        val tag = Regex(""""tag_name"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return@runCatching null
        val url = Regex(""""html_url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: ""
        tag to url
    }.getOrNull()

    /**
     * True when [candidate] is strictly newer than [current] by numeric major.minor.patch.
     * Suffixes (e.g. -rc3, -beta) are stripped before comparison, so stable "0.8.4" is newer
     * than prerelease "0.8.4-rc3" — matching GitHub's own latest-release logic.
     */
    internal fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }
        val c = parts(candidate)
        val cur = parts(current)
        for (i in 0 until maxOf(c.size, cur.size)) {
            val a = c.getOrElse(i) { 0 }
            val b = cur.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        // Equal numeric base — order by the prerelease suffix so the pre-release channel advances (e.g.
        // rc4 > rc3): a stable (no suffix) beats any prerelease; between prereleases the trailing number
        // wins; identical suffixes are not newer.
        val cs = candidate.substringAfter('-', "")
        val curs = current.substringAfter('-', "")
        if (cs == curs) return false
        if (cs.isEmpty()) return true      // candidate stable, current prerelease
        if (curs.isEmpty()) return false   // candidate prerelease, current stable
        val cn = Regex("""\d+""").findAll(cs).lastOrNull()?.value?.toIntOrNull() ?: 0
        val curn = Regex("""\d+""").findAll(curs).lastOrNull()?.value?.toIntOrNull() ?: 0
        return cn > curn
    }
}
