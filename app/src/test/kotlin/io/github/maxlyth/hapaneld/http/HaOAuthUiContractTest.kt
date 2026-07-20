package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HaOAuthUiContractTest {
    @Test fun `configure offers browser sign-in without replacing the long-lived token fallback`() {
        val source = asset("configure.js").readText()

        assertTrue("browser sign-in must post the in-memory HA URL", "new URLSearchParams({ ha_url: target })" in source)
        assertTrue("configured is state, not a connectivity claim", "haAuth.configured ? \"Reconnect\" : \"Connect\"" in source)
        assertTrue("OAuth provenance must be visible", "OAuth configured" in source)
        assertFalse("credential UI must not claim that the live transport is connected", "OAuth connected" in source)
        assertTrue("another HA user needs a private-window path", "To sign in as another user, copy the link into a private window." in source)
        assertTrue("a normal link must remain available when popups are blocked", "text: \"Open sign-in\"" in source)
        assertTrue("manual links must not leak callback context", "rel: \"noopener noreferrer\"" in source && "referrerpolicy: \"no-referrer\"" in source)
        assertFalse("starting a login must not navigate an already authenticated browser session", "window.open(" in source)
        val dirtyHandler = source.substringAfter("function setDirty()").substringBefore("function clearDirty()")
        assertTrue("typing a previously blank URL must immediately enable the button", "syncHaOAuthAvailability()" in dirtyHandler)
        assertTrue("callback completion must preserve unrelated unsaved values", "savedValues.ha_url = haOauthTargetUrl" in source && "recomputeDirty(); updateSaveUi(); render();" in source)
        assertTrue("OAuth must supersede a typed token which could overwrite it later", "savedValues.ha_token = \"\"" in source)
        val startHandler = source.substringAfter("function startHaOAuth()").substringBefore("function haOAuthRow()")
        assertTrue("private-window sign-in must clear a typed token before the request", startHandler.indexOf("values.ha_token = \"\"") < startHandler.indexOf("fetch(\"/api/v1/ha/oauth/start\""))
        assertTrue("failed links must be removed before retry", "openLink.removeAttribute(\"href\")" in source)
        assertTrue("browser sign-in must follow the HA URL instead of the token fallback", "f.key === \"ha_url\") card.appendChild(haOAuthRow())" in source)
        assertTrue("connection status must remain a one-shot no-store probe", "fetch(\"/api/v1/ha/oauth/status\"" in source && "cache: \"no-store\"" in source)
        assertTrue("the authenticated display name must identify the current connection", "Connected as " in source)
        assertTrue("the connected identity must receive prominent styling", "haUserStatus.phase === \"connected\"" in source && "classList.toggle(\"connected\"" in source)
        assertTrue("the connected identity must be visibly bold and successful", ".ha-oauth-status.connected{color:var(--ok-dim);font-weight:700}" in asset("info.css").readText())
        assertTrue("the connected identity must have a restrained success dot", ".ha-oauth-status.connected::before" in asset("info.css").readText() && "background:var(--ok-dim)" in asset("info.css").readText())
        assertTrue("the connected identity must precede the explanatory instructions", source.indexOf("haOauthStatus,") < source.indexOf("Sign in from this computer."))
        assertTrue("every Configure rerender must restore connected identity styling", "haOauthStatus = el(\"div\"" in source && "renderHaConnectionStatus();" in source.substringAfter("haOauthStatus = el(\"div\"").substringBefore("var openLink"))
        assertTrue("the token must be described as an advanced fallback", "Long-lived access token (advanced fallback)" in projectFile("app/src/main/kotlin/io/github/maxlyth/hapaneld/config/SettingsRegistry.kt").readText())
    }

    @Test fun `OAuth callback is one-use no-store and never a Hardened action`() {
        val server = projectFile("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        val routes = projectFile("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/HaOAuthRoutes.kt").readText()

        assertTrue("both routes must be versioned", "route(\"/ha/oauth\")" in routes && "post(\"/start\")" in routes && "get(\"/callback\")" in routes)
        assertTrue("connection identity status must share the no-store OAuth boundary", "get(\"/status\")" in routes && "call.noStoreHaOAuth()" in routes)
        assertTrue("callback must consume state before exchange", routes.indexOf("dependencies.claim") < routes.indexOf("dependencies.exchange"))
        assertTrue("callback pages must suppress storage and referrers", "HttpHeaders.CacheControl, \"no-store\"" in routes && "Referrer-Policy\", \"no-referrer\"" in routes)
        val interceptor = server.substringAfter("intercept(ApplicationCallPipeline.Plugins)").substringBefore("routing {")
        assertTrue("callback privacy headers must precede every outer guard rejection", interceptor.indexOf("HA_OAUTH_CALLBACK_PATH") < interceptor.indexOf("isLocalSource"))
        assertTrue("late completion must compare credential owner and newest-start epoch", "expectedHaAuthOwner = attempt.expectedOwner" in server && "expectedHaOAuthEpoch = attempt.expectedEpoch" in server)
        assertFalse("normal HA login must not require physical Hardened approval", "authorizeSensitive" in routes)
        assertFalse("raw HA error descriptions must never be reflected", "error_description" in routes)
    }

    private fun asset(name: String): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(File(working, "app/src/main/assets/$name"), File(working, "src/main/assets/$name"))
            .first { it.isFile }
    }

    private fun projectFile(path: String): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(File(working, path), File(working.parentFile, path)).first { it.isFile }
    }
}
