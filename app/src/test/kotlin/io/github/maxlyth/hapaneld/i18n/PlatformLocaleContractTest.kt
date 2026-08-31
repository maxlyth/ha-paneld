package io.github.maxlyth.hapaneld.i18n

import io.github.maxlyth.hapaneld.testsupport.TestSources
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformLocaleContractTest {
    @Test fun `platform locale declaration matches the JSON resolver boundary`() {
        val config = document("src/main/res/xml/locales_config.xml")
        val declared = config.getElementsByTagName("locale").let { locales ->
            (0 until locales.length).map { index ->
                locales.item(index).attributes.getNamedItemNS(ANDROID_NS, "name").nodeValue
            }
        }

        assertEquals(AppLocale.RELEASE_LOCALES.toList(), declared)
        assertEquals(declared.size, declared.toSet().size)

        val catalogues = TestSources.appDir("src/main/assets/i18n")
            .listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .map { it.nameWithoutExtension }
            .sorted()
        assertEquals(AppLocale.RELEASE_LOCALES.sorted(), catalogues)
    }

    @Test fun `manifest advertises the finite locale declaration`() {
        val manifest = document("src/main/AndroidManifest.xml")
        val applications = manifest.getElementsByTagName("application")

        assertEquals(1, applications.length)
        assertEquals(
            "@xml/locales_config",
            applications.item(0).attributes.getNamedItemNS(ANDROID_NS, "localeConfig")?.nodeValue,
        )

        val merged = document("build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml")
        val mergedApplications = merged.getElementsByTagName("application")
        assertEquals(1, mergedApplications.length)
        assertEquals(
            "@xml/locales_config",
            mergedApplications.item(0).attributes.getNamedItemNS(ANDROID_NS, "localeConfig")?.nodeValue,
        )
    }

    @Test fun `build filters resources to release locales and enables debug pseudolocales`() {
        val build = TestSources.appFile("build.gradle.kts").readText()
        val filters = Regex("""val releaseLocaleFilters = listOf\(([^)]*)\)""")
            .find(build)
            ?.groupValues
            ?.get(1)
            ?.let { body -> Regex(""""([^"\\]+)"""").findAll(body).map { it.groupValues[1] }.toList() }

        assertEquals(AppLocale.RELEASE_LOCALES.map(::androidResourceQualifier), filters)
        assertTrue(build.contains("localeFilters += releaseLocaleFilters"))
        assertTrue(build.contains("isPseudoLocalesEnabled = true"))
    }

    private fun document(path: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(TestSources.appFile(path))

    private fun androidResourceQualifier(locale: String): String = when (locale) {
        "zh-Hans" -> "b+zh+Hans"
        else -> locale
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
