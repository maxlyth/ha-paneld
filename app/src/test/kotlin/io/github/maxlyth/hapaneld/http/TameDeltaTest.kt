package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TameDeltaTest {
    @Test fun computesOnlyRequiredPostCommitPackageActions() {
        val delta = TameDelta.between(
            old = setOf("vendor.keep", "vendor.remove"),
            next = setOf("vendor.keep", "vendor.add"),
        )

        assertEquals(setOf("vendor.add"), delta.add)
        assertEquals(setOf("vendor.remove"), delta.remove)
    }

    @Test fun identicalBlocklistsNeedNoPostCommitWork() {
        assertTrue(TameDelta.between(setOf("vendor.keep"), setOf("vendor.keep")).isEmpty)
    }
}
