package io.github.maxlyth.hapaneld

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.github.maxlyth.hapaneld.i18n.AppLocale
import io.github.maxlyth.hapaneld.security.SensitiveOperation

/** Keep native Android resources on the same explicit language selected for ha-paneld's web UI. */
internal object NativeLocale {
    fun apply(raw: String) {
        val locale = raw.takeUnless { it.equals("auto", ignoreCase = true) }
            ?.let { AppLocale.canonical(it, allowPseudo = BuildConfig.DEBUG) }
        val desired = locale?.let(LocaleListCompat::forLanguageTags) ?: LocaleListCompat.getEmptyLocaleList()
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != desired.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(desired)
        }
    }
}

internal fun Context.localizedLabel(operation: SensitiveOperation): String = getString(
    when (operation) {
        SensitiveOperation.APK_INSTALL -> R.string.approval_op_apk_install
        SensitiveOperation.APK_FETCH -> R.string.approval_op_apk_fetch
        SensitiveOperation.BACKUP_EXPORT -> R.string.approval_op_backup_export
        SensitiveOperation.CONFIG_SECRET_EXPORT -> R.string.approval_op_secret_export
        SensitiveOperation.CONFIG_IMPORT -> R.string.approval_op_config_import
        SensitiveOperation.BACKUP_RESTORE -> R.string.approval_op_backup_restore
        SensitiveOperation.PACKAGE_UNINSTALL -> R.string.approval_op_package_uninstall
        SensitiveOperation.PROFILE_ACTIVATE -> R.string.approval_op_profile_activate
        SensitiveOperation.DEVTOOLS_ENABLE -> R.string.approval_op_devtools_enable
        SensitiveOperation.REMOTE_MEDIA -> R.string.approval_op_remote_media
        SensitiveOperation.PACKAGE_TAME -> R.string.approval_op_package_tame
        SensitiveOperation.DASHBOARD_RELOAD -> R.string.approval_op_dashboard_reload
        SensitiveOperation.DEVICE_REBOOT -> R.string.approval_op_device_reboot
        SensitiveOperation.COMPANION_REPAIR -> R.string.approval_op_companion_repair
        SensitiveOperation.DASHBOARD_STORAGE_CLEAR -> R.string.approval_op_dashboard_storage_clear
        SensitiveOperation.DISPLAY_CONFIGURATION -> R.string.approval_op_display_configuration
        SensitiveOperation.POWER_CONFIGURATION -> R.string.approval_op_power_configuration
        SensitiveOperation.POWER_SAFETY_ACKNOWLEDGEMENT -> R.string.approval_op_power_safety_ack
        SensitiveOperation.GUARD_DB_MAINTENANCE -> R.string.approval_op_guard_db
        SensitiveOperation.CAMERA_ENABLE -> R.string.approval_op_camera_enable
    },
)
