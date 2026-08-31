package io.github.maxlyth.hapaneld.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigReadRoutesTest {
    @Test fun `dynamic config reads are never cached and only localized schema varies by language`() = testApplication {
        var configRevision = 0
        application {
            routing {
                route("/api/v1") {
                    configReadRoutes(
                        currentConfigJson = { "{\"revision\":${++configRevision}}" },
                        localizedSchema = { call ->
                            val language = call.request.headers[HttpHeaders.AcceptLanguage] ?: "en"
                            LocalizedConfigSchema("{\"language\":\"$language\"}", listOf(language))
                        },
                    )
                }
            }
        }

        val firstConfig = client.get("/api/v1/config")
        val secondConfig = client.get("/api/v1/config")
        assertEquals("no-store", firstConfig.headers[HttpHeaders.CacheControl])
        assertNull(firstConfig.headers[HttpHeaders.Vary])
        assertEquals("{\"revision\":1}", firstConfig.bodyAsText())
        assertEquals("{\"revision\":2}", secondConfig.bodyAsText())

        val schema = client.get("/api/v1/config/schema") {
            header(HttpHeaders.AcceptLanguage, "de")
        }
        assertEquals("no-store", schema.headers[HttpHeaders.CacheControl])
        assertEquals(HttpHeaders.AcceptLanguage, schema.headers[HttpHeaders.Vary])
        assertEquals("de", schema.headers[HttpHeaders.ContentLanguage])
        assertEquals("{\"language\":\"de\"}", schema.bodyAsText())
    }
}
