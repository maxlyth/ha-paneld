package io.github.maxlyth.hapaneld.http

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.maxlyth.hapaneld.CoreInstrumentation
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO as ClientCio
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.server.cio.CIO as ServerCio
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Proves the production Ktor engine and client can load and exchange bytes on the fleet API floor. */
@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class KtorCioRuntimeTest {
    @Test fun cioServerAndClientRunOnAndroid() = runBlocking {
        val server = embeddedServer(ServerCio, host = "::", port = 0) {
            routing {
                get("/health") { call.respondText("ok") }
            }
        }
        val client = HttpClient(ClientCio) {
            install(WebSockets)
        }
        try {
            server.start(wait = false)
            val port = withTimeout(10_000L) { server.engine.resolvedConnectors().single().port }
            val body = withTimeout(10_000L) {
                client.get("http://[::1]:$port/health").body<String>()
            }
            assertEquals("ok", body)
        } finally {
            client.close()
            server.stop(gracePeriodMillis = 1_000L, timeoutMillis = 5_000L)
        }
    }
}
