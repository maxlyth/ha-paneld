package io.github.maxlyth.hapaneld.control

/**
 * Process-local foreground state of the built-in [io.github.maxlyth.hapaneld.DashboardActivity].
 *
 * The built-in renderer runs inside ha-paneld's own process, so — unlike a foreign dashboard app —
 * its liveness/foreground can be read directly from an activity lifecycle flag instead of probed with
 * root `pidof`/`dumpsys`. [SystemController.dashboardState] reads this for the `builtin` dashboard so
 * the watchdog + kiosk return-loop work with zero root and zero IPC. Pure Kotlin (no Android imports)
 * so the controller stays unit-testable.
 */
object BuiltinDashboard {
    // --- foreground state + change fan-out (navbar overlay-strip suppression) ---
    //
    // While the built-in renderer is foreground, [NavbarController] must NOT arm its full-width bottom
    // overlay strip (which consumes every touch in its 48dp band) — the renderer detects the
    // swipe-reveal gesture in-activity instead (zero-latency taps, no root). The controller registers
    // here to learn foreground changes; the fan-out fires on CHANGE only (all writers are main-thread
    // lifecycle callbacks, so the read-modify-write is safe without extra locking).
    @Volatile private var foregroundListener: ((Boolean) -> Unit)? = null

    /** [NavbarController] registers its strip suppress/re-arm handler here. */
    fun setForegroundListener(l: ((Boolean) -> Unit)?) { foregroundListener = l }

    /** True while DashboardActivity is resumed (foreground). Set from its onResume/onPause. */
    @Volatile var foreground = false
        set(value) {
            val changed = field != value
            field = value
            if (changed) foregroundListener?.invoke(value)
        }

    // --- navbar swipe-reveal handoff ---
    //
    // Non-null exactly while navbar mode is "Swipe reveal" (set/cleared by [NavbarController.apply]), so
    // the renderer's edge detector arms only when a reveal can actually happen — never steals a scroll
    // gesture for a no-op. The renderer's `BottomSwipeFrame` reads [navbarSwipeEnabled] at each DOWN and
    // calls [requestNavbarReveal] on a qualifying edge-swipe.
    @Volatile private var navbarReveal: (() -> Unit)? = null

    /** [NavbarController.apply] publishes its reveal trigger here for "Swipe reveal" mode, null otherwise. */
    fun setNavbarRevealHandler(h: (() -> Unit)?) { navbarReveal = h }

    /** True while a bottom-edge swipe should reveal the soft navbar (i.e. navbar mode is "Swipe reveal"). */
    val navbarSwipeEnabled: Boolean get() = navbarReveal != null

    /** Fired by the renderer's `BottomSwipeFrame` when a bottom-edge swipe qualifies; no-op if unset. */
    fun requestNavbarReveal() { navbarReveal?.invoke() }

    /** The dashboard path the renderer should show — set by an MQTT `navigate` command, then the
     *  renderer is (re)launched and reads this on load. Null = fall back to the configured home
     *  dashboard. Lets `navigate` drive the built-in WebView the way a deep link drives the Companion. */
    @Volatile var navPath: String? = null

    /** Consume-once read of [navPath]: a navigate is a one-shot target, not a standing state — without
     *  this, one MQTT navigate becomes the permanent reload target of every later crash rebuild and
     *  interstitial recovery (which should return to the configured home instead). */
    @Synchronized fun consumeNavPath(): String? = navPath.also { navPath = null }

    // A singleTask relaunch reaching the live renderer can mean two different things: "reload the page"
    // (an explicit reload command / changed credentials) or merely "come to the foreground" (the kiosk /
    // watchdog return loops). The launch travels through `am start`, which can't carry that distinction
    // reliably — instead reload requesters set this in-process flag just before launching, and the
    // renderer's onNewIntent consumes it. Absent flag + healthy page = foreground only, so a kiosk
    // snap-back no longer blanks the dashboard with a full reload.
    @Volatile private var reloadRequested = false

    fun requestReload() { reloadRequested = true }

    @Synchronized fun consumeReloadRequest(): Boolean = reloadRequested.also { reloadRequested = false }

    // --- renderer crash-loop budget + latch (process-global, NOT per-activity-instance) ---
    //
    // The budget must outlive the activity: a crash fallback finishes the activity, the kiosk/watchdog
    // return loop relaunches a fresh instance, and a per-instance counter resets — an infinite
    // fallback/relaunch churn with no backoff. Held here so every instance shares one budget, and when
    // it's exhausted the renderer LATCHES: [SystemController.dashboardState] reports DEAD (engaging the
    // watchdog's existing crash-loop backoff + health warning) and startBuiltin refuses automatic
    // relaunches until the latch expires. An explicit reload (MQTT/`:8888`/navbar) clears the latch —
    // deliberate user action is always honoured. Time params are caller-supplied elapsed-realtime
    // millis so this object stays pure Kotlin (JVM-unit-testable).
    private var rebuilds = 0
    private var rebuildWindowStart = 0L
    @Volatile private var rendererLatchedUntil = 0L

    /** Spend one renderer (re)build from the budget; false = budget exhausted, latch engaged. */
    @Synchronized fun consumeRebuildBudget(now: Long): Boolean {
        if (now - rebuildWindowStart > REBUILD_WINDOW_MS) { rebuildWindowStart = now; rebuilds = 0 }
        if (++rebuilds <= MAX_REBUILDS) return true
        rendererLatchedUntil = now + RENDERER_LATCH_MS
        return false
    }

    /** True while automatic relaunches are suppressed after a crash-loop (latch not yet expired). */
    fun rendererLatched(now: Long): Boolean = now < rendererLatchedUntil

    /** Clear the crash-loop latch + budget — called on an explicit reload (deliberate retry consent). */
    @Synchronized fun clearRendererLatch() { rendererLatchedUntil = 0L; rebuilds = 0; rebuildWindowStart = 0L }

    const val MAX_REBUILDS = 3
    const val REBUILD_WINDOW_MS = 60_000L
    const val RENDERER_LATCH_MS = 10 * 60_000L      // one 3-attempt burst per 10 min, not per 1.2s poll

    // Screen-state fan-out to the live renderer. A 24/7 dashboard WebView keeps churning CPU (websocket
    // state, animations, JS timers) behind a dark screen, so when the panel screen goes off/on we tell
    // the renderer to pause/resume the WebView. The activity registers a listener (and must marshal to
    // the UI thread itself); [ScreenController] pokes it. Pure Kotlin so the controller stays testable.
    @Volatile private var screenListener: ((Boolean) -> Unit)? = null

    /** Renderer registers its pause/resume handler here. */
    fun setScreenListener(l: ((Boolean) -> Unit)?) { screenListener = l }

    /** Clear the listener only if it's still [l] — so a destroyed old activity instance can't wipe a
     *  newer instance's registration when their lifecycles overlap. */
    fun clearScreenListener(l: (Boolean) -> Unit) { if (screenListener === l) screenListener = null }

    /** Last screen state pushed by [ScreenController] — read by a renderer CREATED while the panel is
     *  dark (a watchdog/kiosk relaunch at night), which would otherwise assume the screen is on, arm the
     *  handshake watchdog, and never freeze until the next real screen transition. Defaults awake (no
     *  transition seen since process start = panel presumed on). */
    @Volatile var screenAwakeNow = true
        private set

    /** [ScreenController] calls this from sleep()/wake(); no-op when no renderer is listening. */
    fun onScreenAwake(awake: Boolean) { screenAwakeNow = awake; screenListener?.invoke(awake) }

    /** True while the renderer is latched on "HA definitively rejected our credential" (revoked token /
     *  repeated auth-invalid). Read by the `:8888` health warnings so the failure is visible off-panel;
     *  cleared when a reload/navigate arrives or the frontend connects. */
    @Volatile var authLatched = false
}
