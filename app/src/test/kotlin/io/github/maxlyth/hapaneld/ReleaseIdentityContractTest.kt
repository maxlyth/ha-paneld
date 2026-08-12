package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseIdentityContractTest {
    @Test fun rc1CandidateOwnsVersionCode557() {
        assertEquals("0.9.7-rc1", BuildConfig.VERSION_NAME)
        assertEquals(557, BuildConfig.VERSION_CODE)
    }
}
