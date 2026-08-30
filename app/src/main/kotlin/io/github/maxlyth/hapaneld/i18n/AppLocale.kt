package io.github.maxlyth.hapaneld.i18n

import java.util.Locale

/** Locale negotiation for the first translated product surface. */
object AppLocale {
    const val ENGLISH = "en"
    const val PSEUDO = "en-XA"

    /** Release locales admitted for the Tier-A bootstrap. English is always the final fallback. */
    val RELEASE_LOCALES: Set<String> = linkedSetOf(ENGLISH, "de", "fr", "it", "es", "zh-Hans")

    /**
     * Resolve every admitted signal in the configured precedence order. Query/browser choice wins,
     * followed by a non-automatic panel setting, the connected Home Assistant user's language, the
     * browser language list, the Android process locale, and finally English. The debug pseudolocale
     * is deliberately opt-in and can never be selected through an inherited signal.
     */
    fun resolve(
        explicit: String?,
        persisted: String? = null,
        haUser: String? = null,
        acceptLanguage: String?,
        deviceLanguageTag: String?,
        allowPseudo: Boolean,
    ): String {
        canonical(explicit, allowPseudo = allowPseudo)?.let { return it }
        persisted?.takeUnless { it.equals("auto", ignoreCase = true) }
            ?.let { canonical(it, allowPseudo = false) }
            ?.let { return it }
        canonical(haUser, allowPseudo = false)?.let { return it }
        parseAcceptLanguage(acceptLanguage).forEach { requested ->
            canonical(requested, allowPseudo = false)?.let { return it }
        }
        canonical(deviceLanguageTag, allowPseudo = false)?.let { return it }
        return ENGLISH
    }

    /** RFC-4647-style lookup over the locales currently implemented by the product. */
    fun canonical(raw: String?, allowPseudo: Boolean = false): String? {
        val tag = raw?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return null
        if (tag.length > 63 || !tag.matches(Regex("[A-Za-z0-9]{1,8}(?:-[A-Za-z0-9]{1,8})*"))) return null
        if (allowPseudo && tag.equals(PSEUDO, ignoreCase = true)) return PSEUDO
        val lower = tag.lowercase(Locale.ROOT)
        return when {
            lower == "en" || lower.startsWith("en-") -> ENGLISH
            lower == "de" || lower.startsWith("de-") -> "de"
            lower == "fr" || lower.startsWith("fr-") -> "fr"
            lower == "it" || lower.startsWith("it-") -> "it"
            lower == "es" || lower.startsWith("es-") -> "es"
            lower == "zh" || lower == "zh-hans" || lower.startsWith("zh-hans-") ||
                lower == "zh-cn" || lower.startsWith("zh-cn-") ||
                lower == "zh-sg" || lower.startsWith("zh-sg-") -> "zh-Hans"
            else -> null
        }
    }

    internal fun parseAcceptLanguage(header: String?): List<String> = header.orEmpty()
        .takeIf { it.length <= 1_024 }
        .orEmpty()
        .split(',')
        .take(16)
        .mapIndexedNotNull { index, part ->
            val pieces = part.trim().split(';')
            val tag = pieces.firstOrNull()?.trim().orEmpty()
            if (tag.isEmpty() || tag == "*") return@mapIndexedNotNull null
            val qualities = pieces.drop(1).mapNotNull { parameter ->
                val pair = parameter.trim().split('=', limit = 2)
                if (pair.size == 2 && pair[0].equals("q", ignoreCase = true)) {
                    pair[1]
                } else null
            }
            if (qualities.size > 1) return@mapIndexedNotNull null
            val quality = qualities.singleOrNull()?.toDoubleOrNull()
                ?: if (qualities.isEmpty()) 1.0 else return@mapIndexedNotNull null
            if (quality <= 0.0 || quality > 1.0) null else AcceptLanguage(tag, quality, index)
        }
        .sortedWith(compareByDescending<AcceptLanguage> { it.quality }.thenBy { it.index })
        .map { it.tag }

    private data class AcceptLanguage(val tag: String, val quality: Double, val index: Int)
}
