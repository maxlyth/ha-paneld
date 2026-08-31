package io.github.maxlyth.hapaneld.i18n

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxlyth.hapaneld.CoreInstrumentation
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises catalogue parsing on Android's regex engine rather than the desktop JVM implementation. */
@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class CatalogueAndroidTest {
    @Test fun bundledEnglishCatalogueParsesOnAndroid() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val loader = CatalogueLoader { path ->
            assets.open(path).bufferedReader().use { it.readText() }
        }

        assertTrue(loader.strings(AppLocale.ENGLISH).get("settings.auto_brightness.label").isNotBlank())
    }
}
