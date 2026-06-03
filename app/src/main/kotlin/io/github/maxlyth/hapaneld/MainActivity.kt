package io.github.maxlyth.hapaneld

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Minimal launcher Activity. Its only jobs are to request the runtime notification permission
 * (Android 13+, needed for the foreground-service notification) and to start [PaneldService].
 * It shows a one-line status; the panel is expected to run the HA Companion WebView as HOME, so
 * this Activity is a setup/diagnostic surface, not the panel's day-to-day UI.
 */
class MainActivity : AppCompatActivity() {

    private val requestNotif =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { startAgent() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            text = "ha-paneld ${BuildConfig.VERSION_NAME}\nlistening on :${Config.DEFAULT_PORT}"
        }
        setContentView(status)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startAgent()
        }
    }

    private fun startAgent() = PaneldService.start(this)
}
