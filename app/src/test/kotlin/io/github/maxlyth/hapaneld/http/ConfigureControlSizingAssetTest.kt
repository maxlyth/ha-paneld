package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigureControlSizingAssetTest {
    @Test fun `configure text fields and dropdowns share one fixed control height`() {
        val css = listOf(
            File("src/main/assets/info.css"),
            File("app/src/main/assets/info.css"),
            File("../app/src/main/assets/info.css"),
        ).first(File::isFile).readText()

        val controlRule = ".frow input[type=text],.frow input[type=password],.frow input[type=number],.frow select"
        val ruleStart = css.indexOf(controlRule)
        assertTrue("Configure control rule must exist", ruleStart >= 0)
        val rule = css.substring(ruleStart, css.indexOf('}', ruleStart))
        assertTrue("Configure controls must use one border-box height", "box-sizing:border-box;height:34px" in rule)
    }
}
