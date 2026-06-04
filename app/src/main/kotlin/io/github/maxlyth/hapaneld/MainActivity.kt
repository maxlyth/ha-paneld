package io.github.maxlyth.hapaneld

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Launcher Activity. Requests the notification permission (Android 13+) and starts [PaneldService],
 * then shows a small standing UI. Previously it `finish()`ed immediately after starting the service —
 * on a panel with no real home app that just drops back to the launcher and looks like a crash. Now
 * tapping the icon offers: open the config page (in a browser) or jump to the dashboard app. The
 * agent runs headless as a foreground service regardless of this Activity.
 */
class MainActivity : AppCompatActivity() {

    private val requestNotif =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { PaneldService.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            PaneldService.start(this)
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#111111"))
            setPadding(64, 64, 64, 64)
        }
        root.addView(TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.WHITE)
            text = "ha-paneld ${BuildConfig.VERSION_NAME}\nrunning in the background · port ${Config.DEFAULT_PORT}"
            setPadding(0, 0, 0, 56)
        })
        root.addView(button("Open configuration") { open("http://127.0.0.1:${Config.DEFAULT_PORT}/") })
        root.addView(button("Open dashboard") { openDashboard() })
        return root
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 16f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#2557A7"))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 12, 0, 12) }
        setOnClickListener { onClick() }
    }

    private fun open(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun openDashboard() {
        for (p in listOf("io.homeassistant.companion.android.minimal", "io.homeassistant.companion.android")) {
            packageManager.getLaunchIntentForPackage(p)?.let {
                startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
            }
        }
    }
}
