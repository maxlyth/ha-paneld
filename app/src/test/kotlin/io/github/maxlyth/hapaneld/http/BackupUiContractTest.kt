package io.github.maxlyth.hapaneld.http

import java.io.File
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
}
