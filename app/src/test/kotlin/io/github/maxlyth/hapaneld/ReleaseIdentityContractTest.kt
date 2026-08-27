package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseIdentityContractTest {
    @Test fun rc3CandidateOwnsVersionCode617() {
        assertEquals("0.9.7-rc3", BuildConfig.VERSION_NAME)
        assertEquals(617, BuildConfig.VERSION_CODE)
    }
}
