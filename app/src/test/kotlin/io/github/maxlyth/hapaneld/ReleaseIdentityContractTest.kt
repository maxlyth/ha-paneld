package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseIdentityContractTest {
    @Test fun rc3CandidateOwnsVersionCode661() {
        assertEquals("0.9.7-rc3", BuildConfig.VERSION_NAME)
        assertEquals(661, BuildConfig.VERSION_CODE)
    }
}
