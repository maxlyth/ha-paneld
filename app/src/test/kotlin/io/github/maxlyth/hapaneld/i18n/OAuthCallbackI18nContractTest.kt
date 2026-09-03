package io.github.maxlyth.hapaneld.i18n

import io.github.maxlyth.hapaneld.http.HaOAuthCallbackCopy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contracts the small HTML callback surface reached from localized Setup and Configure OAuth. */
class OAuthCallbackI18nContractTest {
    private val project = File(".")
    private val assets = File(project, "src/main/assets")
    private val source = SourceCatalogue.parse(File(assets, "i18n/en.json").readText())
    private val server = File(project, "src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
    private val routes = File(project, "src/main/kotlin/io/github/maxlyth/hapaneld/http/HaOAuthRoutes.kt").readText()

    @Test fun `OAuth callback copy exactly consumes its catalogue namespace`() {
        val sourceKeys = source.strings.keys.filterTo(sortedSetOf()) { it.startsWith("oauth.callback.") }
        val start = server.indexOf("private fun haOAuthCallbackCopy(")
        val end = server.indexOf("\n    private suspend fun completeHaOAuth(", start)
        val consumer = server.substring(start, end)
        val consumed = Regex("strings[.]get[(]\"(oauth[.]callback[.][a-z0-9_.-]+)\"[)]")
            .findAll(consumer)
            .mapTo(sortedSetOf()) { it.groupValues[1] }

        assertEquals(14, sourceKeys.size)
        assertEquals(sourceKeys, consumed)
        assertTrue(routes.contains("context.contentLanguages.sorted().joinToString(\", \")"))
        assertTrue(routes.contains("<html lang=\"${'$'}{escapeHaOAuthHtml(context.locale)}\""))
        assertFalse(routes.contains("<html lang=\"en\"><head"))
    }

    @Test fun `Fail-closed English callback copy stays identical to the source catalogue`() {
        val copy = HaOAuthCallbackCopy.ENGLISH
        val fallback = mapOf(
            "oauth.callback.success_heading" to copy.successHeading,
            "oauth.callback.failure_heading" to copy.failureHeading,
            "oauth.callback.action.continue" to copy.continueAction,
            "oauth.callback.action.back_to_configure" to copy.backToConfigureAction,
            "oauth.callback.action.back_to_setup" to copy.backToSetupAction,
            "oauth.callback.cancelled" to copy.cancelled,
            "oauth.callback.invalid_code" to copy.invalidCode,
            "oauth.callback.rejected" to copy.rejected,
            "oauth.callback.transient" to copy.transient,
            "oauth.callback.stale" to copy.stale,
            "oauth.callback.commit_failed" to copy.commitFailed,
            "oauth.callback.configured" to copy.configured,
            "oauth.callback.reload_may_be_needed" to copy.reloadMayBeNeeded,
            "oauth.callback.ambient_warning" to copy.ambientWarning,
        )
        val authoritative = source.strings
            .filterKeys { it.startsWith("oauth.callback.") }
            .mapValues { it.value.text }

        assertEquals(authoritative, fallback)
    }

    @Test fun `OAuth callback catalogue is current and promoted in every release locale`() {
        val keys = source.strings.keys.filter { it.startsWith("oauth.callback.") }
        AppLocale.RELEASE_LOCALES.filterNot { it == AppLocale.ENGLISH }.forEach { locale ->
            val target = TargetCatalogue.parse(File(assets, "i18n/$locale.json").readText(), source)
            keys.forEach { key ->
                val english = checkNotNull(source.strings[key])
                val translated = checkNotNull(target.strings[key]) { "$locale is missing $key" }
                assertEquals("$locale has stale source text for $key", english.sourceHash, translated.sourceHash)
                assertTrue(
                    "$locale must ship localized callback copy for $key",
                    translated.state == TranslationState.MACHINE_CROSS_CHECKED ||
                        translated.state == TranslationState.COMMUNITY_CORRECTED,
                )
            }
        }
    }

    @Test fun `Localized callers bind a closed OAuth return context`() {
        val setup = File(assets, "setup.js").readText()
        val configure = File(assets, "configure.js").readText()
        assertTrue(setup.contains("ui_locale: requestedLocale()"))
        assertTrue(setup.contains("return_surface: \"setup\""))
        assertTrue(configure.contains("ui_locale: oauthLocale"))
        assertTrue(configure.contains("return_surface: \"configure\""))
        assertTrue(setup.contains("preserve_explicit_english:"))
        assertTrue(configure.contains("preserve_explicit_english:"))
        assertTrue(routes.contains("HaOAuthReturnSurface.CONFIGURE"))
        assertTrue(routes.contains("HaOAuthReturnSurface.SETUP"))
        assertFalse("OAuth callers must never submit a raw return URL", setup.contains("return_url"))
        assertFalse("OAuth callers must never submit a raw return URL", configure.contains("return_url"))
    }
}
