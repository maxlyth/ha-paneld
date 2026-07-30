package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseIdentityContractTest {
    @Test fun rc4IntegrationWaveOwnsVersionCode517() {
        assertEquals("0.9.6-rc3", BuildConfig.VERSION_NAME)
        assertEquals(517, BuildConfig.VERSION_CODE)
    }
}
