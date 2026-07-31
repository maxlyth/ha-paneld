package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseIdentityContractTest {
    @Test fun rc5CandidateOwnsVersionCode524() {
        assertEquals("0.9.6-rc5", BuildConfig.VERSION_NAME)
        assertEquals(524, BuildConfig.VERSION_CODE)
    }
}
