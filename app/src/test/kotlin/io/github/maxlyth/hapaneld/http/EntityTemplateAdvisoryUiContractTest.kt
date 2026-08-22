package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contracts for what the Entities page says about a template selector it refuses to read (issue #113):
 * that allowing the check adds nothing, and where the entities actually come from instead.
 */
class EntityTemplateAdvisoryUiContractTest {
    private fun source(vararg candidates: String): String =
        candidates.map(::File).first(File::isFile).readText()

    private val server = source(
        "src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
        "app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
    )
    private val script = source("src/main/assets/entities.js", "app/src/main/assets/entities.js")
    private val lint = source(
        "src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/DashboardConfigurationLint.kt",
        "app/src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/DashboardConfigurationLint.kt",
    )

    @Test fun `the confirmation says plainly that continuing adds nothing`() {
        val handler = script.substringAfter("button.textContent=issue.ignored?'Re-enable safety check'")
            .substringBefore("issuesList.appendChild(row)")

        assertTrue(handler.contains("This adds no entities to the subscription."))
        assertTrue(handler.contains("until you pin those entities by hand"))
    }

    @Test fun `the allow note and the search route are offered only where allow is`() {
        // Both are built into the row markup and gated on the same predicate as the toggle, so a row
        // that cannot be allowed never claims anything about allowing.
        assertTrue(
            script.contains(
                "var allowable=!!(issue.fingerprint&&issue.ignorable!==false&&(issue.blocking||issue.ignored))",
            ),
        )
        assertEquals(1, Regex("Allowing this check never adds entities").findAll(script).count())
        assertEquals(1, Regex("allowable\\?'<div class=\"entity-issue-allow-note\"").findAll(script).count())
        assertTrue(script.contains("if(allowable){"))
        // Delegated, so a poll that rebuilds the issue list does not need rebinding and the row's one
        // appended child stays the toggle the severity contracts assert on.
        assertTrue(script.contains("if(!t.closest('.entity-issue-search'))return;focusCatalogSearch()"))
    }

    @Test fun `the template advisory distinguishes a returned entity from one only read`() {
        val reason = lint.substringAfter("TEMPLATE_SELECTOR_REASON =").substringBefore("private const val TEMPLATE_SELECTOR_RECOMMENDATION")
        val recommendation = lint.substringAfter("TEMPLATE_SELECTOR_RECOMMENDATION =").substringBefore("private const val MAX_PATTERN_LENGTH")

        assertTrue(reason, reason.contains("does not evaluate templates"))
        assertTrue(reason, reason.contains("which entities this filter returns"))
        assertTrue(reason, reason.contains("delivered outside this filter"))
        assertTrue(reason, reason.contains("they need nothing"))
        assertTrue(recommendation, recommendation.contains("pin only the entities the template returns"))
        assertTrue(
            "the advisory must be attached to the filter.template finding",
            lint.contains("\"Unbounded template entity selector\", null, SELECTOR_ENTITY_LIMIT,\n" +
                "                    reason = TEMPLATE_SELECTOR_REASON,\n" +
                "                    recommendation = TEMPLATE_SELECTOR_RECOMMENDATION,"),
        )
    }

    @Test fun `an entity a template only reads is never presented as something to pin`() {
        // EntityFilterProtocol mutates only subscribe_entities, so a render_template subscription reaches
        // the panel unfiltered and those entities need nothing. Advising a pin for them would grow the
        // subscription the entity filter exists to shrink, so no shipped surface may suggest it.
        val page = server.substringAfter("private fun entitiesBody()")
            .substringBefore("private fun ghLink")
        val filterProtocol = source(
            "src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityFilterProtocol.kt",
            "app/src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityFilterProtocol.kt",
        )
        assertTrue(
            "the filter must still touch only subscribe_entities for this advice to be true",
            filterProtocol.contains("""if (obj.optString("type") != "subscribe_entities") return Mutation(text, false)"""),
        )
        listOf(lint, script, page).forEach { surface ->
            assertFalse(surface.contains("those it only tests"))
            assertFalse(surface.contains("is a dependency too"))
            assertFalse(surface.contains("and the entities it only reads"))
        }
    }

    @Test fun `server-rendered templates are not presented as something exercising can reveal`() {
        val page = server.substringAfter("private fun entitiesBody()")
            .substringBefore("private fun entityTableHtml")
        val dynamic = page.substringAfter("Dynamic expressions to exercise").substringBefore("entity-dynamic-list")

        assertTrue(dynamic, dynamic.contains("Templates that Home Assistant renders on the server"))
        assertTrue(dynamic, dynamic.contains("exercising never reveals the entities they read"))
        // And says so without turning that into a chore: those reads arrive outside the filter, so the
        // only thing worth adding by hand is what such a template produces as a card reference.
        assertTrue(dynamic, dynamic.contains("a separate subscription this filter does not touch"))
        assertTrue(dynamic, dynamic.contains("those entities need nothing"))
        assertTrue(dynamic, dynamic.contains("produces as a card reference"))
    }

    @Test fun `the lint never reads the template it refuses`() {
        // The advisory copy is constant text. Nothing derived from the template body may be spliced into
        // it, because that is the same act as evaluating it.
        val branch = lint.substringAfter("if (hasTemplate) {").substringBefore("fun reportUnsafeGeneratedRows")
        assertFalse(branch, branch.contains("template)"))
        assertFalse(branch, branch.contains("\$template"))
        assertTrue(lint.contains("private const val TEMPLATE_SELECTOR_REASON ="))
    }

    @Test fun `the template advisory row renders its note, route and nothing from the template`() {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val fixture = listOf(
            File(working, "app/src/test/js/entity-template-advisory-test.mjs"),
            File(working, "src/test/js/entity-template-advisory-test.mjs"),
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
        assertTrue(output, output.contains("entity template advisory cases passed"))
    }
}
