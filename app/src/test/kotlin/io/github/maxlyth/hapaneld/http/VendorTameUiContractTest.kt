package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorTameUiContractTest {
    @Test fun freeTextTameStaysDisabledUntilThePackageGrammarIsValid() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()

        assertTrue(source.contains("id=\"tame-pkg\""))
        assertTrue(source.contains("id=\"tame-package-submit\" type=\"submit\""))
        assertTrue(source.contains("label for=\"tame-pkg\""))
        assertTrue(source.contains("required pattern=\"[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*\""))
        assertTrue(source.contains("aria-describedby=\"tame-pkg-hint\""))
        assertTrue(source.contains("oninput=\"updateTamePackageSubmit()\""))
        assertTrue(source.contains("function updateTamePackageSubmit()"))
        assertTrue(source.contains("button.disabled=input.disabled||!input.checkValidity()"))
        val installJs = File("src/main/assets/install.js").readText()
        assertTrue(installJs.contains("submitter.id === 'tame-package-submit'"))
        assertTrue(installJs.contains("updateTamePackageSubmit();"))
    }
}
