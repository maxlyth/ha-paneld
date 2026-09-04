package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.testsupport.TestSources
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLocalizationContractTest {
    private val productionRoot = File("src/main")
    private val productionKotlin = File(productionRoot, "kotlin").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.relativeTo(productionRoot).invariantSeparatorsPath to it.readText() }

    private fun kotlin(path: String): String = productionKotlin.getValue("kotlin/io/github/maxlyth/hapaneld/$path")

    private fun baseStrings(): Map<String, Boolean> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length).associate { index ->
            val attributes = strings.item(index).attributes
            attributes.getNamedItem("name").nodeValue to
                (attributes.getNamedItem("translatable")?.nodeValue != "false")
        }
    }

    @Test fun baseCatalogueHasAUniqueBoundedKeyset() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
        val catalogue = baseStrings()
        assertEquals(254, document.getElementsByTagName("string").length)
        assertEquals(254, catalogue.size)
        assertEquals(251, catalogue.count { it.value })
        assertEquals(
            setOf("app_name", "home_assistant", "wordmark_description"),
            catalogue.filterValues { !it }.keys,
        )
    }

    @Test fun componentLabelsAndAccessibilityServiceUseResources() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        listOf(
            "@string/config_activity_label",
            "@string/guard_db_activity_label",
            "@string/dashboard_activity_label",
            "@string/admin_launcher_activity_label",
        ).forEach { assertTrue("missing manifest resource $it", manifest.contains(it)) }
        val accessibility = File("src/main/res/xml/accessibility_config.xml").readText()
        assertTrue(accessibility.contains("android:description=\"@string/a11y_description\""))
    }

    @Test fun nativeUiSinksDoNotEmbedEnglishLiterals() {
        val sink = Regex(
            "(?:\\b(?:text|contentDescription|navigationContentDescription|subtitle)\\s*=|" +
                "\\.(?:setText|setTitle|setMessage|setPositiveButton|setNegativeButton|setNeutralButton|" +
                "setContentTitle|setContentText)\\s*\\(|" +
                "\\b(?:menu\\.add|surface\\.(?:heading|detail|caption|action)|text|button|Tile|" +
                "updateForegroundStatus|startForegroundCompat|announceDeliberateRestart|" +
                "showBlockedAdmissionScreen)\\s*\\()\\s*\"([^\"\\n]*)\"",
        )
        val deliberateNonLanguageLiterals = setOf(
            "ha-paneld",
            "v\${BuildConfig.VERSION_NAME}",
            "\${(brightness.getCommanded().coerceAtLeast(0) * 100 + 127) / 255}%",
            "\${volume.getPercent()}%",
        )
        productionKotlin.forEach { (name, source) ->
            val violations = sink.findAll(source)
                .map { it.groupValues[1] }
                .filter { literal -> literal.any(Char::isLetter) && literal !in deliberateNonLanguageLiterals }
                .toList()
            assertEquals("hardcoded native UI sink in $name", emptyList<String>(), violations)
        }
    }

    @Test fun everyAppStringReferenceResolvesAndEveryFrozenKeyIsAccountedFor() {
        val catalogue = baseStrings()
        val kotlinReferences = productionKotlin.values.flatMap { source ->
            Regex("(?<!android\\.)R\\.string\\.([A-Za-z0-9_]+)").findAll(source).map { it.groupValues[1] }.toList()
        }.toSet()
        val xmlReferences = productionRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "xml" || it.name == "AndroidManifest.xml") }
            .flatMap { file ->
                Regex("@string/([A-Za-z0-9_]+)").findAll(file.readText()).map { it.groupValues[1] }
            }
            .toSet()
        val references = kotlinReferences + xmlReferences

        assertEquals("missing base resources", emptySet<String>(), references - catalogue.keys)
        // Kept in the frozen catalogue for the bounded no-remote-address variant. The current runtime
        // always has an Entities URL and therefore renders entity_filter_attention_remote instead.
        assertEquals(
            "unaccounted frozen resources",
            setOf("entity_filter_attention_detail"),
            catalogue.keys - references,
        )
    }

    @Test fun nativeLocaleFollowsThePersistedUiLanguage() {
        val application = TestSources.kotlin("HaPaneldApp.kt").readText()
        val locale = TestSources.kotlin("NativeLocale.kt").readText()
        assertTrue(application.contains("NativeLocale.apply(Config(this).uiLanguage)"))
        assertTrue(locale.contains("AppCompatDelegate.setApplicationLocales(desired)"))
        assertTrue(kotlin("ConfigActivity.kt").contains("NativeLocale.apply(Config(this@ConfigActivity).uiLanguage)"))
        assertTrue(kotlin("DashboardActivity.kt").contains("NativeLocale.apply(Config(this).uiLanguage)"))
        assertTrue(kotlin("AdminLauncherActivity.kt").contains("NativeLocale.apply(Config(this).uiLanguage)"))
        assertTrue(kotlin("MainActivity.kt").contains("NativeLocale.apply(config.uiLanguage)"))
    }

    @Test fun everyNavbarControlHasALocalizedDescription() {
        val navbar = kotlin("control/NavbarController.kt")
        assertEquals(5, Regex("navButton\\(R\\.drawable\\.[^,]+, R\\.string\\.nav_").findAll(navbar).count())
        assertEquals(4, Regex("repeatButton\\(R\\.drawable\\.[^,]+, R\\.string\\.nav_").findAll(navbar).count())
        assertEquals(2, Regex("sliderButton\\(R\\.drawable\\.[^,]+, R\\.string\\.nav_").findAll(navbar).count())
        assertTrue(navbar.contains("contentDescription = context.getString(description)"))
    }

    @Test fun everyNotificationSurfaceResolvesUserCopyFromResources() {
        val service = kotlin("PaneldService.kt")
        val camera = kotlin("camera/CameraForegroundService.kt")
        val recovery = kotlin("GuardDbMaintenanceService.kt")
        assertTrue(service.contains("localizedStorageHealthNotification(snapshot)"))
        assertTrue(service.contains("getString(R.string.panel_agent_channel_description)"))
        assertTrue(camera.contains("getString(R.string.camera_in_use)"))
        assertTrue(recovery.contains("getString(R.string.database_recovery_notification)"))
    }

    @Test fun everyFiniteDashboardRestartReasonHasANativeLocalization() {
        val finiteReasons = productionKotlin.values.flatMap { source ->
            Regex("system\\.reloadDashboard\\([\\s\\S]{0,300}?reason\\s*=\\s*\"([^\"]+)\"")
                .findAll(source)
                .map { it.groupValues[1] }
                .toList()
        }.toSet()
        assertEquals(
            setOf(
                "applying the entity filter",
                "applying your settings",
                "clearing the dashboard’s stored data",
                "updating the entity filter",
            ),
            finiteReasons,
        )
        val dashboard = kotlin("DashboardActivity.kt")
        finiteReasons.forEach { reason ->
            assertTrue("restart reason is not localized: $reason", dashboard.contains("\"$reason\""))
        }
    }
}
