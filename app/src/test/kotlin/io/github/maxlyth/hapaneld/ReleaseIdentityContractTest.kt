package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseIdentityContractTest {
    @Test fun rc5CandidateOwnsVersionCode523() {
        assertEquals("0.9.6-rc5", BuildConfig.VERSION_NAME)
        assertEquals(523, BuildConfig.VERSION_CODE)
    }
}
