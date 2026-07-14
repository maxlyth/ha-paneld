package io.github.maxlyth.hapaneld.dashboard

/**
 * True while automatic learning has no allow-list that can safely be installed at document start.
 *
 * This is deliberately a renderer-side invariant rather than a provisioning convention. Activity,
 * watchdog and HOME relaunches can happen at any point during a catalog synchronization; none may turn
 * an empty automatic learner into an ordinary unfiltered Home Assistant WebSocket connection.
 */
internal fun shouldHoldRendererForEntityBootstrap(
    learningEnabled: Boolean,
    filterEnabled: Boolean,
    entityIds: Collection<String>,
): Boolean = learningEnabled && (!filterEnabled || entityIds.isEmpty())

/** A learner may continue synchronizing while another renderer is selected, but its completion must not
 * launch the built-in activity unless that activity is still the configured renderer. */
internal fun shouldReloadBuiltinAfterEntityFilterChange(
    dashboardPackage: String,
    builtinPackage: String,
): Boolean = dashboardPackage == builtinPackage
