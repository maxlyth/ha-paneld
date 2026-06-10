package io.github.maxlyth.hapaneld.control

import android.os.Build

/**
 * System navigation-bar visibility, exposed to HA as a `select`. Uses Android's global immersive override
 * `settings global policy_control` — so the hidden modes get the OS's NATIVE swipe-from-edge transient
 * reveal + auto-hide for free, with no custom gesture detector or overlay (unlike NSPanelTools, which
 * hand-rolls both). The setting is system-wide (applies to the foreground dashboard too) and persists
 * across reboot, so there's no boot-restore to do — the live state is read straight back from it.
 *
 * `policy_control` was removed in **Android 11+**, so this only applies to the Sonoff NSPanel Pro class
 * (Android 8.1) and other ≤10 panels; [available] gates on that + su. Writes need WRITE_SECURE_SETTINGS,
 * hence the [Su] path.
 */
class NavbarController {

    /** True when the platform supports the override (Android ≤ 10) and we can write global settings (su). */
    fun available(): Boolean = Build.VERSION.SDK_INT <= 29 && Su.available()

    /** Current mode, reverse-mapped from `policy_control` (anything not an immersive value reads as Shown). */
    fun current(): String = when (Su.runOutput("settings get global policy_control")?.trim()) {
        "immersive.full=*" -> HIDDEN_FULL
        "immersive.navigation=*" -> HIDDEN_NAV
        else -> SHOWN // "null" / empty / unset
    }

    /** Apply [mode]. The value is single-quoted so the shell doesn't glob the `*`. Returns true if it ran. */
    fun set(mode: String): Boolean {
        val v = when (mode) {
            HIDDEN_NAV -> "'immersive.navigation=*'"
            HIDDEN_FULL -> "'immersive.full=*'"
            else -> "null" // SHOWN — clear the override
        }
        return Su.run("settings put global policy_control $v")
    }

    companion object {
        const val SHOWN = "Shown"
        const val HIDDEN_NAV = "Hidden (swipe to reveal)"
        const val HIDDEN_FULL = "Hidden — full screen"
        val MODES = listOf(SHOWN, HIDDEN_NAV, HIDDEN_FULL)
    }
}
