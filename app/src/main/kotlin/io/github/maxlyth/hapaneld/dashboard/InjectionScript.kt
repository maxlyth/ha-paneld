package io.github.maxlyth.hapaneld.dashboard

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/**
 * One home for the constants and computations shared by the built-in dashboard's document-start
 * JavaScript injections — the entity-filter WebSocket wrapper, the traffic observer, the
 * entity-learning observer, and the theme / panel-preference seeding scripts.
 *
 * Each of those builders previously copied the top-frame guard, the `selectedTheme` store key, and
 * the identical WebSocket target-selection (`targetWsOrigins` / `targetWsPath`) computation. This
 * object owns them so they are defined once instead of per script. It only centralises how the
 * shared *fragments* are produced; the composed script text every caller emits is byte-for-byte
 * unchanged (the constants expand to the exact literals they replaced, and [wsTargets] performs the
 * same computation the call sites did inline).
 */
internal object InjectionScript {
    /** Guard dropped at the top of every document-start snippet so it runs only in the top frame
     *  (never inside an embedded iframe). */
    const val TOP_FRAME_GUARD = "if(window.top&&window.top!==window)return;"

    /** HA's own per-device theme store key in `localStorage` — exactly what the profile page's
     *  Auto/Light/Dark radio writes, and the only lever that actually re-renders HA's theme. */
    const val SELECTED_THEME_KEY = "selectedTheme"

    /**
     * ha-paneld's own marker inside the same store, recording what the `dark` field held at the moment
     * a Dark/Light policy first took ownership of it.
     *
     * It exists so that returning to Follow Home Assistant is a hand-back rather than a guess. The
     * marker records ONLY the `dark` field and whether the whole entry was absent, because `dark` is
     * the only field the policy ever writes: a named theme and its colours live in the same object and
     * are the user's, so they are preserved on the way in and left alone on the way out.
     */
    const val FORCED_THEME_MARKER_KEY = "haPaneldForcedThemeDark"

    /** JS-literal identifiers for the single HA entity WebSocket a document-start wrapper is allowed
     *  to intercept: [origins] is a JSON array of the permitted `wss://`/`ws://` origins and [path]
     *  is the quoted API path. Both are ready to interpolate straight into a script template. */
    data class WsTargets(val origins: String, val path: String)

    /**
     * Compute the [WsTargets] for [haUrl] and the page [documentOrigins] that may host the renderer.
     * Shared by every document-start wrapper so they all intercept exactly the same socket; the
     * result is identical to the computation the call sites previously inlined.
     */
    fun wsTargets(haUrl: String, documentOrigins: Collection<String>): WsTargets {
        val upstream = URI(EntityFilterProtocol.upstreamWebSocketUrl(haUrl))
        val origins = JSONArray(documentOrigins.map(EntityFilterProtocol::origin).distinct().sorted().map {
            it.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        }).toString()
        val path = JSONObject.quote(upstream.rawPath)
        return WsTargets(origins, path)
    }
}
