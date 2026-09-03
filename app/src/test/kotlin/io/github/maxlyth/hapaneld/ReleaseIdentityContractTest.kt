package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseIdentityContractTest {
    @Test fun stableReleaseOwnsVersionCode688() {
        assertEquals("0.9.7", BuildConfig.VERSION_NAME)
        assertEquals(688, BuildConfig.VERSION_CODE)
    }
}
