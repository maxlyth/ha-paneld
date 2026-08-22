package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardDbSuccessorHandoffTest {
    @Test fun `production handoff publishes only a fixed immutable OS PaneldService alarm`() {
        val coordinator = TestSources.kotlin("util/GuardDbArmCoordinator.kt").readText()
        val request = coordinator.substring(coordinator.indexOf("private fun requestFreshGuardDbProcess"))
        val alarm = TestSources.kotlin("util/GuardDbSuccessorAlarm.kt").readText()

        assertTrue(request.contains("publishAlarmRetry = { GuardDbSuccessorAlarm.schedule(context) }"))
        assertTrue(request.contains("exitCurrentProcess = { Process.killProcess(Process.myPid()) }"))
        assertTrue(request.contains("alarmPublicationRetry.schedule(retry, delayMs, TimeUnit.MILLISECONDS)"))
        assertFalse(request.contains("PaneldService.start("))
        assertFalse(request.contains("startForegroundService("))
        assertFalse(request.contains("startService("))
        assertEquals(1, Regex("Process\\.killProcess").findAll(request).count())

        assertEquals("io.github.maxlyth.hapaneld.action.GUARD_DB_SUCCESSOR_RETRY", GuardDbSuccessorAlarm.ACTION)
        assertEquals(0x48414752, GuardDbSuccessorAlarm.REQUEST_CODE)
        assertEquals(1_000L, GuardDbSuccessorAlarm.DELAY_MS)
        assertTrue(alarm.contains("PendingIntent.getForegroundService("))
        assertTrue(alarm.contains("Intent(context, PaneldService::class.java).setAction(ACTION)"))
        assertTrue(alarm.contains("PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE"))
        assertTrue(alarm.contains("val operation = retryIntent(context)"))
        assertTrue(alarm.contains("AlarmManager.ELAPSED_REALTIME_WAKEUP"))
        assertTrue(alarm.contains("alarm.setExactAndAllowWhileIdle("))
        assertTrue(alarm.contains("alarm.setAndAllowWhileIdle("))
        assertFalse("fixed retry authority carries no mutable payload", alarm.contains("putExtra("))
    }

    @Test fun `accepted alarm publication precedes process exit`() {
        val events = mutableListOf<String>()
        var localRetryScheduled = false
        GuardDbSuccessorHandoff(
            publishAlarmRetry = { events += "publish-alarm" },
            exitCurrentProcess = { events += "exit" },
            scheduleAlarmPublicationRetry = { _, _ -> localRetryScheduled = true },
            onPublicationFailure = { throw AssertionError("unexpected publication failure", it) },
        ).request()

        assertEquals(listOf("publish-alarm", "exit"), events)
        assertFalse(localRetryScheduled)
    }

    @Test fun `failed alarm publication retains process and retries publication without direct start`() {
        val publicationFailure = IllegalStateException("alarm service unavailable")
        val events = mutableListOf<String>()
        var observedFailure: Throwable? = null
        var publicationAllowed = false
        var retryDelayMs: Long? = null
        var retry: (() -> Unit)? = null
        val handoff = GuardDbSuccessorHandoff(
            publishAlarmRetry = {
                events += "publish-alarm"
                if (!publicationAllowed) throw publicationFailure
            },
            exitCurrentProcess = { events += "exit" },
            scheduleAlarmPublicationRetry = { delayMs, action ->
                retryDelayMs = delayMs
                retry = action
            },
            onPublicationFailure = { observedFailure = it },
        )

        handoff.request()

        assertEquals(listOf("publish-alarm"), events)
        assertSame(publicationFailure, observedFailure)
        assertEquals(GuardDbSuccessorHandoff.RETRY_DELAY_MS, retryDelayMs)
        assertTrue("writer-free process must remain live while no OS retry exists", "exit" !in events)

        publicationAllowed = true
        val scheduledRetry = retry
        assertTrue("alarm publication failure must retain a scheduling retry", scheduledRetry != null)
        scheduledRetry!!.invoke()

        assertEquals(listOf("publish-alarm", "publish-alarm", "exit"), events)
    }
}
