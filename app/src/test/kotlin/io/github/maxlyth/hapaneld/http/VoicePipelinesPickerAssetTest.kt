package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contracts for the voice_pipelines Configure picker in configure.js: a retained pipeline id Home
 * Assistant no longer offers must render as an honest "unknown retained value", not silently as the
 * unset "Preferred pipeline" placeholder; and raw JSON typed into the degraded textarea must survive an
 * asynchronous catalogue response landing mid-edit. configure.js has no JS-executing test harness in
 * this codebase (see ConfigureControlSizingAssetTest for the same source-content-assertion pattern), so
 * these are asset-content contracts rather than a DOM-behavioral test.
 */
class VoicePipelinesPickerAssetTest {
    private val js = listOf(
        File("src/main/assets/configure.js"),
        File("app/src/main/assets/configure.js"),
        File("../app/src/main/assets/configure.js"),
    ).first(File::isFile).readText()

    @Test fun `a retained pipeline id Home Assistant no longer offers is rendered as an honest unknown value`() {
        assertTrue(
            "a retained-but-unmatched pipeline id must get its own labelled option",
            "not in Home Assistant's list" in js,
        )
        assertTrue(
            "the retained-unknown branch must track whether the catalogue matched it",
            "matchedRetained" in js,
        )
    }

    @Test fun `the raw pipelines textarea commits on every keystroke, not only on blur`() {
        assertTrue("voice-pipelines-raw textarea must exist", "voice-pipelines-raw" in js)
        val rawBlockStart = js.indexOf("var pipelinesRaw = el(\"textarea\"")
        assertTrue("raw textarea construction must exist", rawBlockStart >= 0)
        val rawBlockEnd = js.indexOf("pipelinesWrap.appendChild(pipelinesRaw)", rawBlockStart)
        assertTrue(rawBlockEnd > rawBlockStart)
        val rawBlock = js.substring(rawBlockStart, rawBlockEnd)
        assertTrue(
            "the raw textarea must listen on 'input' so every keystroke reaches values[f.key]",
            "addEventListener(\"input\"" in rawBlock,
        )
        assertFalse(
            "a 'change'-only listener on this textarea would lose keystrokes typed before blur",
            "addEventListener(\"change\"" in rawBlock,
        )
    }

    @Test fun `an actively-focused raw textarea is not switched away from mid-edit`() {
        assertTrue(
            "the picker must check document.activeElement against its own raw textarea before switching",
            "activeRawTextarea" in js && "document.activeElement" in js,
        )
        assertTrue(
            "the raw textarea must be identifiable across renders by its field key",
            "data-field-key" in js,
        )
    }
}
