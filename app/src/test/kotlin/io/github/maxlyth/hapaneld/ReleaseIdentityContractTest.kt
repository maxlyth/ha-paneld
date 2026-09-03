package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseIdentityContractTest {
    @Test fun stableReleaseOwnsVersionCode687() {
        assertEquals("0.9.7", BuildConfig.VERSION_NAME)
        assertEquals(687, BuildConfig.VERSION_CODE)
    }
}
