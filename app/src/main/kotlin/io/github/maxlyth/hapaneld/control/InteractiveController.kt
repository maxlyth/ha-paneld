package io.github.maxlyth.hapaneld.control

import android.view.KeyEvent
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.input.PanelAccessibilityService
import io.github.maxlyth.hapaneld.platform.AccessibilityActions
import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.RootShell
import io.github.maxlyth.hapaneld.util.HelperClient

/**
 * Live routing for interactive operations whose usable transport can differ from profile metadata.
 * [DeviceProfile.appCanSu] orders attempts only: known su panels try root first, while sandboxed
 * profiles try the helper or accessibility service first. An operation-level failure always falls
 * through to the other safe route.
 *
 * Screenshot deliberately keeps the helper's raw-byte protocol: empty bytes/EOF are failure, not a
 * textual `ERR` reply. A non-empty byte stream is accepted exactly as it was by the HTTP endpoint.
 */
internal class InteractiveController(
    private val canSu: Boolean = DeviceProfile.detect().appCanSu,
    private val root: RootShell = Su,
    private val daemon: Daemon = HelperClient,
    private val accessibility: AccessibilityActions = PanelAccessibilityService,
) {
    fun screenshot(): ByteArray? {
        val su = ValueAttempt(PrivilegeRoute.SU) {
            root.runBytes("screencap -p")?.takeUnless { it.isEmpty() }
        }
        val helper = ValueAttempt(PrivilegeRoute.DAEMON) {
            daemon.sendBytes("SCREENCAP")?.takeUnless { it.isEmpty() }
        }
        val attempts = if (canSu) arrayOf(su, helper) else arrayOf(helper, su)
        return ShortOperationRouter.value(*attempts)?.value
    }

    fun back(): Boolean = navigate(
        rootCommand = "input keyevent ${KeyEvent.KEYCODE_BACK}",
        accessibilityAction = accessibility::back,
    )

    fun recents(): Boolean = navigate(
        rootCommand = "input keyevent ${KeyEvent.KEYCODE_APP_SWITCH}",
        accessibilityAction = accessibility::recents,
    )

    fun tap(x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite() || x < 0f || y < 0f || x > Int.MAX_VALUE || y > Int.MAX_VALUE) {
            return false
        }
        val xi = x.toInt()
        val yi = y.toInt()
        return routeInput(
            su = { root.run("input tap $xi $yi") },
            accessibility = { accessibility.tap(xi, yi) },
        )
    }

    private fun navigate(rootCommand: String, accessibilityAction: () -> Boolean): Boolean =
        routeInput(
            su = { root.run(rootCommand) },
            accessibility = accessibilityAction,
        )

    private fun routeInput(su: () -> Boolean, accessibility: () -> Boolean): Boolean {
        val suAttempt = EffectAttempt(PrivilegeRoute.SU, su)
        val accessibilityAttempt = EffectAttempt(PrivilegeRoute.ACCESSIBILITY, accessibility)
        val attempts = if (canSu) arrayOf(suAttempt, accessibilityAttempt)
            else arrayOf(accessibilityAttempt, suAttempt)
        return ShortOperationRouter.effect(*attempts) != null
    }
}
