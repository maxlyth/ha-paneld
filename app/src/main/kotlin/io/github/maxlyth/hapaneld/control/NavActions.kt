package io.github.maxlyth.hapaneld.control

import android.view.KeyEvent
import io.github.maxlyth.hapaneld.input.PanelAccessibilityService

/**
 * Back / Recents global navigation. Android forbids an app from injecting these from the sandbox, so
 * there are exactly two routes: root key injection, or an enabled accessibility service.
 *
 * Where the app can exec `su` (NSPanel Pro / SuperSU panels) we prefer **root `input keyevent`** — it
 * needs no accessibility service, so it sidesteps the fragile a11y-enable/bind dance (which can be
 * cleared by an uninstall, poisoned by a force-stop, or simply fail to bind). Where su is sandbox-blocked
 * (Tuya TPA10), we fall back to the accessibility global action.
 *
 * Both calls block on a `su`/binder round-trip — invoke from a background thread (the MQTT command
 * thread is fine; the overlay button offloads to a worker).
 */
object NavActions {

    fun back(appCanSu: Boolean): Boolean =
        if (appCanSu) Su.run("input keyevent ${KeyEvent.KEYCODE_BACK}")
        else PanelAccessibilityService.navBack()

    fun recents(appCanSu: Boolean): Boolean =
        if (appCanSu) Su.run("input keyevent ${KeyEvent.KEYCODE_APP_SWITCH}")
        else PanelAccessibilityService.navRecents()
}
