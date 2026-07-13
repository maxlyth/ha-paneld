package io.github.maxlyth.hapaneld.logship

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [LogCapture.redact] is the last line of defence before a log line reaches ANY consumer — the
 * remote [LogShipper] sink and the `:8888` live log viewer both read the same redacted stream — so
 * the secret shapes it strips are pinned here. Capture is the app's own logcat, which can carry MQTT
 * creds, HA tokens, and URLs with query secrets — none of which should escape verbatim.
 */
class LogCaptureTest {
    private fun redact(s: String) = LogCapture.redact(s)

    @Test fun stripsBearerAuthorizationHeader() {
        val out = redact("06-28 10:15:30.123  900  950 D ha-paneld/http: Authorization: Bearer abc123XYZ.token-value")
        assertFalse("token must not survive", out.contains("abc123XYZ.token-value"))
        assertTrue(out.contains("***"))
    }

    @Test fun stripsPasswordAndTokenKeyValues() {
        assertFalse(redact("mqtt password=hunter2secret").contains("hunter2secret"))
        assertFalse(redact("access_token: aaaaaaaabbbbbbbb").contains("aaaaaaaabbbbbbbb"))
        assertFalse(redact("api_key = ZZZZ9999ZZZZ").contains("ZZZZ9999ZZZZ"))
        // The key label is preserved so the line stays readable.
        assertTrue(redact("password=hunter2secret").startsWith("password"))
    }

    @Test fun stripsJwtLikeTokens() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        val out = redact("06-28 10:15:30.123  900  950 I ha-paneld/mqtt: connecting with $jwt")
        assertFalse(out.contains(jwt))
        assertTrue(out.contains("***jwt***"))
    }

    @Test fun stripsUrlQuerySecrets() {
        val out = redact("GET http://ha.local:8123/api/stream?access_token=SUPERSECRETTOKEN&foo=bar")
        assertFalse(out.contains("SUPERSECRETTOKEN"))
        // Non-secret query params and the rest of the URL are untouched.
        assertTrue(out.contains("foo=bar"))
        assertTrue(out.contains("http://ha.local:8123/api/stream"))
    }

    @Test fun leavesOrdinaryLinesUnchanged() {
        val line = "06-28 10:15:30.123  900  950 I ha-paneld/svc: foreground service started"
        assertEquals(line, redact(line))
    }

    // ---- streaming behaviour (shell subprocess stands in for logcat) ----------------------------

    private fun capture(script: String, dump: String = "true") = LogCapture(
        CoroutineScope(Dispatchers.IO),
        listOf("sh", "-c", script),
        { listOf("sh", "-c", dump) },
    )

    /** Poll until [cond] or the deadline — the subprocess pipe delivery is asynchronous. */
    private fun await(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            assertTrue("timed out waiting for capture output", System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
    }

    @Test fun streamsRedactsAndBuffersLines() {
        val cap = capture("echo one; echo 'password=hunter2secret'; sleep 30")
        val got = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sub = cap.subscribe { got.add(it) }
        try {
            await { got.size >= 2 }
            assertEquals("one", got[0])
            // Redaction happens at capture, so the viewer path can never see the secret either.
            assertFalse(got[1].contains("hunter2secret"))
            // The ring snapshot serves the same (redacted) lines as backlog for a late viewer.
            assertTrue(cap.snapshot().containsAll(got.take(2)))
        } finally {
            sub.close()
        }
    }

    @Test fun fanOutReachesEverySubscriberAndIdleStopClearsRing() {
        val cap = capture("echo shared; sleep 30")
        val a = java.util.concurrent.CopyOnWriteArrayList<String>()
        val b = java.util.concurrent.CopyOnWriteArrayList<String>()
        val subA = cap.subscribe { a.add(it) }
        val subB = cap.subscribe { b.add(it) }
        await { a.isNotEmpty() && b.isNotEmpty() }
        assertEquals("shared", a[0])
        assertEquals("shared", b[0])
        subA.close()
        // One subscriber left → capture (and its backlog ring) stays alive.
        assertTrue(cap.snapshot().isNotEmpty())
        subB.close()
        // Last detach = idle-stop: the subprocess is destroyed and the ring cleared.
        assertTrue(cap.snapshot().isEmpty())
    }

    @Test fun dumpRunsOneShotCommandRedacted() {
        val cap = capture("sleep 30", dump = "echo ok; echo 'api_key = ZZZZ9999ZZZZ'")
        val out = cap.dump(10)
        assertEquals("ok", out[0])
        assertFalse(out[1].contains("ZZZZ9999ZZZZ"))
    }

    @Test fun terminalCloseClearsStateAndRejectsNewSubscribers() {
        val cap = capture("echo before-close; sleep 30")
        val before = java.util.concurrent.CopyOnWriteArrayList<String>()
        val first = cap.subscribe { before.add(it) }
        await { before.isNotEmpty() }

        cap.close()
        assertTrue(cap.snapshot().isEmpty())
        assertTrue(cap.dump().isEmpty())
        val after = java.util.concurrent.CopyOnWriteArrayList<String>()
        val rejected = cap.subscribe { after.add(it) }
        Thread.sleep(100)
        assertTrue(after.isEmpty())
        first.close()
        rejected.close()
    }

    @Test fun terminalCloseDestroysBlockedDumpProcess() {
        val cap = LogCapture(
            CoroutineScope(Dispatchers.IO),
            listOf("true"),
            { listOf("sleep", "30") },
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val dump = executor.submit<List<String>> { cap.dump() }
            Thread.sleep(100)
            cap.close()
            assertTrue(dump.get(2, TimeUnit.SECONDS).isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }
}
