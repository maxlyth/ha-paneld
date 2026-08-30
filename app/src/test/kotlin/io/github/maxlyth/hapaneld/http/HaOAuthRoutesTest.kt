package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.HaAuthOwner
import io.github.maxlyth.hapaneld.HaOAuthAttemptAuthority
import io.github.maxlyth.hapaneld.util.HaLink
import io.github.maxlyth.hapaneld.sensors.HaCurrentUserStatus
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class HaOAuthRoutesTest {
    @Test fun `status exposes only the display name and language and is never cached`() = testApplication {
        val harness = Harness()
        application {
            routing {
                route("/api/v1") {
                    haOAuthRoutes(harness.dependencies().copy(
                        status = { HaCurrentUserStatus.Connected("Alice", "de-DE") },
                    ))
                }
            }
        }

        val response = client.get("/api/v1/ha/oauth/status")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        val json = JSONObject(response.bodyAsText())
        assertEquals("connected", json.getString("phase"))
        assertEquals("Alice", json.getString("display_name"))
        assertEquals("de-DE", json.getString("language"))
        assertEquals(setOf("phase", "display_name", "language"), json.keys().asSequence().toSet())
    }

    @Test fun `connected status keeps an exact nullable language key`() = testApplication {
        val harness = Harness()
        application {
            routing {
                route("/api/v1") {
                    haOAuthRoutes(harness.dependencies().copy(
                        status = { HaCurrentUserStatus.Connected("Alice", null) },
                    ))
                }
            }
        }

        val json = JSONObject(client.get("/api/v1/ha/oauth/status").bodyAsText())

        assertTrue(json.has("language"))
        assertTrue(json.isNull("language"))
        assertEquals(setOf("phase", "display_name", "language"), json.keys().asSequence().toSet())
    }

    @Test fun `start validates bounds and panel origin and returns a no-store explicit link`() = testApplication {
        val harness = Harness()
        application { routing { route("/api/v1") { haOAuthRoutes(harness.dependencies()) } } }

        val response = client.post("/api/v1/ha/oauth/start") {
            panelHost()
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("ha_url=https%3A%2F%2Fha.example")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertOAuthHeaders(response.headers[HttpHeaders.CacheControl], response.headers["Referrer-Policy"])
        assertTrue(JSONObject(response.bodyAsText()).getString("authorization_url").startsWith("https://ha.example/auth/authorize?"))
        assertEquals(1, harness.starts)

        val invalidUrl = client.post("/api/v1/ha/oauth/start") {
            panelHost()
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("ha_url=https%3A%2F%2Fha.example%2F%3Fsecret%3D1")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidUrl.status)

        val invalidHost = client.post("/api/v1/ha/oauth/start") {
            header(HttpHeaders.Host, "user@panel.local:8888")
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("ha_url=https%3A%2F%2Fha.example")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidHost.status)

        val oversized = "ha_url=" + "x".repeat(MAX_HA_OAUTH_START_BODY_BYTES.toInt())
        val declared = client.post("/api/v1/ha/oauth/start") {
            panelHost()
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody(oversized)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, declared.status)
        val chunked = client.post("/api/v1/ha/oauth/start") {
            panelHost()
            setBody(chunkedForm(oversized.toByteArray()))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, chunked.status)
        assertEquals(1, harness.starts)
    }

    @Test fun `a newer start invalidates older state while the current callback is one use`() = testApplication {
        val harness = Harness()
        application { routing { route("/api/v1") { haOAuthRoutes(harness.dependencies()) } } }

        val first = start(client, "https://ha-a.example")
        val second = start(client, "https://ha-b.example")
        val old = client.get("/api/v1/ha/oauth/callback?state=${state(first)}&code=old") { panelHost() }
        assertEquals(HttpStatusCode.BadRequest, old.status)
        assertEquals(0, harness.exchanges)

        val current = client.get("/api/v1/ha/oauth/callback?state=${state(second)}&code=current") { panelHost() }
        assertEquals(HttpStatusCode.OK, current.status)
        assertTrue(current.bodyAsText().contains("Home Assistant configured"))
        assertTrue(current.bodyAsText().contains("history.replaceState(null,\"\",\"$HA_OAUTH_CALLBACK_PATH\")"))
        assertTrue(current.bodyAsText().contains("href=\"/configure#cfg-ha_url\""))
        assertOAuthHeaders(current.headers[HttpHeaders.CacheControl], current.headers["Referrer-Policy"])
        assertEquals(1, harness.exchanges)
        assertEquals(1, harness.completions)

        val replay = client.get("/api/v1/ha/oauth/callback?state=${state(second)}&code=current") { panelHost() }
        assertEquals(HttpStatusCode.BadRequest, replay.status)
        assertEquals(1, harness.exchanges)
    }

    @Test fun `callback needs no browser cookie or session from the client which started sign-in`() = testApplication {
        val harness = Harness()
        application { routing { route("/api/v1") { haOAuthRoutes(harness.dependencies()) } } }
        val configureClient = createClient { }
        val privateWindowClient = createClient { }

        val authorizationUrl = start(configureClient, "https://ha.example")
        val completed = callback(privateWindowClient, authorizationUrl, "private-window-code")

        assertEquals(HttpStatusCode.OK, completed.status)
        assertTrue(completed.bodyAsText().contains("Home Assistant configured"))
        assertEquals(1, harness.exchanges)
        assertEquals(1, harness.completions)

        val replay = callback(configureClient, authorizationUrl, "private-window-code")
        assertEquals(HttpStatusCode.BadRequest, replay.status)
        assertEquals(1, harness.exchanges)
        assertEquals(1, harness.completions)
    }

    @Test fun `callback rejects wrong origin expiry cancellation and invalid codes without reflection`() = testApplication {
        val harness = Harness()
        application { routing { route("/api/v1") { haOAuthRoutes(harness.dependencies()) } } }

        val wrongOriginUrl = start(client, "https://ha.example")
        val wrongOrigin = client.get("/api/v1/ha/oauth/callback?state=${state(wrongOriginUrl)}&code=secret-code") {
            header(HttpHeaders.Host, "other.local:8888")
        }
        assertEquals(HttpStatusCode.BadRequest, wrongOrigin.status)
        assertFalse(wrongOrigin.bodyAsText().contains("secret-code"))

        val expiredUrl = start(client, "https://ha.example")
        harness.now += 600_001L
        val expired = client.get("/api/v1/ha/oauth/callback?state=${state(expiredUrl)}&code=secret-code") { panelHost() }
        assertEquals(HttpStatusCode.BadRequest, expired.status)

        val cancelledUrl = start(client, "https://ha.example")
        val cancelled = client.get(
            "/api/v1/ha/oauth/callback?state=${state(cancelledUrl)}&error=access_denied&error_description=PRIVATE_PROVIDER_DETAIL",
        ) { panelHost() }
        assertEquals(HttpStatusCode.BadRequest, cancelled.status)
        assertTrue(cancelled.bodyAsText().contains("sign-in was cancelled"))
        assertFalse(cancelled.bodyAsText().contains("PRIVATE_PROVIDER_DETAIL"))

        val blankCodeUrl = start(client, "https://ha.example")
        val blankCode = client.get("/api/v1/ha/oauth/callback?state=${state(blankCodeUrl)}") { panelHost() }
        assertEquals(HttpStatusCode.BadRequest, blankCode.status)
        val longCodeUrl = start(client, "https://ha.example")
        val longCode = client.get(
            "/api/v1/ha/oauth/callback?state=${state(longCodeUrl)}&code=${"x".repeat(MAX_HA_OAUTH_CODE_CHARS + 1)}",
        ) { panelHost() }
        assertEquals(HttpStatusCode.BadRequest, longCode.status)
        assertEquals(0, harness.exchanges)
        assertEquals(0, harness.completions)
        listOf(wrongOrigin, expired, cancelled, blankCode, longCode).forEach {
            assertOAuthHeaders(it.headers[HttpHeaders.CacheControl], it.headers["Referrer-Policy"])
        }
    }

    @Test fun `exchange and completion outcomes map to generic callback pages`() = testApplication {
        val harness = Harness()
        application { routing { route("/api/v1") { haOAuthRoutes(harness.dependencies()) } } }

        harness.exchangeResult = HaLink.AuthorizationCodeExchange.Rejected
        assertEquals(HttpStatusCode.BadRequest, callback(client, start(client), "rejected").status)
        harness.exchangeResult = HaLink.AuthorizationCodeExchange.Transient
        assertEquals(HttpStatusCode.BadRequest, callback(client, start(client), "transient").status)
        harness.exchangeResult = HaLink.AuthorizationCodeExchange.Success(TOKENS)
        harness.completionResult = HaOAuthCompletion.Stale
        assertTrue(callback(client, start(client), "stale").bodyAsText().contains("settings changed"))
        harness.completionResult = HaOAuthCompletion.CommitFailed
        assertTrue(callback(client, start(client), "failed").bodyAsText().contains("could not save"))
        harness.completionResult = HaOAuthCompletion.Success(ambientWarning = true, reloadMayBeNeeded = true)
        val success = callback(client, start(client), "success")
        assertEquals(HttpStatusCode.OK, success.status)
        assertTrue(success.bodyAsText().contains("manual reload"))
        assertTrue(success.bodyAsText().contains("ambient-light source needs attention"))
    }

    @Test fun `a newer start during exchange makes the claimed callback stale`() = testApplication {
        val harness = Harness()
        harness.enforceCurrentEpoch = true
        harness.onExchange = {
            harness.startAttempt("https://newer-ha.example", "http://panel.local:8888")
        }
        application { routing { route("/api/v1") { haOAuthRoutes(harness.dependencies()) } } }

        val response = callback(client, start(client, "https://older-ha.example"), "claimed-before-new-start")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("settings changed"))
        assertEquals(1, harness.exchanges)
        assertEquals(1, harness.completions)
    }

    private class Harness {
        var now = 0L
        var starts = 0
        var exchanges = 0
        var completions = 0
        var epoch = 0L
        var enforceCurrentEpoch = false
        var onExchange: ((HaOAuthAttempt) -> Unit)? = null
        private var tokenIndex = 0
        private val flow = HaOAuthFlow(
            nowMillis = { now },
            stateToken = { tokenChar(tokenIndex++).repeat(43) },
        )
        var exchangeResult: HaLink.AuthorizationCodeExchange = HaLink.AuthorizationCodeExchange.Success(TOKENS)
        var completionResult: HaOAuthCompletion = HaOAuthCompletion.Success()

        fun startAttempt(haUrl: String, origin: String): HaOAuthStart {
            starts++
            return flow.start(haUrl, origin, HaOAuthAttemptAuthority(OWNER, ++epoch))
        }

        fun dependencies() = HaOAuthRouteDependencies(
            panelPort = 8888,
            start = ::startAttempt,
            claim = flow::claim,
            exchange = { attempt, _ -> exchanges++; onExchange?.invoke(attempt); exchangeResult },
            complete = { attempt, _ ->
                completions++
                if (enforceCurrentEpoch && attempt.expectedEpoch != epoch) HaOAuthCompletion.Stale
                else completionResult
            },
        )
    }

    private companion object {
        val OWNER = HaAuthOwner("", "", "", "")
        val TOKENS = HaLink.OAuthTokens("access", "refresh", 3_600L)

        fun tokenChar(index: Int): String = ('a'.code + index).toChar().toString()

        fun io.ktor.client.request.HttpRequestBuilder.panelHost() {
            header(HttpHeaders.Host, "panel.local:8888")
        }

        suspend fun start(
            client: io.ktor.client.HttpClient,
            haUrl: String = "https://ha.example",
        ): String {
            val response = client.post("/api/v1/ha/oauth/start") {
                panelHost()
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                setBody("ha_url=" + java.net.URLEncoder.encode(haUrl, "UTF-8"))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            return JSONObject(response.bodyAsText()).getString("authorization_url")
        }

        fun state(authorizationUrl: String): String = URI(authorizationUrl).rawQuery.split('&')
            .map { it.split('=', limit = 2) }
            .first { URLDecoder.decode(it[0], "UTF-8") == "state" }
            .let { URLDecoder.decode(it[1], "UTF-8") }

        suspend fun callback(client: io.ktor.client.HttpClient, authorizationUrl: String, code: String) =
            client.get("/api/v1/ha/oauth/callback?state=${state(authorizationUrl)}&code=$code") { panelHost() }

        fun assertOAuthHeaders(cacheControl: String?, referrerPolicy: String?) {
            assertEquals("no-store", cacheControl)
            assertEquals("no-referrer", referrerPolicy)
        }

        fun chunkedForm(bytes: ByteArray) = object : OutgoingContent.WriteChannelContent() {
            override val contentType: ContentType = ContentType.Application.FormUrlEncoded
            override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(bytes) }
        }
    }
}
