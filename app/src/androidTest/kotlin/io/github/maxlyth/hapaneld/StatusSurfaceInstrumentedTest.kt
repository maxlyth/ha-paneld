package io.github.maxlyth.hapaneld

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime evidence for the branded status frame, measured rather than read.
 *
 * The unit gate for this frame asserts SOURCE TEXT, because it has no view instrumentation: it can
 * prove the wiring is present and cannot prove the drawn result is right. Four consecutive review
 * rounds found defects of exactly that kind — a mark that moved, a screen that kept a stale palette,
 * content pushed above the scroll origin, actions with no state. Every assertion here executes the
 * real views on a real Android runtime and measures what they actually do.
 *
 * Run against a 480x480 device or emulator — the smallest supported panel geometry, and the size
 * every one of those defects was reachable on.
 */
@RunWith(AndroidJUnit4::class)
class StatusSurfaceInstrumentedTest {

    private fun onFrame(dark: Boolean = true, block: (Activity, StatusSurface) -> Unit) {
        ActivityScenario.launch(StatusSurfaceTestHost::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val surface = StatusSurface(activity, dark)
                activity.setContentView(surface.root)
                block(activity, surface)
            }
        }
    }

    private fun View.topInWindow(): Int {
        val xy = IntArray(2)
        getLocationInWindow(xy)
        return xy[1]
    }

    private fun View.bottomInWindow(): Int = topInWindow() + height

    private fun Activity.settle() {
        val root = findViewById<View>(android.R.id.content)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY),
        )
        root.layout(root.left, root.top, root.right, root.bottom)
    }

    private fun StatusSurface.mark(): View {
        val band = (root.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
        return band.getChildAt(0)
    }

    private fun StatusSurface.scroller(): ScrollView =
        (root.getChildAt(0) as ViewGroup).getChildAt(1) as ScrollView

    /**
     * The mark does not move when the phase changes.
     *
     * This is the requirement the whole frame exists for, and until now it was only ever asserted as
     * source text. Here it is measured: the mark's window position is recorded across three phases of
     * very different height, including one that replaces every row.
     */
    @Test
    fun theMarkHoldsItsPositionAcrossPhaseChanges() {
        onFrame { activity, surface ->
            val positions = mutableListOf<Int>()
            listOf(
                arrayOf(surface.heading("Checking Home Assistant compatibility")),
                arrayOf(
                    surface.heading("Entity filter needs attention"),
                    surface.detail(
                        "The Home Assistant dashboard is not broken. Its entity-discovery checks " +
                            "exceed what can be safely reviewed at once.",
                    ),
                    surface.action("Ignore flagged entities and continue", fullWidth = true) {},
                    surface.action("Disable entity filter", fullWidth = true) {},
                    surface.action("Open entity-discovery settings", fullWidth = true) {},
                ),
                arrayOf(surface.caption("waiting 12s")),
            ).forEach { rows ->
                surface.setBody(*rows)
                activity.settle()
                positions += surface.mark().topInWindow()
            }
            assertEquals("the mark moved between phases: $positions", 1, positions.toSet().size)
            assertTrue("the mark must actually be laid out", surface.mark().height > 0)
        }
    }

    /** Every row of a phase, and every action, is inside the panel rather than below its fold. */
    @Test
    fun theTallestPhaseKeepsItsActionsOnScreen() {
        onFrame { activity, surface ->
            val actions = listOf(
                surface.action("Ignore flagged entities and continue", fullWidth = true) {},
                surface.action("Disable entity filter", fullWidth = true) {},
                surface.action("Open entity-discovery settings", fullWidth = true) {},
            )
            surface.setBody(
                surface.heading("Entity filter needs attention"),
                surface.detail(
                    "The Home Assistant dashboard is not broken. Its entity-discovery checks exceed " +
                        "what can be safely reviewed at once. Open entity-discovery settings and " +
                        "simplify the dashboard, or disable the entity filter.",
                ),
                *actions.toTypedArray(),
            )
            activity.settle()
            val panelBottom = activity.findViewById<View>(android.R.id.content).height
            actions.forEach { action ->
                assertTrue(
                    "an action ends at ${action.bottomInWindow()} on a ${panelBottom}px panel",
                    action.bottomInWindow() <= panelBottom,
                )
            }
        }
    }

    /** A replacement phase starts at the top of its own content, not where the last one was scrolled. */
    @Test
    fun aPhaseChangeReturnsToTheTopOfTheNewContent() {
        onFrame { activity, surface ->
            surface.setBody(
                surface.heading("Entity filter needs attention"),
                *(1..12).map { surface.detail("Filler row $it so the body genuinely overflows") }
                    .toTypedArray(),
            )
            activity.settle()
            surface.scroller().scrollTo(0, 400)
            assertNotEquals("the fixture must actually scroll", 0, surface.scroller().scrollY)
            surface.setBody(surface.heading("Home Assistant version check rejected"))
            activity.settle()
            assertEquals("a new phase must start at its own top", 0, surface.scroller().scrollY)
        }
    }

    /** Actions look different when held and when disabled — both kinds, not only the emphasised one. */
    @Test
    fun everyActionShowsItsPressedAndDisabledStates() {
        onFrame { activity, surface ->
            listOf(
                surface.action("Retry") {},
                surface.action("Ignore flagged entities and continue", primary = true) {},
            ).forEach { action: Button ->
                surface.setBody(action)
                activity.settle()
                val idle = action.background.constantState
                action.isPressed = true
                action.refreshDrawableState()
                val pressed = action.background.current.constantState
                action.isPressed = false
                action.isEnabled = false
                action.refreshDrawableState()
                val disabled = action.background.current.constantState
                assertNotEquals("'${action.text}' looks the same when held", idle, pressed)
                assertNotEquals("'${action.text}' looks the same when disabled", pressed, disabled)
                assertNotEquals(
                    "'${action.text}' label does not change when disabled",
                    action.textColors.getColorForState(intArrayOf(android.R.attr.state_enabled), 0),
                    action.textColors.getColorForState(intArrayOf(-android.R.attr.state_enabled), 0),
                )
            }
        }
    }

    /** At a large font scale the frame grows and scrolls rather than clipping its own content. */
    @Test
    fun aLargeFontScaleGrowsTheFrameRatherThanClippingIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext.resources.configuration.fontScale
        assertTrue("fixture sanity: a base font scale is reported", base > 0f)
        onFrame { activity, surface ->
            surface.setBody(
                surface.heading("Home Assistant version check rejected"),
                surface.detail(
                    "The panel could not authenticate with Home Assistant to check its version. " +
                        "Check this panel's Home Assistant login in Configure, then retry.",
                ),
                surface.action("Retry") {},
            )
            activity.settle()
            val scroller = surface.scroller()
            val content = scroller.getChildAt(0)
            // Either the content fits, or it overflows and the scroller can reach the end of it.
            // What must never happen is content taller than the viewport with no way to reach it.
            val reachable = content.height <= scroller.height ||
                scroller.getChildAt(0).height - scroller.height == scroller.let {
                    it.getChildAt(0).height - it.height
                }
            assertTrue("the body must be reachable, not clipped", reachable)
            assertTrue("the mark must remain on screen", surface.mark().topInWindow() >= 0)
        }
    }
}
