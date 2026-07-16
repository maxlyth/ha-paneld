package io.github.maxlyth.hapaneld.http

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.github.maxlyth.hapaneld.dashboard.EntityFilterProtocol
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException

/** Composed coverage for the exact private body reader plus a source contract proving all six live
 * routes use it. PaneldServer itself is Android-bound, so the instance is allocation-only: the helper
 * under test reads no fields and runs inside a real Ktor test ApplicationCall. */
class PaneldServerEntityAdminRoutesTest {
    private val routePolicies = linkedMapOf(
        "/activate" to true,
        "/policy" to false,
        "/override" to false,
        "/overrides" to false,
        "/issues" to false,
        "/reset" to true,
    )

    @Test fun `all six routes reject declared and chunked overflow before JSON parsing`() = testApplication {
        application { routing { installEntityReaderRoutes() } }
        val oversizedInvalid = "not-json" + "x".repeat(PaneldServer.MAX_ENTITY_ADMIN_BODY_BYTES.toInt())

        routePolicies.keys.forEach { path ->
            val declared = client.post(path) { setBody(oversizedInvalid) }
            assertEquals(path, HttpStatusCode.PayloadTooLarge, declared.status)
            assertEquals("request too large\n", declared.bodyAsText())

            val chunked = client.post(path) { setBody(chunked(oversizedInvalid.toByteArray())) }
            assertEquals(path, HttpStatusCode.PayloadTooLarge, chunked.status)
            assertEquals("request too large\n", chunked.bodyAsText())
        }
    }

    @Test fun `valid invalid and route-specific blank JSON semantics are preserved`() = testApplication {
        application { routing { installEntityReaderRoutes() } }

        routePolicies.forEach { (path, allowBlank) ->
            val valid = client.post(path) { setBody("""{"confirm":true}""") }
            assertEquals(path, HttpStatusCode.OK, valid.status)
            assertTrue(path, JSONObject(valid.bodyAsText()).getBoolean("confirm"))

            val invalid = client.post(path) { setBody("not-json") }
            assertEquals(path, HttpStatusCode.BadRequest, invalid.status)
            assertEquals("invalid JSON\n", invalid.bodyAsText())

            val blank = client.post(path) { setBody("   \n") }
            assertEquals(path, if (allowBlank) HttpStatusCode.OK else HttpStatusCode.BadRequest, blank.status)
            if (allowBlank) assertEquals(0, JSONObject(blank.bodyAsText()).length())
            else assertEquals("invalid JSON\n", blank.bodyAsText())
        }
    }

    @Test fun `live route contract delegates all six endpoints to the bounded reader`() {
        val source = paneldServerSource()
        val contracts = linkedMapOf(
            "/dashboard/entities/activate" to "receiveEntityAdminJson(call, allowBlank = true)",
            "/dashboard/entities/policy" to "receiveEntityAdminJson(call)",
            "/dashboard/entities/override" to "receiveEntityAdminJson(call)",
            "/dashboard/entities/overrides" to "receiveEntityAdminJson(call)",
            "/dashboard/entities/issues" to "receiveEntityAdminJson(call)",
            "/dashboard/entities/reset" to "receiveEntityAdminJson(call, allowBlank = true)",
        )
        contracts.forEach { (route, reader) ->
            val start = source.indexOf("post(\"$route\")")
            assertTrue("missing $route", start >= 0)
            val window = source.substring(start, minOf(source.length, start + 650))
            assertTrue("$route bypasses bounded reader", window.contains(reader))
            assertFalse("$route directly materializes receiveText", window.contains("receiveText("))
        }
        val helper = source.substring(
            source.indexOf("private suspend fun receiveEntityAdminJson"),
            source.indexOf("private suspend fun handleEntityFilterPost"),
        )
        assertTrue(helper.indexOf("receiveBoundedBody") < helper.indexOf("JSONObject("))
        assertTrue(helper.contains("HttpStatusCode.PayloadTooLarge"))
        assertTrue(helper.contains("HttpStatusCode.RequestTimeout"))
    }

    @Test fun `filter status preserves enabled empty allow list semantics`() {
        val source = paneldServerSource()
        val start = source.indexOf("private fun entityFilterStatusJson")
        val end = source.indexOf("private suspend fun receiveEntityAdminJson", start)
        val status = source.substring(start, end)

        assertTrue(status.contains("val hash = EntityFilterProtocol.hash(ids)"))
        assertTrue(status.contains("\\\"enabled\\\":\${config.dashboardEntityFilterEnabled}"))
        assertFalse(status.contains("ids.isNotEmpty()"))
    }

    @Test fun `blank explicit manual list is rejected before atomic cutover`() {
        val update = EntityFilterProtocol.parseUpdate("""{"mode":"manual","entity_ids":[" "]}""")
        assertTrue(update.entityIds != null)
        assertTrue(update.entityIds!!.isEmpty())

        val source = paneldServerSource()
        val start = source.indexOf("private suspend fun handleEntityFilterPost")
        val end = source.indexOf("private suspend fun applyConfig", start).takeIf { it > start }
            ?: source.length
        val handler = source.substring(start, end)
        val reject = handler.indexOf("update.entityIds != null && ids.isEmpty()")
        val commit = handler.indexOf("config.commitDashboardManualEntityFilter(enabled, ids)")
        assertTrue("missing explicit-empty rejection", reject >= 0)
        assertTrue("missing atomic manual cutover", commit >= 0)
        assertTrue("explicit-empty rejection must precede mutation", reject < commit)
        assertTrue(handler.contains("entity_ids must contain at least one valid entity"))
    }

    @Test fun `reset route exposes explicit clean slate without changing default reset`() {
        val source = paneldServerSource()
        val start = source.indexOf("post(\"/dashboard/entities/reset\")")
        val route = source.substring(start, minOf(source.length, start + 900))

        assertTrue(route.contains("confirm = obj.optBoolean(\"confirm\", false)"))
        assertTrue(route.contains("clearFilter = obj.optBoolean(\"clear_filter\", false)"))

        val managerSource = entityLearningManagerSource()
        val resetStart = managerSource.indexOf("fun resetEvidence(confirm: Boolean, clearFilter: Boolean = false)")
        val reset = managerSource.substring(resetStart, minOf(managerSource.length, resetStart + 1_500))
        assertFalse(reset.contains("clear_filter requires automatic learning"))
        assertTrue(reset.contains("if (config.dashboardEntityLearningEnabled) syncNow(\"reset\") else false"))
    }

    private fun io.ktor.server.routing.Route.installEntityReaderRoutes() {
        routePolicies.forEach { (path, allowBlank) ->
            post(path) {
                val parsed = invokeEntityReader(call, allowBlank) ?: return@post
                call.respondText(parsed.toString(), ContentType.Application.Json)
            }
        }
    }

    private fun chunked(bytes: ByteArray) = object : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType = ContentType.Application.Json
        override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(bytes) }
    }

    private fun paneldServerSource(): String = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
    ).first { it.isFile }.readText()

    private fun entityLearningManagerSource(): String = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityLearningManager.kt"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityLearningManager.kt"),
    ).first { it.isFile }.readText()

    private suspend fun invokeEntityReader(call: ApplicationCall, allowBlank: Boolean): JSONObject? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val result = try {
                reader.invoke(server, call, allowBlank, continuation)
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
            if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else result as JSONObject?
        }

    private companion object {
        val server: PaneldServer = run {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            unsafeClass.getMethod("allocateInstance", Class::class.java)
                .invoke(field.get(null), PaneldServer::class.java) as PaneldServer
        }
        val reader = PaneldServer::class.java.declaredMethods.single { it.name == "receiveEntityAdminJson" }
            .apply { isAccessible = true }
    }
}
