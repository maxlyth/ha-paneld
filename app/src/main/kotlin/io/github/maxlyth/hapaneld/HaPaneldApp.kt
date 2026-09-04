package io.github.maxlyth.hapaneld

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import io.github.maxlyth.hapaneld.shizuku.ShizukuBridge
import io.github.maxlyth.hapaneld.control.RemoteDebugSecurityTransitionGate
import io.github.maxlyth.hapaneld.util.GuardDbStartupAcknowledger
import io.github.maxlyth.hapaneld.util.GuardDbProcessAdmission
import io.github.maxlyth.hapaneld.util.GuardDbSentinelLoad

/**
 * On Android 9- panels (no system dark/light setting — the NSPanel Pro fleet) the DayNight default is
 * set from the panel's `dark_mode` config before any activity is created, so ha-paneld's own screens
 * (admin launcher, dialogs, on-device config) follow the Display-card toggle. Android 10+ panels have
 * a real OS control and are left on FOLLOW_SYSTEM — ha-paneld never fights the system setting there.
 * Runtime changes are applied by the config POST handler; this covers process start.
 */
class HaPaneldApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Installed before providers/components: every security/debug mutation publishes a durable
        // TRANSITION epoch, and maintenance successors can authenticate HARDENED without opening DB.
        RemoteDebugSecurityTransitionGate.install(base.noBackupFilesDir)
        // This is earlier than ContentProvider creation. Merely finding a valid or corrupt marker
        // closes ordinary DB/service admission until onCreate reconciles the root journal.
        GuardDbProcessAdmission.prime(base)
    }

    override fun onCreate() {
        super.onCreate()
        // Guard DB recovery returns before Config may open the protected database. Its UI and foreground
        // notification still need the last selected language, so restore it from Config's read-only 0.9.x
        // compatibility mirror before taking that early return.
        NativeLocale.applyBeforeDatabase(this)
        // A helper-owned replacement transaction must settle before Config or any service/background
        // producer opens shared state. This performs only the expected migration/proof/nonce-bound ACK.
        if (!GuardDbStartupAcknowledger.reconcileBeforeServices(this)) {
            if (GuardDbProcessAdmission.current() is GuardDbSentinelLoad.Valid) {
                GuardDbMaintenanceService.start(this)
            }
            return
        }
        // Registers only the official Binder lifecycle listeners. No service is bound and no permission
        // is requested until the user opts in locally through the on-panel setup surface.
        ShizukuBridge.initialize(this)
        // The database remains authoritative after ordinary admission; correct any stale compatibility
        // mirror before activities or the foreground service render user-visible resources.
        NativeLocale.apply(Config(this).uiLanguage)
        if (Build.VERSION.SDK_INT < 29) {
            AppCompatDelegate.setDefaultNightMode(
                if (Config(this).darkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO,
            )
        }
    }
}
