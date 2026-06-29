package io.github.maxlyth.hapaneld.http

/**
 * Pure decisions for the info-page health banner (unit-tested in PanelHealthTest). These flag the
 * states that stop a panel rendering the dashboard as the user expects but that the info map otherwise
 * reports neutrally. The "no dashboard renderer" check needs the package manager, so it lives in
 * [PanelInfo.dashboardRenderers]; the WebView-age check is pure string parsing and lives here so it's
 * testable without an Android context.
 */
object PanelHealth {
    /** Chromium major below which the current Home Assistant frontend renders blank/broken. 107 was
     *  verified blank on an NSPanel Pro; the fleet target is 138. Matches the `check_webview` cutoff in
     *  scripts/provision.sh so the in-app warning and the provisioning warning agree. */
    const val MIN_CHROMIUM = 110

    /** Chromium major parsed from a "System WebView" value like "com.android.webview 107.0.5304.105"
     *  → 107. Null when the value has no dotted four-part version (e.g. "unknown"). */
    fun chromiumMajor(webView: String): Int? =
        Regex("""(\d+)\.\d+\.\d+\.\d+""").find(webView)?.groupValues?.get(1)?.toIntOrNull()

    /** True when the WebView version parses AND is below [MIN_CHROMIUM]. Unparseable/unknown → false:
     *  don't cry wolf — a Cromite SystemWebView swap reports the stale OEM version yet actually renders. */
    fun webViewTooOld(webView: String): Boolean =
        chromiumMajor(webView)?.let { it < MIN_CHROMIUM } ?: false
}
