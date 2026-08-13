package io.github.maxlyth.hapaneld.dashboard

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A manual pin or exclusion is the operator's own work and is never withdrawn automatically — not by a
 * dashboard change, and not because the dashboard stopped using it. The only honest way to close that
 * loop is to SHOW the ones that no longer earn their place, so they can be unpinned deliberately.
 *
 * The predicates run against Android SQLite and have no JVM unit-test route, so this pins the query
 * shape and the promise the page makes about it. Both are mutation-covered in `tools/issue-90/`.
 */
class PinnedEntityReviewContractTest {
    private val store = File("src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityCatalogStore.kt").readText()
    private val server = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()

    private val reviewPredicate = store.lineSequence()
        .first { it.trimStart().startsWith("\"review\" -> where +=") }

    @Test fun `a pin the dashboard no longer uses is surfaced for review`() {
        // Pinned, and neither referenced by the dashboard's configuration nor reached at runtime: a
        // subscription being paid for with nothing asking for it.
        assertTrue(
            "the review filter must surface a pin nothing references: $reviewPredicate",
            reviewPredicate.contains("coalesce(m.pinned,0)=1") &&
                reviewPredicate.contains("coalesce(m.referenced_by_config,0)=0") &&
                reviewPredicate.contains("coalesce(m.referenced_at_runtime,0)=0"),
        )
    }

    @Test fun `an entity missing from Home Assistant is still surfaced whether or not it is pinned`() {
        // The missing branch carries no pinned test, so a pin to something deleted in Home Assistant
        // shows up rather than disappearing silently.
        assertTrue(reviewPredicate.contains("e.missing_streak>0"))
    }

    @Test fun `the page promises that nothing is removed automatically`() {
        // The table is the ONLY place a stale pin becomes visible, so the copy has to say both that it
        // is review-only and where to act. If this drifts, the feature silently becomes a dead end.
        val note = server.lineSequence().first { it.contains("Stale or noisy entities") }
        assertTrue("the note must say a manual override is never auto-removed: $note",
            note.contains("never removed automatically") || note.contains("is never removed automatically"))
        assertTrue("the note must name the pinned-but-unused case: $note", note.contains("pinned by hand"))
    }
}
