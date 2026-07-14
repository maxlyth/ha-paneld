package io.github.maxlyth.hapaneld

import android.app.Application
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import io.github.maxlyth.hapaneld.shizuku.ShizukuBridge

/**
 * On Android 9- panels (no system dark/light setting — the NSPanel Pro fleet) the DayNight default is
 * set from the panel's `dark_mode` config before any activity is created, so ha-paneld's own screens
 * (admin launcher, dialogs, on-device config) follow the Display-card toggle. Android 10+ panels have
 * a real OS control and are left on FOLLOW_SYSTEM — ha-paneld never fights the system setting there.
 * Runtime changes are applied by the config POST handler; this covers process start.
 */
class HaPaneldApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Registers only the official Binder lifecycle listeners. No service is bound and no permission
        // is requested until the user opts in locally through the on-panel setup surface.
        ShizukuBridge.initialize(this)
        if (Build.VERSION.SDK_INT < 29) {
            AppCompatDelegate.setDefaultNightMode(
                if (Config(this).darkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO,
            )
        }
    }
}
