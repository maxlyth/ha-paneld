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
    suspend fun checkIfStale(context: Context, staleMs: Long = 3_600_000L) {
        if (System.currentTimeMillis() - lastCheckMs > staleMs) check(context)
    }

    /** Unconditional check against the GitHub releases API; updates [available] in place. */
    suspend fun check(context: Context) = withContext(Dispatchers.IO) {
        lastCheckMs = System.currentTimeMillis()
        val found = mutableListOf<UpdateInfo>()

        fetchLatest("maxlyth/ha-paneld")?.let { (tag, url) ->
            val latest = tag.removePrefix("v")
            val current = BuildConfig.VERSION_NAME
            if (isNewer(latest, current)) found += UpdateInfo("ha-paneld", current, latest, url)
        }

        // HA Companion (either full or minimal variant, if installed on this no-Play-Store panel)
        val companionPkg = listOf(
            "io.homeassistant.companion.android",
            "io.homeassistant.companion.android.minimal",
        ).firstOrNull { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess }
        if (companionPkg != null) {
            val installed = runCatching {
                context.packageManager.getPackageInfo(companionPkg, 0).versionName ?: ""
            }.getOrElse { "" }
            fetchLatest("home-assistant/android")?.let { (tag, url) ->
                // HA Android tags: "3.3.2-full", "2024.11.1-minimal", etc. — strip variant suffix
                val latest = tag.removePrefix("v").let { Regex("-(?:full|minimal|wear)$").replace(it, "") }
                if (installed.isNotBlank() && isNewer(latest, installed)) {
                    found += UpdateInfo("HA Companion", installed, latest, url)
                }
            }
        }

        available = found
    }

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
        // Equal numeric version: stable (no suffix) trumps a prerelease (has suffix)
        return !candidate.contains('-') && current.contains('-')
    }
}
