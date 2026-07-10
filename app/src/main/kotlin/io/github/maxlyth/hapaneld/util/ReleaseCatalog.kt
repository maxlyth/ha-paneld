package io.github.maxlyth.hapaneld.util

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Recent GitHub releases for a repo, for the Install-tab version picker. Fetches up to N releases,
 * filters by channel (stable = non-prerelease only; prerelease = all releases, newest first), and
 * exposes each as a pickable version with its release-notes URL + whether it carries an installable APK
 * asset. Also resolves the APK asset URL for an exact tag (install-a-specific-version). Network — call
 * OFF the main / MQTT thread.
 */
object ReleaseCatalog {
    private const val TAG = "ha-paneld/releases"

    /** A raw release as read from the API, before channel filtering. */
    data class Raw(val tag: String, val prerelease: Boolean, val notesUrl: String, val apkUrl: String?)

    /** A pickable version for the UI: [version] is the display label, [tag] the API/install key. */
    data class Version(
        val version: String,
        val tag: String,
        val notesUrl: String,
        val installable: Boolean,
        // Direct APK asset URL (null when the release ships none) — surfaced so a NO-ROOT panel's
        // Install tab can offer the download for a manual `adb install -r` from the admin machine.
        val apkUrl: String? = null,
    )

    /** A tag is only ever interpolated into a GitHub API path — restrict it to release-tag characters so
     *  a crafted value can't escape the path. */
    private val TAG_RE = Regex("^[A-Za-z0-9._-]{1,64}$")
    fun validTag(tag: String): Boolean = TAG_RE.matches(tag)

    /**
     * Pure selection: keep the first [limit] releases newest-first, dropping prereleases unless [channel]
     * is "prerelease"; map each tag to a display version via [normalize]. Unit-tested in ReleaseCatalogTest.
     */
    internal fun select(raw: List<Raw>, channel: String, limit: Int, normalize: (String) -> String): List<Version> =
        raw.asSequence()
            .filter { channel == "prerelease" || !it.prerelease }
            .take(limit)
            .map { Version(normalize(it.tag), it.tag, it.notesUrl, it.apkUrl != null, it.apkUrl) }
            .toList()

    /** Fetch + parse the releases list for [repo]; [apkMatch] picks the installable asset by name. Returns
     *  an empty list on any network/parse error (the picker degrades to "no versions").
     *
     *  Stable filtering discards the prereleases interleaved in the release list — some repos
     *  (home-assistant/android) publish many betas between stable tags, so the last [limit] releases may
     *  hold only a couple of stable ones. Fetch a larger page for the stable channel so [limit] stable
     *  versions still surface; the prerelease channel keeps everything, so one page of [limit] is enough. */
    fun list(repo: String, channel: String, limit: Int, apkMatch: (String) -> Boolean, normalize: (String) -> String): List<Version> {
        val perPage = if (channel == "prerelease") limit else minOf(100, limit * 8)
        return runCatching { select(fetch(repo, perPage, apkMatch), channel, limit, normalize) }
            .getOrElse { Log.w(TAG, "list $repo failed", it); emptyList() }
    }

    /** Resolve the installable APK asset URL for an exact [tag] in [repo], or null (unknown tag / no asset
     *  / invalid tag). */
    fun apkUrl(repo: String, tag: String, apkMatch: (String) -> Boolean): String? {
        if (!validTag(tag)) return null
        return runCatching {
            val json = get("https://api.github.com/repos/$repo/releases/tags/$tag") ?: return null
            assetUrl(JSONObject(json), apkMatch)
        }.getOrElse { Log.w(TAG, "apkUrl $repo $tag failed", it); null }
    }

    private fun fetch(repo: String, limit: Int, apkMatch: (String) -> Boolean): List<Raw> {
        val json = get("https://api.github.com/repos/$repo/releases?per_page=$limit") ?: return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Raw(o.getString("tag_name"), o.optBoolean("prerelease", false), o.optString("html_url", ""), assetUrl(o, apkMatch))
        }
    }

    private fun assetUrl(release: JSONObject, apkMatch: (String) -> Boolean): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (apkMatch(a.optString("name"))) return a.optString("browser_download_url").ifEmpty { null }
        }
        return null
    }

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000; conn.readTimeout = 8_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        return if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
    }
}
