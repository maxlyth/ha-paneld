package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseIdentityContractTest {
    @Test fun rc3CandidateOwnsVersionCode665() {
        assertEquals("0.9.7-rc3", BuildConfig.VERSION_NAME)
        assertEquals(665, BuildConfig.VERSION_CODE)
    }
}
