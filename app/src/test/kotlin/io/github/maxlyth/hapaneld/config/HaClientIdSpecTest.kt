package io.github.maxlyth.hapaneld.config

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaClientIdSpecTest {
    @Test fun oauthImplementationFieldsStayStoredButOnlyLongLivedTokenIsUserFacing() {
        val clientId = SettingsRegistry.spec("ha_client_id")
        val refreshToken = SettingsRegistry.spec("ha_refresh_token")
        val longLivedToken = SettingsRegistry.spec("ha_token")

        assertNotNull("client id must remain available to token import and refresh", clientId)
        assertNotNull("refresh token must remain available to token import and refresh", refreshToken)
        assertTrue("token provenance is derived, not a user-facing preference", clientId!!.hidden)
        assertTrue("refresh credentials are managed automatically", refreshToken!!.hidden)
        assertFalse("the understandable LLT fallback remains configurable", longLivedToken!!.hidden)
        assertTrue(longLivedToken.label.contains("long-lived access token", ignoreCase = true))
    }
}
