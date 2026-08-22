package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contracts for the Entities-page catalogue search: the feedback the reporter of issue #114 could not
 * find, and the boundaries on the one-shot reveal that make an automatic scroll acceptable at all.
 */
class EntitySearchFeedbackUiContractTest {
    private fun source(vararg candidates: String): String =
        candidates.map(::File).first(File::isFile).readText()

    private val server = source(
        "src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
        "app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
    )
    private val script = source("src/main/assets/entities.js", "app/src/main/assets/entities.js")
    private val css = source("src/main/assets/info.css", "app/src/main/assets/info.css")

    @Test fun `the search box is paired with an adjacent live status line`() {
        val page = server.substringAfter("private fun entitiesBody()")
            .substringBefore("private fun entityTableHtml")
        val searchRow = page.substringAfter("<div class=\"entity-search-row\">").substringBefore("</div>\n")

        assertTrue(searchRow.contains("<label class=\"sr-only\" for=\"entity-search\">"))
        assertTrue(searchRow.contains("aria-describedby=\"entity-search-status\""))
        assertTrue(
            page.contains(
                "<div id=\"entity-search-status\" class=\"entity-search-status muted\" " +
                    "role=\"status\" aria-live=\"polite\"></div>",
            ),
        )
        // The live region ships in the document rather than being inserted when there is something to
        // say: a region created at announce time is routinely missed by assistive technology.
        assertTrue(page.indexOf("id=\"entity-search-status\"") < page.indexOf("entityTableHtml"))
    }

    @Test fun `each section carries the short label the counts line uses`() {
        val page = server.substringAfter("private fun entitiesBody()")
        assertTrue(server.contains("data-table=\"\$id\" data-short=\"\$shortTitle\""))
        listOf("\"Current\"", "\"Suggested\"", "\"Stale or noisy\"").forEach {
            assertTrue("missing short label $it", page.substringBefore("private fun ghLink").contains(it))
        }
        assertTrue(script.contains("card.dataset.short||card.dataset.table"))
    }

    @Test fun `the status line reserves its height so appearing text shifts nothing`() {
        val rule = css.substringAfter(".entity-search-status{").substringBefore("}")
        assertTrue(rule, rule.contains("min-height:"))
    }

    @Test fun `only a typed query arms the reveal`() {
        // beginQuery is the single dispatcher, and `reveal` is the only thing that permits a scroll. If
        // it appears anywhere except the search wrapper, some background repaint can move the page.
        assertEquals(1, Regex("reveal:true").findAll(script).count())
        assertTrue(script.contains("function searchAll(){return beginQuery({reset:true,reveal:true})}"))
        assertTrue(script.contains("function loadAll(){return beginQuery({})}"))
        assertTrue(script.contains("function resetAll(){return beginQuery({reset:true})}"))
        assertTrue(script.contains("timer=setTimeout(searchAll,250)"))
        // Every other repaint route still goes through the unarmed wrappers.
        assertTrue(script.contains("if(!document.hidden&&!document.querySelector('.entity-list select:focus'))loadAll()"))
    }

    @Test fun `counts are rendered only from one fully settled generation`() {
        // A section reports once, into a results array replaced on every dispatch, and the line is only
        // written when every section has answered.
        assertTrue(script.contains("if(!generation||!generationResults||generationResults[index])return"))
        assertTrue(script.contains("if(++generationSettled<tables.length)return"))
        // The per-table request token is what discards a superseded answer before it can claim a slot.
        assertTrue(script.contains("if(request!==state.request)return;state.items=d.items||[]"))
        assertTrue(script.contains("if(request!==state.request)return;msg.textContent='Entity list unavailable'"))
        // Sorting or paging supersedes that section's pending request, so it adopts the pending
        // generation; otherwise the section could never answer and the line would stay on "Searching…".
        assertEquals(3, Regex("load\\(activeGeneration\\)").findAll(script).count())
    }

    @Test fun `the reveal uses only interfaces WebKit implements`() {
        // No WebKit build is installed here, so Safari support is held as a source contract instead of a
        // second live browser: the reveal may use nothing outside the baseline WebKit ships, and it must
        // degrade rather than depend on the options object being understood.
        val chromeOnly = listOf(
            "scrollIntoViewIfNeeded", "requestIdleCallback", "scrollend", "checkVisibility",
            "startViewTransition", "CSS.highlights", "showPicker", "navigator.scheduling",
            "webkitRequestAnimationFrame", "IntersectionObserver", "ResizeObserver",
        )
        chromeOnly.forEach { assertTrue("entities.js must not depend on $it", !script.contains(it)) }
        assertTrue(script.contains("node.scrollIntoView({behavior:reduced?'auto':'smooth',block:'start'})"))
        assertTrue(script.contains("catch(e){node.scrollIntoView(true)}"))
        assertTrue(script.contains("window.matchMedia('(prefers-reduced-motion: reduce)')"))
    }

    @Test fun `search feedback and the never-scroll routes behave as specified`() {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val fixture = listOf(
            File(working, "app/src/test/js/entity-search-feedback-test.mjs"),
            File(working, "src/test/js/entity-search-feedback-test.mjs"),
        ).first(File::isFile)
        val asset = listOf(
            File(working, "app/src/main/assets/entities.js"),
            File(working, "src/main/assets/entities.js"),
        ).first(File::isFile)
        val process = ProcessBuilder("node", fixture.absolutePath, asset.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(output, 0, process.waitFor())
        assertTrue(output, output.contains("entity search feedback cases passed"))
    }
}
