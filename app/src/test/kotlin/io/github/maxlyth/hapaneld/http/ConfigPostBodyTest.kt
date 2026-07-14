package io.github.maxlyth.hapaneld.http

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
            setBody("friendly_name=Kitchen+Panel&dashboard_zoom=125")
        }
        assertEquals(HttpStatusCode.OK, form.status)
        assertEquals("Kitchen Panel|125", form.bodyAsText())

        val json = client.post("/config") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"friendly_name":"Kitchen Panel","dashboard_zoom":125}""")
        }
        assertEquals(HttpStatusCode.OK, json.status)
        assertEquals("Kitchen Panel|125", json.bodyAsText())
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
        assertTrue(handler.contains("val p = receiveBoundedConfigParameters(call) ?: return"))
        assertFalse(handler.contains("call.receiveParameters()"))
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
