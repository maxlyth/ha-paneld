package io.github.maxlyth.hapaneld.http

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.OutgoingContent
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigPostBodyTest {
    @Test fun `legacy and versioned config posts reject declared and chunked overflow before parsing`() =
        testApplication {
            application { routing { installConfigReader("/config"); installConfigReader("/api/v1/config") } }
            val oversized = "not-json" + "x".repeat(TEST_LIMIT.toInt())

            listOf("/config", "/api/v1/config").forEach { path ->
                val declared = client.post(path) { setBody(oversized) }
                assertEquals(path, HttpStatusCode.PayloadTooLarge, declared.status)
                assertEquals("request too large\n", declared.bodyAsText())

                val chunked = client.post(path) { setBody(chunked(oversized.toByteArray())) }
                assertEquals(path, HttpStatusCode.PayloadTooLarge, chunked.status)
                assertEquals("request too large\n", chunked.bodyAsText())
            }
        }

    @Test fun `bounded config reader preserves form and scalar JSON values`() = testApplication {
        application { routing { installConfigReader("/config") } }

        val form = client.post("/config") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("friendly_name=Example+Panel&dashboard_zoom=125")
        }
        assertEquals(HttpStatusCode.OK, form.status)
        assertEquals("Example Panel|125", form.bodyAsText())

        val json = client.post("/config") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"friendly_name":"Example Panel","dashboard_zoom":125}""")
        }
        assertEquals(HttpStatusCode.OK, json.status)
        assertEquals("Example Panel|125", json.bodyAsText())
    }

    @Test fun `invalid or structured JSON is rejected without reaching config mutation`() = testApplication {
        application { routing { installConfigReader("/config") } }

        listOf("not-json", """{"friendly_name":{"nested":"value"}}""").forEach { body ->
            val response = client.post("/config") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid config body\n", response.bodyAsText())
        }
    }

    @Test fun `both production config routes enter the bounded reader before mutation`() {
        val source = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        ).first { it.isFile }.readText()
        assertTrue(source.contains("post(\"/config\") { handleConfigPost(call) }"))
        assertTrue(source.contains("private suspend fun handleConfigPost(call: ApplicationCall)"))
        val handler = source.substring(
            source.indexOf("private suspend fun handleConfigPost(call: ApplicationCall)"),
            source.indexOf("private fun configSchemaJson()"),
        )
        val receive = handler.indexOf("receiveBoundedConfigParameters(call) ?: return")
        val validation = handler.indexOf("normalizeConfigPostParameters(received)")
        assertFalse(handler.contains("call.receiveParameters()"))
        val mutation = handler.indexOf("config.applyBatch")
        assertTrue(receive >= 0)
        assertTrue(validation > receive)
        assertTrue(mutation > validation)
    }

    @Test fun `direct config admission normalizes every registered value before mutation`() {
        val result = normalizeConfigPostParameters(Parameters.build {
            append("friendly_name", "  Example Panel  ")
            append("dashboard_zoom", "125")
            append("dashboard_fullscreen", "on")
            append("ha_expose_wake_on_wave", "0")
            append("tame_vendor_packages", "com.vendor.one, com.vendor.two com.vendor.one")
            append("ha_token_expiry", "42")
            append("mqtt_password", "  exact password  ")
        }) as ConfigPostParameters.Ok
        assertEquals("Example Panel", result.values["friendly_name"])
        assertEquals("true", result.values["dashboard_fullscreen"])
        assertEquals("false", result.values["ha_expose_wake_on_wave"])
        assertEquals("com.vendor.one com.vendor.two", result.values["tame_vendor_packages"])
        assertEquals("42", result.values["ha_token_expiry"])
        assertEquals("  exact password  ", result.values["mqtt_password"])
    }

    @Test fun `direct config admission rejects invalid or amplifying values atomically`() {
        listOf(
            Parameters.build { append("friendly_name", "x".repeat(129)) },
            Parameters.build { append("dashboard_zoom", "301") },
            Parameters.build { append("dashboard_fullscreen", "maybe") },
            Parameters.build { append("ha_expose_missing", "true") },
            Parameters.build { append("tame_vendor_packages", "com.good;reboot") },
            Parameters.build { append("ha_token_expiry", "-1") },
            Parameters.build { append("typo_setting", "true") },
            Parameters.build { append("friendly_name", "one"); append("friendly_name", "two") },
        ).forEach { parameters ->
            assertTrue(normalizeConfigPostParameters(parameters) is ConfigPostParameters.Bad)
        }
    }

    private fun io.ktor.server.routing.Route.installConfigReader(path: String) {
        post(path) {
            val parameters = receiveBoundedConfigParameters(call, TEST_LIMIT) ?: return@post
            call.respondText("${parameters["friendly_name"].orEmpty()}|${parameters["dashboard_zoom"].orEmpty()}")
        }
    }

    private fun chunked(bytes: ByteArray) = object : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType = ContentType.Application.Json
        override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(bytes) }
    }

    private companion object { const val TEST_LIMIT = 1_024L }
}
