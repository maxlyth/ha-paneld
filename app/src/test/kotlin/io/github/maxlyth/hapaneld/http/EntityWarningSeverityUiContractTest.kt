package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityWarningSeverityUiContractTest {
    private fun source(vararg candidates: String): String =
        candidates.map(::File).first(File::isFile).readText()

    private val server = source(
        "src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
        "app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
    )
    private val script = source("src/main/assets/entities.js", "app/src/main/assets/entities.js")
    private val css = source("src/main/assets/info.css", "app/src/main/assets/info.css")

    @Test fun `compatibility summary is an accessible live status`() {
        val page = server.substringAfter("private fun entitiesBody(strings: AppStrings)")
            .substringBefore("private fun entityTableHtml")

        assertTrue(page.contains("id=\"entity-issues-summary\" class=\"muted\" role=\"status\" aria-live=\"polite\""))
    }

    @Test fun `red is reserved for effective blockers`() {
        assertTrue(script.contains("disposition=issue.blocking?'blocking':(issue.ignored?'allowed':'advisory')"))
        assertTrue(css.contains(".entity-issue.blocking{border-left:4px solid #e05a48}"))
        assertTrue(css.contains(".entity-issue.advisory{border-left:4px solid #3d7fd0}"))
        assertTrue(css.contains(".entity-issue.advisory"))
        assertFalse(css.contains(".entity-issue.advisory .entity-issue-severity{background:var(--crit-bg)"))
    }

    @Test fun `advisory copy says discovery remains active`() {
        assertTrue(script.contains("tp('entities.issues.summary.notes'"))
        assertTrue(script.contains("t('entities.issue.severity.limited','Limited coverage')"))
        assertTrue(script.contains("t('entities.issue.severity.paused','Automatic updates paused')"))
        assertTrue(script.contains("t('entities.issue.severity.note','Compatibility note')"))
    }
}
