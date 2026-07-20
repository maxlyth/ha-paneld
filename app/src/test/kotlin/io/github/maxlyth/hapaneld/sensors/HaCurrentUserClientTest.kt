package io.github.maxlyth.hapaneld.sensors

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaCurrentUserClientTest {
    @Test fun `connected status projects only a bounded display name`() = kotlinx.coroutines.test.runTest {
        val client = HaCurrentUserClient(
            auth = HaApiSessionProvider { HaApiSession("https://ha.example", "secret") },
            transport = HaCurrentUserTransport { _, _ ->
                JSONObject()
                    .put("id", "private-user-id")
                    .put("name", "  Alice\nAdministrator  ")
                    .put("is_admin", true)
                    .put("credentials", "private")
            },
        )

        val status = client.status() as HaCurrentUserStatus.Connected

        assertEquals("Alice Administrator", status.displayName)
        assertTrue(status.toString().contains("Alice Administrator"))
        assertTrue(!status.toString().contains("private-user-id"))
        assertTrue(!status.toString().contains("credentials"))
    }

    @Test fun `authentication rejection retries once then reports rejected`() = kotlinx.coroutines.test.runTest {
        val forces = mutableListOf<Boolean>()
        var calls = 0
        val client = HaCurrentUserClient(
            auth = HaApiSessionProvider { force ->
                forces += force
                HaApiSession("https://ha.example", "secret")
            },
            transport = HaCurrentUserTransport { _, _ ->
                calls++
                throw HaAuthenticationException("rejected")
            },
        )

        assertEquals(HaCurrentUserStatus.Rejected, client.status())
        assertEquals(listOf(false, true), forces)
        assertEquals(2, calls)
    }

    @Test fun `missing credentials avoid network and blank names remain anonymous`() = kotlinx.coroutines.test.runTest {
        var calls = 0
        val missing = HaCurrentUserClient(
            auth = HaApiSessionProvider { HaApiSession("https://ha.example", null) },
            transport = HaCurrentUserTransport { _, _ -> calls++; JSONObject() },
        )
        assertEquals(HaCurrentUserStatus.NotConfigured, missing.status())
        assertEquals(0, calls)

        val blank = HaCurrentUserClient(
            auth = HaApiSessionProvider { HaApiSession("https://ha.example", "secret") },
            transport = HaCurrentUserTransport { _, _ -> JSONObject().put("name", "\u0000  ") },
        ).status() as HaCurrentUserStatus.Connected
        assertNull(blank.displayName)
    }
}
