package io.github.maxlyth.hapaneld.testsupport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Locks the shared locator's own behaviour, so the ~24 tests that depend on it inherit one proven
 * cwd-fallback rule instead of each carrying its own copy. It reads real shipped files, so it doubles
 * as a tripwire that the app source/asset layout still resolves from the test working directory.
 */
class TestSourcesTest {
    @Test fun resolvesProductionKotlinFromEitherWorkingDirectory() {
        val server = TestSources.kotlin("http/PaneldServer.kt")
        assertTrue("PaneldServer.kt must resolve", server.isFile)
        assertTrue("resolved file must be the real source", server.readText().contains("class PaneldServer"))
    }

    @Test fun resolvesShippedAssetsAndAssetDir() {
        assertTrue("configure.js must resolve", TestSources.asset("configure.js").isFile)
        val dir = TestSources.assetDir()
        assertTrue("asset dir must resolve", dir.isDirectory)
        assertTrue("asset dir must contain shipped assets", dir.resolve("openapi.json").isFile)
    }

    @Test fun requiredLookupErrorsWhenAbsentAndOptionalReturnsNull() {
        assertEquals(null, TestSources.appFileOrNull("src/main/kotlin/does/not/Exist.kt"))
        try {
            TestSources.kotlin("does/not/Exist.kt")
            fail("required lookup of a missing file must throw")
        } catch (e: IllegalStateException) {
            assertTrue("error must report the working directory for debugging", e.message!!.contains("cwd="))
        }
    }
}
