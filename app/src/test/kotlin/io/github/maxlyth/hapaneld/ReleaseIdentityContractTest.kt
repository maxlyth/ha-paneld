package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseIdentityContractTest {
    @Test fun rc2CandidateOwnsVersionCode596() {
        assertEquals("0.9.7-rc2", BuildConfig.VERSION_NAME)
        assertEquals(596, BuildConfig.VERSION_CODE)
    }
}
