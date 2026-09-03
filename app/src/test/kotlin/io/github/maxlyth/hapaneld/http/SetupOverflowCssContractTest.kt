package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupOverflowCssContractTest {
    private val css = listOf(
        File("src/main/assets/info.css"),
        File("app/src/main/assets/info.css"),
        File("../app/src/main/assets/info.css"),
    ).first(File::isFile).readText()

    @Test fun `unbroken setup values wrap without changing banners outside the wizard`() {
        val selector = ".wiz-preview,.wiz .setup"
        val start = css.indexOf(selector)
        assertTrue("Setup overflow rule must exist", start >= 0)

        val rule = css.substring(start, css.indexOf('}', start))
        assertTrue("modern WebViews must wrap opaque values at any character", "overflow-wrap:anywhere" in rule)
        assertTrue("older WebViews need the break-word fallback", "word-break:break-word" in rule)
    }
}
