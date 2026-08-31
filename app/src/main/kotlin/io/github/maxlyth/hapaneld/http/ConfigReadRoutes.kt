package io.github.maxlyth.hapaneld.http

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
