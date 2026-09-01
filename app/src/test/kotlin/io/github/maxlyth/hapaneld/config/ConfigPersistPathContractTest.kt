package io.github.maxlyth.hapaneld.config

import io.github.maxlyth.hapaneld.device.profile.BundledProfileFixtures
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every settable setting must actually be able to persist.
 *
 * The HTTP config route has exactly two ways to make a posted value durable: a key with
 * `liveApply = true` goes through the live-setting authority, while ordinary keys pass through the
 * registry writer and any coupled owner helper in the direct-mutation batch. This source-presence
 * floor remains useful for its named historical regressions; the behavioural walker proves that the
 * referenced writer actually commits and reads each catalogue value back.
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
     * The activation latch is derived machine state. The other three are user-controlled through the
     * specialized Entities API. All four are rejected by direct `/config` admission and exercised by
     * the behavioural settings contract through their actual owners.
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
     * A key counts as wired when the handler or one of its production-used owner helpers reads it. That
     * is deliberately a floor rather than
     * a proof: seven keys (`mqtt_broker`, `mqtt_user`, `mqtt_address_family`, `ha_url`,
     * `ha_token_expiry`, `dashboard_entity_learning`, `log_ship_enabled`) are read into a named local
     * inside the batch and written further down, so requiring a `config.set` call near the lookup
     * rejects working code — the first draft of this test did exactly that. Presence still separates the
     * two real defects from every working key, because both `dashboard_idle_return_min` and the four
     * camera settings were not mentioned in this file *anywhere*: there was nothing to read the value.
     */
    private fun isWired(key: String): Boolean =
        server.contains("p[\"$key\"]") || server.contains("posted[\"$key\"]")

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
        assertEquals(
            "the camera card holds the switch, the three stream defaults and the exposure bias",
            listOf("camera_enabled", "camera_resolution", "camera_fps", "camera_kbps", "camera_exposure"),
            camera.map { it.key },
        )

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

    /**
     * Every numeric setting's own default must sit on the grid its `min` and `step` describe.
     *
     * `configure.js` assigns `inp.step` straight onto the input, so the browser enforces that grid as
     * constraint validation: a default off the grid is rejected on the very first save, with a message
     * about "the two nearest valid values" and no clue that the registry is at fault. That shipped once —
     * `camera_exposure` declared min -2 with a third-of-a-stop step, which does not contain 0 — and the
     * class is invisible to every behavioural test, because the value is never wrong, only unenterable.
     */
    @Test fun everyNumericSettingsDefaultSitsOnItsOwnStepGrid() {
        val offGrid = SettingsRegistry.SPECS.mapNotNull { spec ->
            val min = spec.min ?: return@mapNotNull null
            val step = spec.step?.takeIf { it > 0.0 } ?: return@mapNotNull null
            val default = spec.default.toDoubleOrNull() ?: return@mapNotNull null
            val steps = (default - min) / step
            if (Math.abs(steps - Math.round(steps)) < 1e-9) null else spec.key
        }
        assertEquals("a default the browser will refuse to save is not a default", emptyList<String>(), offGrid)
    }

    @Test fun theExposureGridOffersTheValuesItsHelpDescribes() {
        val spec = SettingsRegistry.spec("camera_exposure")
        assertNotNull(spec)
        // Whole and half stops within the advertised range must all be enterable, since the help text
        // talks in stops, and a whole stop either way is the first thing anyone will reach for.
        listOf(-2.0, -1.5, -1.0, -0.5, 0.0, 0.5, 1.0, 1.5, 2.0).forEach { value ->
            val steps = (value - spec!!.min!!) / spec.step!!
            assertTrue("$value must be on the grid", Math.abs(steps - Math.round(steps)) < 1e-9)
            assertTrue("$value must be within range", value >= spec.min!! && value <= spec.max!!)
        }
    }
}
