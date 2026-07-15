package io.github.maxlyth.hapaneld.shizuku

/** User-facing lifecycle state; plain data so status rendering and retry policy stay unit-testable. */
enum class ShizukuState {
    MANAGER_MISSING,
    MANAGER_UNTRUSTED,
    STOPPED,
    PERMISSION_REQUIRED,
    MANUAL_GRANT_REQUIRED,
    BINDING,
    READY,
    INCOMPATIBLE,
    ERROR,
}

internal object ShizukuPolicy {
    const val MANAGER_PACKAGE = ShizukuManagerIdentity.PACKAGE
    const val SHELL_UID = 2000
    const val PROTOCOL_VERSION = 1
    const val MIN_DPI = 80
    const val MAX_DPI = 640
    const val MIN_FONT_SCALE = 0.5f
    const val MAX_FONT_SCALE = 1.5f
    const val MAX_SCREENSHOT_BYTES = 32 * 1024 * 1024
    const val MAX_APK_BYTES = 512L * 1024L * 1024L

    fun usable(uid: Int, protocol: Int): Boolean =
        uid == SHELL_UID && protocol == PROTOCOL_VERSION

    fun validKeyCode(keyCode: Int): Boolean = keyCode in 0..320
    fun validCoordinate(value: Int): Boolean = value in 0..100_000
    fun validDensity(dpi: Int): Boolean = dpi in MIN_DPI..MAX_DPI
    fun validFontScale(scale: Float): Boolean =
        scale.isFinite() && scale in MIN_FONT_SCALE..MAX_FONT_SCALE
    fun validApkLength(length: Long): Boolean = length in 1..MAX_APK_BYTES

    /** The client deadline must outlive the UserService process deadline and its one-second reader join. */
    fun clientDeadline(innerTimeoutMs: Long): Long =
        if (innerTimeoutMs > Long.MAX_VALUE - 5_000L) Long.MAX_VALUE else innerTimeoutMs + 5_000L

    fun canAcceptBinding(
        callbackGeneration: Long,
        currentGeneration: Long,
        connectionIsCurrent: Boolean,
        consentEnabled: Boolean,
        managerTrusted: Boolean,
        identityUsable: Boolean,
    ): Boolean = callbackGeneration == currentGeneration && connectionIsCurrent && consentEnabled &&
        managerTrusted && identityUsable

    /**
     * Shizuku reports a rationale after the user has denied access and its prompt cannot be used to
     * recover the grant. Only a fresh explicit opt-in may open the prompt; denied access must send the
     * user to Shizuku's Authorized applications screen instead of repeatedly requesting permission.
     */
    fun shouldRequestPermission(explicitRequest: Boolean, rationaleRequired: Boolean): Boolean =
        explicitRequest && !rationaleRequired
}
