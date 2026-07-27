package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileIdentityConfigUiContractTest {
    private val server = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
    private val configure = File("src/main/assets/configure.js").readText()

    @Test fun profileIdentitySurfacesOnlyAsPlaceholdersWithoutBecomingDurableConfig() {
        assertTrue(server.contains("\"manufacturer\" -> profile.manufacturer"))
        assertTrue(server.contains("\"model\" -> profile.model"))
        assertTrue(server.contains("val placeholder = hints[spec.key]?.let { \"auto (\$it)\" } ?: when (spec.key)"))
        assertTrue(!server.contains("\"prefill\":"))
        assertTrue(!configure.contains("f.prefill"))
    }

    @Test fun partialIdentityWritesPreserveTheOtherRawOverrideAndBlankMeansAuto() {
        assertTrue(server.contains("mfr ?: config.manufacturerRaw"))
        assertTrue(server.contains("mdl ?: config.modelRaw"))
        assertTrue(!server.contains("(mfr ?: config.manufacturer).ifEmpty"))
        assertTrue(!server.contains("(mdl ?: config.model).ifEmpty"))
    }
}
