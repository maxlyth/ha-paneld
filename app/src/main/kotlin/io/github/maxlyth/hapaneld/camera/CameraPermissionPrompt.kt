package io.github.maxlyth.hapaneld.camera

/**
 * Whether the visible activity should ask Android for the camera permission right now. Owned by the
 * session owner, which is the only party that knows all three of contract §1's gates: the profile
 * declares a camera, the master switch is on, and the permission is not yet held. The activity only
 * reads it, so a camera-less panel with an imported `camera_enabled=true` is never prompted.
 *
 * Process-global on purpose: it survives activity recreation, so a denial is remembered for as long
 * as the switch stays on rather than being asked again on the next rotation or renderer restart. A
 * fresh enable clears it, which is the one event that legitimately re-asks.
 */
object CameraPermissionPrompt {
    @Volatile private var wanted = false
    @Volatile private var declined = false
    @Volatile private var inFlight = false

    /** The owner publishes the gate state; a fresh enable also forgets an earlier denial. */
    fun publish(wantsPermission: Boolean, freshEnable: Boolean) {
        if (freshEnable) declined = false
        wanted = wantsPermission
    }

    fun shouldAsk(): Boolean = wanted && !declined && !inFlight

    fun asking() {
        inFlight = true
    }

    fun answered(granted: Boolean) {
        inFlight = false
        declined = !granted
        if (granted) wanted = false
    }

    /** Test seam. */
    internal fun reset() {
        wanted = false
        declined = false
        inFlight = false
    }
}
