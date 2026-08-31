package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.i18n.AppLocale
import io.github.maxlyth.hapaneld.i18n.CatalogueLoader
import io.github.maxlyth.hapaneld.i18n.Strings
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal data class LocalizedConfigSchema(
    val json: String,
    val languages: Collection<String>,
)

/** Production locale negotiation and catalogue selection for the dynamic Configure schema. */
internal fun localizedConfigSchema(
    call: ApplicationCall,
    persistedLanguage: String?,
    deviceLanguageTag: String?,
    allowPseudo: Boolean,
    catalogueLoader: CatalogueLoader,
    render: (Strings) -> String,
): LocalizedConfigSchema {
    val locale = AppLocale.resolve(
        explicit = call.request.queryParameters["lang"],
        persisted = persistedLanguage,
        haUser = call.request.queryParameters["ha_lang"],
        acceptLanguage = call.request.headers[HttpHeaders.AcceptLanguage],
        deviceLanguageTag = deviceLanguageTag,
        allowPseudo = allowPseudo,
    )
    val strings = catalogueLoader.strings(locale)
    return LocalizedConfigSchema(render(strings), strings.languages)
}

/** Dynamic Configure reads. Neither response may be reused after settings or locale signals change. */
internal fun Route.configReadRoutes(
    currentConfigJson: () -> String,
    localizedSchema: (ApplicationCall) -> LocalizedConfigSchema,
) {
    get("/config") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondText(currentConfigJson(), ContentType.Application.Json)
    }
    get("/config/schema") {
        val schema = localizedSchema(call)
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        call.response.headers.append(
            HttpHeaders.ContentLanguage,
            schema.languages.joinToString(", "),
        )
        call.respondText(schema.json, ContentType.Application.Json)
    }
}
