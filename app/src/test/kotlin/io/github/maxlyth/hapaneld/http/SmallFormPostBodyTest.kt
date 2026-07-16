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

class SmallFormPostBodyTest {
    @Test fun `small form reader rejects declared and chunked total-body overflow`() = testApplication {
        application {
            routing {
                post("/form") {
                    val parameters = receiveBoundedFormParameters(call, TEST_LIMIT) ?: return@post
                    call.respondText(parameters["value"].orEmpty())
                }
            }
        }
        val oversized = "value=" + "x".repeat(TEST_LIMIT.toInt())

        val declared = client.post("/form") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody(oversized)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, declared.status)
        assertEquals("request too large\n", declared.bodyAsText())

        val chunked = client.post("/form") { setBody(chunkedForm(oversized.toByteArray())) }
        assertEquals(HttpStatusCode.PayloadTooLarge, chunked.status)
        assertEquals("request too large\n", chunked.bodyAsText())
    }

    @Test fun `small form reader preserves percent and plus decoding`() = testApplication {
        application {
            routing {
                post("/form") {
                    val parameters = receiveBoundedFormParameters(call, TEST_LIMIT) ?: return@post
                    call.respondText("${parameters["value"]}|${parameters["mode"]}")
                }
            }
        }
        val response = client.post("/form") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("value=Sample+Panel&mode=one%2Ftwo")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Sample Panel|one/two", response.bodyAsText())
    }

    @Test fun `production HTTP control routes do not use the Ktor 50 MiB form default`() {
        val sources = listOf("ControlPlaneRoutes.kt", "PaneldServer.kt").associateWith { name ->
            listOf(
                File("src/main/kotlin/io/github/maxlyth/hapaneld/http/$name"),
                File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/$name"),
            ).first { it.isFile }.readText()
        }
        sources.forEach { (name, source) ->
            assertFalse("$name still uses unbounded receiveParameters", source.contains("receiveParameters()"))
        }
        assertTrue(sources.getValue("PaneldServer.kt").contains("MAX_SMALL_FORM_POST_BODY_BYTES = 16L * 1024L"))
        assertTrue(sources.getValue("ControlPlaneRoutes.kt").contains("receiveBoundedFormParameters(call)"))
    }

    @Test fun `all materialized control bodies use the shared total receipt deadline`() {
        fun source(name: String) = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/http/$name"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/$name"),
        ).first { it.isFile }.readText()
        val server = source("PaneldServer.kt")
        val profiles = source("ProfileRoutes.kt")
        val control = source("ControlPlaneRoutes.kt")

        assertTrue(server.contains("receiveBoundedBody(call, maxBytes)"))
        assertTrue(server.contains("receiveBoundedBody(call, MAX_ENTITY_ADMIN_BODY_BYTES)"))
        assertTrue(server.contains("receiveBoundedBody(call, EntityFilterProtocol.MAX_API_BODY_BYTES.toLong())"))
        assertTrue(server.contains("receiveBoundedBody(call, MAX_CONFIG_IMPORT_BYTES)"))
        assertTrue(profiles.contains("receiveBoundedBody(this, maxBytes)"))
        assertTrue(control.contains("receiveBoundedBody(call, PaneldServer.MAX_PLAY_BODY_BYTES)"))
        assertFalse(profiles.contains("receiveStream()"))
    }

    private fun chunkedForm(bytes: ByteArray) = object : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType = ContentType.Application.FormUrlEncoded
        override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(bytes) }
    }

    private companion object { const val TEST_LIMIT = 1_024L }
}
