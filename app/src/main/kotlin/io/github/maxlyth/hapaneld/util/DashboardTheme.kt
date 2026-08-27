package io.github.maxlyth.hapaneld.util

/**
 * Vocabulary and pure decisions for the built-in dashboard's colour-scheme policy (`dashboard_theme`).
 *
 * This is deliberately a SEPARATE authority from `dark_mode`, which keeps its existing meaning: it
 * themes ha-paneld's own native screens and supplies the dashboard's DEFAULT scheme on panels with no
 * system dark-mode setting. `dark_mode` proposes; a theme picked inside Home Assistant still wins.
 *
 * This policy answers the different question the default cannot: "whatever Home Assistant has stored,
 * show the dashboard dark (or light)". [FOLLOW] is the default and means exactly today's behaviour —
 * ha-paneld does not decide, and never writes a value it did not already write itself.
 */
object DashboardTheme {
    /** Home Assistant owns the dashboard's scheme; ha-paneld only supplies the `dark_mode` default. */
    const val FOLLOW = "Follow Home Assistant"
    const val DARK = "Dark"
    const val LIGHT = "Light"

    const val DEFAULT = FOLLOW

    val OPTIONS = listOf(FOLLOW, DARK, LIGHT)

    /**
     * Spellings accepted in place of the declared options. The ENUM matcher is already
     * case-insensitive, so `dark`/`light` need no entry; these exist so the long [FOLLOW] label can be
     * written as one word from a script, an automation or a config bundle. Retired spellings would go
     * here too — an option is never renamed, because a restore is all-or-nothing and one unrecognised
     * value takes the whole archive down with it.
     */
    val ALIASES = mapOf(
        "follow" to FOLLOW,
        "follow_home_assistant" to FOLLOW,
        "follow-home-assistant" to FOLLOW,
        "auto" to FOLLOW,
    )

    /**
     * Canonical policy for a persisted [raw] value; unknown or blank falls back to [DEFAULT].
     *
     * This deliberately does NOT resolve [ALIASES]. Every inbound path — the config POST, import and
     * restore — runs `SettingValue.validate` first, which resolves an alias and normalises the casing
     * before anything is persisted, so what reaches here is already a declared option or a value from
     * a build that declared a different set. Repeating the alias pass here would be unreachable code
     * that no test can fail, which is worse than no guard: it reads as coverage without being any.
     */
    fun policy(raw: String?): String {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return DEFAULT
        return OPTIONS.firstOrNull { it.equals(v, ignoreCase = true) } ?: DEFAULT
    }

    /**
     * The scheme this policy forces, or null when Home Assistant keeps ownership. Null is the whole
     * point of [FOLLOW]: it is not "force whatever ha-paneld thinks", it is "do not decide".
     */
    fun forcedDark(policy: String?): Boolean? = when (policy(policy)) {
        DARK -> true
        LIGHT -> false
        else -> null
    }

    /** True when [policy] takes ownership of Home Assistant's stored theme. */
    fun forces(policy: String?): Boolean = forcedDark(policy) != null
}
