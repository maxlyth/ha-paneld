package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseIdentityContractTest {
    @Test fun stableReleaseOwnsVersionCode686() {
        assertEquals("0.9.7", BuildConfig.VERSION_NAME)
        assertEquals(686, BuildConfig.VERSION_CODE)
    }
}
