package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseIdentityContractTest {
    @Test fun rc3IntegrationWaveOwnsVersionCode513() {
        assertEquals("0.9.6-rc3", BuildConfig.VERSION_NAME)
        assertEquals(513, BuildConfig.VERSION_CODE)
    }
}
