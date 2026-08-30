package io.github.maxlyth.hapaneld.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLocaleTest {
    @Test fun `explicit locale wins and regional tags use RFC lookup`() {
        assertEquals(
            "fr",
            AppLocale.resolve(
                explicit = "fr-CA", persisted = "it", haUser = "es",
                acceptLanguage = "de-DE", deviceLanguageTag = "it-IT", allowPseudo = false,
            ),
        )
        assertEquals("zh-Hans", AppLocale.resolve("zh-CN", acceptLanguage = null, deviceLanguageTag = null, allowPseudo = false))
        assertEquals("zh-Hans", AppLocale.resolve(null, acceptLanguage = "zh", deviceLanguageTag = null, allowPseudo = false))
    }

    @Test fun `panel setting and HA user precede inherited browser and device signals`() {
        assertEquals(
            "it",
            AppLocale.resolve(
                explicit = null, persisted = "it", haUser = "es",
                acceptLanguage = "de", deviceLanguageTag = "fr", allowPseudo = false,
            ),
        )
        assertEquals(
            "es",
            AppLocale.resolve(
                explicit = null, persisted = "auto", haUser = "es-MX",
                acceptLanguage = "de", deviceLanguageTag = "fr", allowPseudo = false,
            ),
        )
        assertEquals(
            "de",
            AppLocale.resolve(
                explicit = null, persisted = "unsupported", haUser = "unsupported",
                acceptLanguage = "de", deviceLanguageTag = "fr", allowPseudo = false,
            ),
        )
    }

    @Test fun `accept language honors quality and falls through unsupported languages`() {
        assertEquals(
            "de",
            AppLocale.resolve(null, acceptLanguage = "nl-NL, de-DE;q=0.8, fr;q=0.7", deviceLanguageTag = "it-IT", allowPseudo = false),
        )
        assertEquals("es", AppLocale.resolve(null, acceptLanguage = "ar;q=1, es-MX;q=.9", deviceLanguageTag = "de", allowPseudo = false))
    }

    @Test fun `device locale and English are final fallbacks`() {
        assertEquals("it", AppLocale.resolve(null, acceptLanguage = null, deviceLanguageTag = "it-CH", allowPseudo = false))
        assertEquals("en", AppLocale.resolve(null, acceptLanguage = "ar", deviceLanguageTag = "ja-JP", allowPseudo = false))
    }

    @Test fun `pseudolocale requires an explicit debug admission`() {
        assertEquals("en-XA", AppLocale.resolve("en-XA", acceptLanguage = "de", deviceLanguageTag = "fr", allowPseudo = true))
        assertEquals("en", AppLocale.resolve("en-XA", acceptLanguage = "de", deviceLanguageTag = "fr", allowPseudo = false))
        assertEquals("en", AppLocale.resolve(null, acceptLanguage = "en-XA, de;q=.9", deviceLanguageTag = "fr", allowPseudo = true))
    }

    @Test fun `malformed and unbounded language input is ignored`() {
        assertEquals("fr", AppLocale.resolve("de\nInjected", acceptLanguage = null, deviceLanguageTag = "fr", allowPseudo = true))
        assertEquals("it", AppLocale.resolve(null, acceptLanguage = "x".repeat(1_025), deviceLanguageTag = "it", allowPseudo = false))
        assertEquals("fr", AppLocale.resolve(null, acceptLanguage = "de;q=broken, fr;q=.8", deviceLanguageTag = "it", allowPseudo = false))
        assertEquals("fr", AppLocale.resolve(null, acceptLanguage = "de;q=.9;q=.8, fr;q=.7", deviceLanguageTag = "it", allowPseudo = false))
        assertEquals("fr", AppLocale.resolve(null, acceptLanguage = "de;q=NaN, fr;q=.8", deviceLanguageTag = "it", allowPseudo = false))
        assertEquals("fr", AppLocale.resolve(null, acceptLanguage = "de;q=Infinity, fr;q=.8", deviceLanguageTag = "it", allowPseudo = false))
    }
}
