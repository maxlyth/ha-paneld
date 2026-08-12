package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseIdentityContractTest {
    @Test fun rc1CandidateOwnsVersionCode556() {
        assertEquals("0.9.7-rc1", BuildConfig.VERSION_NAME)
        assertEquals(556, BuildConfig.VERSION_CODE)
    }
}
