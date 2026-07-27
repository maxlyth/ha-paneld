package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupUiContractTest {
    private val script by lazy {
        listOf(
            File("src/main/assets/install.js"),
            File("app/src/main/assets/install.js"),
        ).first { it.isFile }.readText()
    }
    private val serverSource by lazy {
        listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        ).first { it.isFile }.readText()
    }
    private val openApi by lazy {
        listOf(
            File("src/main/assets/openapi.json"),
            File("app/src/main/assets/openapi.json"),
        ).first { it.isFile }.readText()
    }

    @Test fun browserRequiresExplicitEncryptionOrPlaintextAcknowledgementAndNamesZipTruthfully() {
        assertTrue("plaintext acknowledgement must be sent", "allow_plaintext=" in script)
        assertTrue("empty passphrase must not silently download credentials", "if (!pw && !plain)" in script)
        assertTrue("plaintext v2 archive must download as ZIP", "'zip'" in script)
        assertTrue("server artifact must advertise ZIP", "PanelBackup.Artifact(plain, \"zip\")" in serverSource)
        assertTrue("restore picker must identify encrypted and plaintext backup extensions", "Choose backup (.hpb or .zip)" in serverSource)
        assertTrue("restore picker must accept encrypted and plaintext backup extensions", """accept=".hpb,.zip,application/octet-stream,application/zip"""" in serverSource)
        assertTrue("OpenAPI must document plaintext acknowledgement", "\"allow_plaintext\"" in openApi)
        assertTrue("legacy JSON filename must not remain", "'json'" !in script.substring(
            script.indexOf("window.doBackup"),
            script.indexOf("// Pick a bundle"),
        ))
    }

    /**
     * A panel with no HA Companion installed must not mention it at all — no include-login checkbox, no
     * "needs the current helper" note, and nothing about it in the bundle description or restore warning.
     */
    @Test fun theBackupCardIsSilentAboutTheCompanionWhenItIsNotInstalled() {
        listOf(true, false).forEach { helper ->
            val copy = backupCompanionCopy(installed = false, helper = helper)
            assertEquals(BackupCompanionCopy("", "", ""), copy)
            listOf(copy.row, copy.restoreWarning, copy.bundleSuffix).forEach { text ->
                assertTrue("must not name the Companion (helper=$helper): $text", "Companion" !in text)
            }
        }
    }

    @Test fun theCompanionLoginIsOfferedOnlyWithBothTheAppAndTheHelper() {
        val offered = backupCompanionCopy(installed = true, helper = true)
        assertTrue("the include-login checkbox belongs here", """id="bk-comp"""" in offered.row)
        assertTrue(offered.restoreWarning.isNotEmpty() && offered.bundleSuffix.isNotEmpty())

        // Installed but the helper is stale: explain why, and do not promise it in the bundle or warning.
        val explained = backupCompanionCopy(installed = true, helper = false)
        assertTrue("must explain the helper requirement", "needs the current ha-paneld helper" in explained.row)
        assertTrue("must not offer the checkbox", """id="bk-comp"""" !in explained.row)
        assertEquals("", explained.restoreWarning)
        assertEquals("", explained.bundleSuffix)
    }

    /** The card must gate on the app being installed, not only on helper capability. */
    @Test fun theCardIsRenderedWithTheInstalledStateNotJustHelperCapability() {
        assertTrue(
            "backupCardHtml must receive the installed check",
            "backupCardHtml(companionHelper, CompanionInstaller.installedPkg(appContext) != null)" in serverSource,
        )
    }

    /** The browser already tolerates an absent checkbox; keep it that way. */
    @Test fun theBrowserTreatsAnAbsentCompanionCheckboxAsOff() {
        assertTrue("include_companion must be null-safe", "comp && comp.checked" in script)
    }
}
