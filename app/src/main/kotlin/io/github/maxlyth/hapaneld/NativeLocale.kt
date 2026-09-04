package io.github.maxlyth.hapaneld

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.github.maxlyth.hapaneld.i18n.AppLocale
import io.github.maxlyth.hapaneld.security.SensitiveOperation
import java.util.Locale

/** Keep native Android resources on the same explicit language selected for ha-paneld's web UI. */
internal object NativeLocale {
    @Volatile private var explicitLanguageTag: String? = null

    /** Read only the downgrade-compatible XML mirror; Guard DB recovery must not construct Config/AppState. */
    fun applyBeforeDatabase(context: Context) {
        val raw = runCatching {
            context.applicationContext
                .getSharedPreferences(LEGACY_CONFIG_PREFERENCES, Context.MODE_PRIVATE)
                .getString(UI_LANGUAGE_KEY, AUTO_LANGUAGE)
        }.getOrNull()
        apply(raw ?: AUTO_LANGUAGE)
    }

    fun apply(raw: String) {
        val locale = raw.takeUnless { it.equals("auto", ignoreCase = true) }
            ?.let { AppLocale.canonical(it, allowPseudo = BuildConfig.DEBUG) }
        explicitLanguageTag = locale
        val desired = locale?.let(LocaleListCompat::forLanguageTags) ?: LocaleListCompat.getEmptyLocaleList()
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != desired.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(desired)
        }
    }

    fun string(context: Context, @StringRes id: Int, vararg formatArgs: Any): String {
        val tag = explicitLanguageTag
        val localized = if (Build.VERSION.SDK_INT >= 33 || tag == null) {
            context
        } else {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }
            context.createConfigurationContext(configuration)
        }
        return if (formatArgs.isEmpty()) localized.getString(id) else localized.getString(id, *formatArgs)
    }

    private const val LEGACY_CONFIG_PREFERENCES = "ha-paneld"
    private const val UI_LANGUAGE_KEY = "ui_language"
    private const val AUTO_LANGUAGE = "auto"
}

/** Services do not inherit AppCompat's activity locale override before Android 13. */
internal fun Context.nativeString(@StringRes id: Int, vararg formatArgs: Any): String =
    NativeLocale.string(this, id, *formatArgs)

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
