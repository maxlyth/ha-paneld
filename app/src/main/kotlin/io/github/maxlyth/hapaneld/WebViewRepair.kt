package io.github.maxlyth.hapaneld

/**
 * Whether the panel can repair its own Android System WebView, and what to say when it cannot.
 *
 * The repair itself already exists and is not re-implemented here: `WebViewInstaller.heal` installs the
 * device profile's pinned build over a privileged route, and the Install page has offered it as one
 * click for several releases. What was missing is the connection to the screen that is actually blocked
 * by the fault — a panel parked on "this panel's web viewer is too old" told whoever was standing there
 * to go and update something, while a one-tap repair for that exact fault sat one page away on a device
 * whose dashboard was down.
 */

/**
 * What the panel knows about repairing its own WebView, as one value.
 *
 * Read on the service side, where the privilege probe and the device profile live, and handed to the
 * screen whole. A screen that asked for these one at a time could pair a stale privilege answer with a
 * fresh profile answer and offer a button that cannot work.
 */
internal data class WebViewRepairCapability(
    /** The device profile pins a known-good build for this panel model. */
    val hasKnownGoodBuild: Boolean,
    /** A privileged install route is ready — su or the root helper daemon. */
    val privileged: Boolean,
    /** Something else already owns updating this WebView, so ha-paneld must not fight it. */
    val managedElsewhere: Boolean,
)

/** What a blocked screen can offer, and — when it can offer nothing — which true reason to give. */
internal enum class WebViewRepairOffer {
    /** This screen is not about the WebView, so it says nothing about repairing one. */
    NOT_REPAIRABLE,

    /** The panel can install a known-good build itself. */
    OFFER,

    /** A build exists for this panel, but nothing here is allowed to install it. */
    NEEDS_PRIVILEGE,

    /** Nothing is pinned for this panel model, so there is no build to install. */
    NO_KNOWN_GOOD_BUILD,

    /** The panel gets its WebView from a store, which will replace it more safely than this would. */
    MANAGED_ELSEWHERE,

    /**
     * The panel has not worked out yet whether it can repair itself.
     *
     * A separate answer rather than a pessimistic one. Deciding costs a privileged probe that must not
     * run on the drawing thread, so it is computed away from the screen and cached; until the first
     * answer lands, saying "there is no known-good version for this panel" would be a confident claim
     * made without evidence, and saying nothing is merely incomplete. The screen falls back to what it
     * showed before this existed, and the next repaint tells the truth.
     */
    UNKNOWN_CAPABILITY,
}

/**
 * Decide what the blocked screen offers.
 *
 * [providerRepairableAdmission] is the authority for the outcome half and is deliberately CALLED rather
 * than restated. That is not tidiness: it is what keeps this from widening. That predicate already
 * answers `true` for exactly one verdict and documents at length why `BRIDGE_ATTACH_FAILED` — the near
 * neighbour a reader would expect to see here — is excluded, so a repair offer cannot reach a screen
 * that is already recovering on its own unless somebody changes the rule in the one place it is written.
 *
 * **`tooOld` is deliberately not a condition, and this is the one place this differs from the Install
 * page's `canHeal`.** That page has no admission verdict to lean on, so it asks whether the engine looks
 * old as its evidence that anything is wrong. Here the verdict IS the evidence, and it is better
 * evidence: the panel has just failed to find the capability the dashboard needs on the engine it
 * actually bound. Requiring the version check as well would refuse the repair on precisely the panels
 * that need it most — a build whose version reads fine but whose engine cannot do the work — which is a
 * gate that only ever turns away the people it cannot harm.
 */
internal fun webViewRepairOffer(
    outcome: AdmissionOutcome?,
    capability: WebViewRepairCapability?,
): WebViewRepairOffer {
    if (outcome == null || !providerRepairableAdmission(outcome)) return WebViewRepairOffer.NOT_REPAIRABLE
    if (capability == null) return WebViewRepairOffer.UNKNOWN_CAPABILITY
    if (capability.managedElsewhere) return WebViewRepairOffer.MANAGED_ELSEWHERE
    if (!capability.hasKnownGoodBuild) return WebViewRepairOffer.NO_KNOWN_GOOD_BUILD
    if (!capability.privileged) return WebViewRepairOffer.NEEDS_PRIVILEGE
    return WebViewRepairOffer.OFFER
}

/** What happened when somebody asked for the repair. */
internal enum class WebViewRepairRequest {
    /** The install has the lane and is running. Nothing else will be reported here — see below. */
    STARTED,

    /** Another install already holds the lane; this one was not queued. */
    BUSY,

    /** Nothing is attached to run it, so nothing was attempted. */
    UNAVAILABLE,
}

/**
 * The repair's own progress, as the screen can observe it.
 *
 * **There is no success state, and that is not an omission.** A WebView provider binds once per process,
 * so a panel that has installed a new engine cannot use it until it restarts — which is exactly what
 * `PaneldService.activateWebView` does a few seconds after a successful install, and what
 * [webViewRebindDecision] independently arranges when the package event arrives. The screen therefore
 * ends a successful repair by disappearing along with the process that drew it, and the panel comes back
 * through the normal admission sequence on the other side. Anything painted as "done" would be a claim
 * this code is in no position to make and would, in the ordinary case, never be seen.
 */
internal data class WebViewRepairProgress(
    val running: Boolean,
    /** The installer's own last word. Empty until it says something. */
    val message: String,
)

/**
 * How a blocked screen reaches the repair.
 *
 * The same attach/detach shape as `EntityLearningRuntime`, for the same reason: the screen lives in an
 * activity, the repair lives in the service, and neither may hold the other. Every accessor degrades to
 * "cannot" rather than throwing, so a screen drawn before the service attaches is honest rather than
 * broken.
 */
internal object WebViewRepairRuntime {
    /** Supplies the capability answer; null when nothing is attached. */
    @Volatile private var capabilitySource: (() -> WebViewRepairCapability?)? = null

    /** Starts the repair, answering whether it took the lane. */
    @Volatile private var starter: (() -> Boolean)? = null

    /** Reports the running install; the installer's single slot is the authority, not a copy of it. */
    @Volatile private var progressSource: (() -> WebViewRepairProgress)? = null

    @Synchronized fun attach(
        capability: () -> WebViewRepairCapability?,
        start: () -> Boolean,
        progress: () -> WebViewRepairProgress,
    ) {
        capabilitySource = capability
        starter = start
        progressSource = progress
    }

    @Synchronized fun detach() {
        capabilitySource = null
        starter = null
        progressSource = null
    }

    fun capability(): WebViewRepairCapability? = capabilitySource?.invoke()

    fun request(): WebViewRepairRequest {
        val start = starter ?: return WebViewRepairRequest.UNAVAILABLE
        return if (start()) WebViewRepairRequest.STARTED else WebViewRepairRequest.BUSY
    }

    fun progress(): WebViewRepairProgress =
        progressSource?.invoke() ?: WebViewRepairProgress(running = false, message = "")
}
