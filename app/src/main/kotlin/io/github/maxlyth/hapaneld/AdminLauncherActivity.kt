package io.github.maxlyth.hapaneld

import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * On-demand **admin launcher** for the panel — a slim app drawer reachable from the navbar Launcher
 * button (see [io.github.maxlyth.hapaneld.control.NavbarController]).
 *
 * **Why this exists:** the HA Companion is the panel's *home* app (it boots straight to the dashboard),
 * which is what you want 99% of the time. But administering a panel — opening Settings, another app,
 * or ha-paneld's own config — needs an app drawer, and the vendor's pseudo-launcher (when one is even
 * installed) fights HA/kiosk operation. Users previously sideloaded a third-party launcher (`l.l`) as a
 * "launcher of last resort" just for this. This Activity replaces that: a built-in, on-demand drawer
 * that never competes for HOME.
 *
 * It is a foreground Activity, so it can `startActivity` directly — no root / daemon needed to launch
 * an app from here (unlike [io.github.maxlyth.hapaneld.control.SystemController], which runs from the
 * background service and is blocked by Android 10+ background-activity-start).
 */
class AdminLauncherActivity : AppCompatActivity() {

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // Day/night palette, mirroring MainActivity so the two ha-paneld screens read consistently.
    private val dark: Boolean
        get() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val bg get() = if (dark) "#111111" else "#f3f4f6"
    private val cardBg get() = if (dark) "#1d1f24" else "#ffffff"
    private val body get() = if (dark) "#c8ccd2" else "#2a2e34"
    private val subtle get() = if (dark) "#8a8f99" else "#5a6068"
    private val accent get() = if (dark) "#2557a7" else "#1669d6"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KioskAdminUi.setVisible(this, true)
        supportActionBar?.hide()
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        KioskAdminUi.setVisible(this, true)
    }

    override fun onStop() {
        KioskAdminUi.setVisible(this, false)
        super.onStop()
    }

    override fun onDestroy() {
        KioskAdminUi.setVisible(this, false)
        super.onDestroy()
    }

    // Rebuild the drawer each time it's shown — the installed-app set and night mode can change while
    // ha-paneld stays resident.
    override fun onResume() {
        super.onResume()
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val dashboardTarget = dashboardTarget()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(text("Panel admin", 20f, body, bold = true, padBottom = 2))
        root.addView(text(
            "Quick access for administering this panel. The dashboard is the panel's home screen.",
            12.5f, subtle, padBottom = 14,
        ))

        // Admin shortcuts — the things you open a drawer to reach but that aren't ordinary app icons.
        root.addView(grid(adminTiles(dashboardTarget)))

        root.addView(text("Apps", 14f, subtle, bold = true, padTop = 18, padBottom = 8))
        val apps = installedApps(dashboardTarget)
        if (apps.isEmpty()) {
            root.addView(text("No launchable apps found.", 13f, subtle))
        } else {
            root.addView(grid(apps.map { ri ->
                Tile(
                    label = ri.loadLabel(packageManager).toString(),
                    icon = runCatching { ri.loadIcon(packageManager) }.getOrNull(),
                ) { launch(ri) }
            }))
        }

        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            isFillViewport = true
            addView(root, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    // --- tiles ---

    private data class Tile(
        val label: String,
        val icon: android.graphics.drawable.Drawable?,
        val badge: String? = null,
        val onClick: () -> Unit,
    )

    private fun adminTiles(dashboardTarget: RendererTarget?): List<Tile> = buildList {
        when (dashboardTarget) {
            RendererTarget.Builtin -> add(Tile("Dashboard", appIcon(packageName), "HA") {
                startSafely(Intent(this@AdminLauncherActivity, DashboardActivity::class.java))
            })
            is RendererTarget.Foreign -> add(Tile("Dashboard", appIcon(dashboardTarget.packageName), "HA") {
                packageManager.getLaunchIntentForPackage(dashboardTarget.packageName)?.let(::startSafely)
            })
            null -> Unit
        }
        add(Tile("Settings", appIcon("com.android.settings"), "⚙") {
            startSafely(Intent(Settings.ACTION_SETTINGS))
        })
        // ha-paneld's own front-door screen (wordmark + config URL/QR + dashboard/config buttons).
        add(Tile("ha-paneld", appIcon(packageName), "cfg") {
            startSafely(Intent(this@AdminLauncherActivity, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_EXPLICIT_ADMIN_ENTRY, true)
            })
        })
    }

    /** Lay tiles out in a column-count grid sized to the panel width (square 480 → 4 cols). */
    private fun grid(tiles: List<Tile>): GridLayout {
        val cellW = dp(84)
        val cols = (resources.displayMetrics.widthPixels / cellW).coerceIn(3, 6)
        return GridLayout(this).apply {
            columnCount = cols
            tiles.forEach { addView(tileView(it, cellW)) }
        }
    }

    private fun tileView(t: Tile, cellW: Int): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(10), dp(6), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(Color.parseColor(cardBg))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { t.onClick() }
            layoutParams = GridLayout.LayoutParams().apply {
                width = cellW; height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
        }
        val iconSize = dp(44)
        if (t.icon != null) {
            cell.addView(ImageView(this).apply {
                setImageDrawable(t.icon)
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            })
        } else {
            // Fallback glyph chip for admin tiles whose package has no icon (e.g. Settings on some ROMs).
            cell.addView(TextView(this).apply {
                gravity = Gravity.CENTER
                text = t.badge ?: t.label.take(1).uppercase()
                setTextColor(Color.WHITE)
                textSize = 16f
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(accent)) }
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            })
        }
        cell.addView(TextView(this).apply {
            gravity = Gravity.CENTER
            text = t.label
            setTextColor(Color.parseColor(body))
            textSize = 11.5f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        })
        return cell
    }

    // --- data + launching ---

    /**
     * Launchable apps, sorted by label, minus the packages already surfaced as curated admin tiles
     * (the selected dashboard renderer, Settings, ourselves) so the grid doesn't duplicate them. The drawer itself has no
     * LAUNCHER filter, so it's absent regardless.
     */
    private fun installedApps(dashboardTarget: RendererTarget?): List<ResolveInfo> {
        val curated = setOfNotNull(
            packageName,
            "com.android.settings",
            (dashboardTarget as? RendererTarget.Foreign)?.packageName,
        )
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            packageManager.queryIntentActivities(main, 0)
                .filterNot { it.activityInfo.packageName in curated }
                .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        }.getOrDefault(emptyList())
    }

    private fun launch(ri: ResolveInfo) {
        val ai = ri.activityInfo
        startSafely(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(ai.packageName, ai.name)
        })
    }

    private fun startSafely(intent: Intent) {
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "launch failed: ${intent.component ?: intent.action}", it) }
    }

    private fun appIcon(pkg: String): android.graphics.drawable.Drawable? =
        runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()

    private fun dashboardTarget(): RendererTarget? {
        val config = Config(this)
        return RendererResolver.resolveLaunchable(
            configuredPackage = config.dashboardPackage,
            builtinReady = config.haUrl.isNotBlank(),
            isLaunchable = { packageManager.getLaunchIntentForPackage(it) != null },
        )
    }

    private fun text(
        s: String, size: Float, color: String,
        bold: Boolean = false, padTop: Int = 0, padBottom: Int = 0,
    ): TextView = TextView(this).apply {
        textSize = size
        setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(padTop), 0, dp(padBottom))
        text = s
    }

    companion object {
        private const val TAG = "ha-paneld/admin-launcher"
    }
}
