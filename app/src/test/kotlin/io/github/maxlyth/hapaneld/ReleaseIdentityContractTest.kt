package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseIdentityContractTest {
    @Test fun stableReleaseOwnsVersionCode527() {
        assertEquals("0.9.6", BuildConfig.VERSION_NAME)
        assertEquals(527, BuildConfig.VERSION_CODE)
    }
}
