package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProximityWizardHostTest {
    private val owners = mutableListOf<Any>()
    private val actions = mutableListOf<String>()

    @Before
    fun clearPreviousBinding() {
        val owner = Any()
        ProximityWizardHost.attach(owner, { "{}" }, { false })
        ProximityWizardHost.detach(owner)
    }

    @After
    fun detachOwners() {
        owners.forEach(ProximityWizardHost::detach)
    }

    private fun attach(id: String, accepted: Boolean = true): Any = Any().also { owner ->
        owners.add(owner)
        ProximityWizardHost.attach(owner, { """{"sessionId":"$id","stage":"intro"}""" }, action = {
            actions.add(it)
            accepted
        })
    }

    @Test
    fun noServiceCannotAdmitActivityActions() {
        assertNull(ProximityWizardHost.status())
        assertFalse(ProximityWizardHost.action("old", "begin"))
    }

    @Test
    fun liveSessionForwardsVisibilityAndControlsExactlyOnce() {
        attach("current")
        for (action in listOf("visible", "begin", "retry", "save", "cancel")) {
            assertTrue(ProximityWizardHost.action("current", action))
        }
        assertEquals(listOf("visible", "begin", "retry", "save", "cancel"), actions)
    }

    @Test
    fun staleOrEmptyIdCannotControlCurrentSession() {
        attach("new")
        assertFalse(ProximityWizardHost.action("old", "cancel"))
        assertFalse(ProximityWizardHost.action("", "begin"))
        assertFalse(ProximityWizardHost.action(" ", "visible"))
        assertTrue(actions.isEmpty())
        assertTrue(ProximityWizardHost.action("new", "begin"))
        assertEquals(listOf("begin"), actions)
    }

    @Test
    fun replacedOwnerAndItsLateDetachCannotAffectNewOwner() {
        val old = attach("old")
        val current = attach("new")
        ProximityWizardHost.detach(old)
        assertFalse(ProximityWizardHost.action("old", "cancel"))
        assertTrue(ProximityWizardHost.action("new", "visible"))
        assertEquals(listOf("visible"), actions)
        ProximityWizardHost.detach(current)
        assertNull(ProximityWizardHost.status())
        assertFalse(ProximityWizardHost.action("new", "save"))
    }

    @Test
    fun rejectedOwnerActionIsNotReportedSuccessful() {
        attach("current", accepted = false)
        assertFalse(ProximityWizardHost.action("current", "save"))
        assertEquals(listOf("save"), actions)
    }

    @Test
    fun unknownActionNeverReachesService() {
        attach("current")
        for (action in listOf("", "start", "erase", "SAVE")) {
            assertFalse(ProximityWizardHost.action("current", action))
        }
        assertTrue(actions.isEmpty())
    }

    @Test
    fun malformedOrMissingSessionSnapshotFailsClosed() {
        val owner = Any().also(owners::add)
        for (snapshot in listOf("not json", "{}", """{"sessionId":""}""")) {
            ProximityWizardHost.attach(owner, { snapshot }, { actions.add(it); true })
            assertFalse(ProximityWizardHost.action("current", "begin"))
        }
        assertTrue(actions.isEmpty())
    }

    @Test
    fun ownerFailureIsContained() {
        val owner = Any().also(owners::add)
        ProximityWizardHost.attach(owner, { error("status unavailable") }, { true })
        assertNull(ProximityWizardHost.status())
        assertFalse(ProximityWizardHost.action("current", "begin"))
        ProximityWizardHost.attach(owner, { """{"sessionId":"current"}""" }, { error("action unavailable") })
        assertFalse(ProximityWizardHost.action("current", "begin"))
    }

    @Test
    fun narrationUsesOnlyTheCurrentSessionAndCanBeStopped() {
        val owner = Any().also(owners::add)
        val spoken = mutableListOf<Pair<String, String>>()
        var stops = 0
        ProximityWizardHost.attach(
            owner,
            { """{"sessionId":"current"}""" },
            { true },
            narrate = { prompt, text, locale -> spoken += prompt to "$text@$locale"; true },
            stopNarration = { stops++ },
        )
        assertFalse(ProximityWizardHost.narrate("stale", "near|APPROACH", "Approach", "en-GB"))
        ProximityWizardHost.stopNarration("stale")
        assertTrue(ProximityWizardHost.narrate("current", "near|APPROACH", "Approach", "fr-FR"))
        ProximityWizardHost.stopNarration("current")
        assertEquals(listOf("near|APPROACH" to "Approach@fr-FR"), spoken)
        assertEquals(1, stops)
    }

    @Test
    fun onlyCompletedPresentationCanRebindToNewLaunch() {
        for (stage in listOf("intro", "clear", "near", "return_clear", "waves", "review", "saving")) {
            assertFalse(stage, proximityWizardMayRebind(stage))
        }
        for (stage in listOf("saved", "cancelled", "timed_out", "failed", "unavailable")) {
            assertTrue(stage, proximityWizardMayRebind(stage))
        }
    }

    @Test
    fun leavingCancelsLiveSessionButRotationPreservesIt() {
        for (stage in listOf("intro", "clear", "near", "return_clear", "waves", "review", "saving")) {
            assertTrue(stage, proximityWizardMustCancelOnStop(stage, changingConfigurations = false))
            assertFalse(stage, proximityWizardMustCancelOnStop(stage, changingConfigurations = true))
        }
        for (stage in listOf("saved", "cancelled", "timed_out", "failed", "unavailable")) {
            assertFalse(stage, proximityWizardMustCancelOnStop(stage, changingConfigurations = false))
        }
    }

    @Test
    fun activityUsesLocalControlsAndStopsItsMainThreadPollingWhenHidden() {
        val source = TestSources.kotlin("ProximityWizardActivity.kt").readText()
        assertTrue(source.contains("Handler(Looper.getMainLooper())"))
        assertTrue(source.contains("handler.removeCallbacks(poll)"))
        assertTrue(source.contains("if (!visible) return"))
        assertTrue(source.contains("proximityWizardMustCancelOnStop(stage, isChangingConfigurations)"))
        assertTrue(source.contains("proximityWizardMayRebind(stage)"))
        assertTrue(source.contains("FLAG_KEEP_SCREEN_ON"))
        assertTrue(source.contains("ProximityWizardHost.action(currentId, \"visible\")"))
        assertTrue(source.contains("snapshot.getDouble(\"raw\")"))
        assertTrue(source.contains("snapshot.optString(\"health\") == \"healthy\""))
        assertTrue(source.contains("proximityWizardShowsRawValue(stage)"))
        assertTrue(source.contains("R.string.proximity_wizard_raw_label"))
        assertTrue(source.contains("proximityWizardSpeech("))
        assertFalse(source.contains("listOf(instruction.text, detail.text)"))
        assertTrue(source.contains("ProximityWizardHost.narrate("))
        assertTrue(source.contains("ProximityWizardHost::stopNarration"))
        assertFalse(source.contains("startService("))
        assertFalse(source.contains("TextToSpeech"))
        assertFalse(source.contains("android.speech.tts"))
        assertFalse(source.contains("ProximityWizardHost.attach("))
    }

    @Test
    fun activityPassesResolvedLocaleAndServiceUsesOnlyHomeAssistantNarration() {
        val activity = TestSources.kotlin("ProximityWizardActivity.kt").readText()
        assertTrue(activity.contains("resources.configuration.locales[0].toLanguageTag()"))
        val source = TestSources.kotlin("PaneldService.kt").readText()
        val wiring = source.substring(
            source.indexOf("narrator = ProximityWizardNarrator"),
            source.indexOf("narrator = ProximityWizardNarrator") + 720,
        )
        assertTrue(wiring.contains("AssistPipelineClient(config).speakText("))
        assertTrue(wiring.contains("text, localeTag, onGeneration"))
        assertTrue(wiring.contains("localeTag"))
        assertTrue(wiring.contains("AnnouncementLanePlayback("))
        assertTrue(wiring.contains("onGeneration = onGeneration"))
        assertTrue(wiring.contains("audio.cancelGeneration(generation)"))
        assertFalse(wiring.contains("TextToSpeech"))
        assertFalse(wiring.contains("Pico"))
    }
}
