package io.github.maxlyth.hapaneld.config

import io.github.maxlyth.hapaneld.device.profile.BundledProfileFixtures
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every settable setting must actually be able to persist.
 *
 * The HTTP config route has exactly two ways to make a posted value durable: a key with
 * `liveApply = true` goes through the live-setting authority, and everything else needs an explicit
 * `p["key"]?.let { config.set…(it) }` line in the direct-mutation batch. A registry key with neither
 * is rendered on the Configure page, reported back in the response's `applied` list, and silently
 * discarded — the form reverts on the next load and nothing anywhere says why.
 *
 * That has now happened twice. `dashboard_idle_return_min` shipped with no persist path (the batch
 * still carries the comment recording it), and all four camera trial settings shipped the same way,
 * which showed up as the Camera switch turning itself back off on every submit. Both were invisible
 * because the response reports success either way: non-live keys are added to `applied`
 * from the *planned* change set, without checking that anything was written.
 *
 * So this is a whole-flow invariant rather than a fact about one setting, and it is asserted as an
 * exact set: a new setting with no persist path fails here rather than in somebody's Configure form.
 *
 * Limitation, stated rather than implied: this reads source text, so it proves the handler reads each
 * key, not that what it then does with the value is correct. It is a floor under a defect class that
 * has escaped twice, not a substitute for the behavioural tests around it.
 */
class ConfigPersistPathContractTest {

    /**
     * Keys with no `p["key"]` line, each persisted by another route that a source scan cannot see.
     * Every entry needs a reason. This list is exact — growing it is a decision, not a formality.
     */
    /**
     * Keys the HTTP handler never reads, each owned by another writer. Every entry needs a reason, and
     * the assertion below is an exact set: growing this list is a decision, not a formality.
     *
     * All four are machine-managed entity-learning state, written by the learning subsystem through
     * `Config` and never posted as a Configure form field, so there is nothing for the handler to read.
     */
    private val ownedByAnotherWriter = setOf(
        "dashboard_entity_overrides",
        "dashboard_entity_learning_applied",
        "dashboard_entity_auto_static",
        "dashboard_entity_auto_runtime",
    )

    private val server: String by lazy {
        File(BundledProfileFixtures.mainKotlinDirectory, "io/github/maxlyth/hapaneld/http/PaneldServer.kt")
            .also { assertTrue("PaneldServer.kt must be readable: ${it.path}", it.isFile) }
            .readText()
    }

    /**
     * A key counts as wired when the handler reads it at all. That is deliberately a floor rather than
     * a proof: seven keys (`mqtt_broker`, `mqtt_user`, `mqtt_address_family`, `ha_url`,
     * `ha_token_expiry`, `dashboard_entity_learning`, `log_ship_enabled`) are read into a named local
     * inside the batch and written further down, so requiring a `config.set` call near the lookup
     * rejects working code — the first draft of this test did exactly that. Presence still separates the
     * two real defects from every working key, because both `dashboard_idle_return_min` and the four
     * camera settings were not mentioned in this file *anywhere*: there was nothing to read the value.
     */
    private fun isWired(key: String): Boolean = server.contains("p[\"$key\"]")

    @Test fun everySettableNonLiveSettingHasAPersistPath() {
        val live = SettingsRegistry.liveApplyKeys().toSet()
        val unpersisted = SettingsRegistry.settable()
            .map { it.key }
            .filter { it !in live }
            .filterNot { isWired(it) }
            .toSet()
        assertEquals(
            "a settable non-live setting with no persist path is reported saved and silently discarded",
            ownedByAnotherWriter,
            unpersisted,
        )
    }

    @Test fun theCameraTrialSettingsPersistThroughTheDirectMutationBatch() {
        // The regression itself, named. Each of these was reported applied and never written.
        listOf("camera_enabled", "camera_resolution", "camera_fps", "camera_kbps").forEach { key ->
            assertTrue("$key must be read by the config batch so it can persist", isWired(key))
            assertTrue("$key must not be routed through the live path", key !in SettingsRegistry.liveApplyKeys())
        }
    }

    @Test fun theCameraSettingsAreTheirOwnConfigureCardAndSayTheyAreExperimental() {
        val camera = SettingsRegistry.SPECS.filter { it.key.startsWith("camera_") }
        assertEquals("every camera setting belongs to the Camera card", setOf("Camera"), camera.map { it.group }.toSet())
        assertEquals("the camera card must hold exactly the four trial settings", 4, camera.size)

        val configureJs = File(BundledProfileFixtures.mainKotlinDirectory, "../assets/configure.js")
        assertTrue("configure.js must be readable", configureJs.isFile)
        // Read the Camera entry itself, not the whole map. Asserting that the map merely *contains*
        // "exp" is satisfied by the Display card's own badge, so swapping Camera's style to something
        // else survived the mutation battery — the assertion could not fail for the reason it claimed.
        val badges = configureJs.readText().substringAfter("var CARD_BADGES =").substringBefore(";")
        assertTrue("the Camera card must carry a badge: $badges", badges.contains("\"Camera\""))
        val cameraBadge = badges.substringAfter("\"Camera\":").substringAfter("[").substringBefore("]")
        assertEquals(
            "the Camera badge must be the experimental pill in the existing style",
            listOf("\"experimental\"", "\"exp\""),
            cameraBadge.split(",").map { it.trim() },
        )
    }
}
