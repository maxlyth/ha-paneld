package io.github.maxlyth.hapaneld.dashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityCatalogIssuePersistenceTest {
    @Test fun ignoredIssuesRemainVisibleButStopBlocking() {
        val fingerprint = "0123456789abcdef"
        val raw = JSONArray().put(
            JSONObject().put("fingerprint", fingerprint).put("blocking", true).put("severity", "error"),
        )

        val effective = EntityCatalogIssuePersistence.applyIgnores(raw, setOf(fingerprint))
        val issue = JSONArray(effective).getJSONObject(0)

        assertTrue(issue.getBoolean("ignored"))
        assertTrue(issue.getBoolean("would_block"))
        assertFalse(issue.getBoolean("blocking"))
        assertEquals("warning", issue.getString("severity"))
        assertEquals(1 to 0, EntityCatalogIssuePersistence.counts(effective))
        assertEquals(1, EntityCatalogIssuePersistence.ignoredCount(effective))
    }
    @Test fun issueGroupsAndSourcesAreBounded() {
        val issues = List(EntityCatalogIssuePersistence.MAX_ISSUE_GROUPS + 10) { index ->
            JSONObject()
                .put("id", "issue-$index")
                .put("blocking", index % 2 == 0)
                .put("sources", JSONArray(List(EntityCatalogIssuePersistence.MAX_SOURCES_PER_GROUP + 5) { "cards[$it]" }))
        }

        val persisted = JSONArray(EntityCatalogIssuePersistence.boundedJson(issues))

        assertEquals(EntityCatalogIssuePersistence.MAX_ISSUE_GROUPS, persisted.length())
        assertEquals(
            EntityCatalogIssuePersistence.MAX_SOURCES_PER_GROUP,
            persisted.getJSONObject(0).getJSONArray("sources").length(),
        )
        assertEquals(64 to 32, EntityCatalogIssuePersistence.counts(persisted.toString()))
    }

    @Test fun payloadAndIndividualValuesAreBounded() {
        val issues = List(EntityCatalogIssuePersistence.MAX_ISSUE_GROUPS) { index ->
            JSONObject()
                .put("id", "issue-$index")
                .put("blocking", true)
                .put("message", "x".repeat(20_000))
                .put("details", JSONArray(List(16) { "y".repeat(20_000) }))
        }

        val json = EntityCatalogIssuePersistence.boundedJson(issues)
        val bytes = json.toByteArray(Charsets.UTF_8).size
        val persisted = JSONArray(json)

        assertTrue(bytes <= EntityCatalogIssuePersistence.MAX_PAYLOAD_BYTES)
        assertTrue(persisted.length() in 1..EntityCatalogIssuePersistence.MAX_ISSUE_GROUPS)
        assertTrue(persisted.getJSONObject(0).getString("message").length <= 500)
    }

    @Test fun malformedOrNonObjectStoredPayloadIsSafelyReduced() {
        assertEquals("[]", EntityCatalogIssuePersistence.boundExistingJson("not-json"))
        assertEquals(0 to 0, EntityCatalogIssuePersistence.counts("not-json"))

        val bounded = JSONArray(EntityCatalogIssuePersistence.boundExistingJson("[1,{\"blocking\":true},null]"))
        assertEquals(1, bounded.length())
        assertTrue(bounded.getJSONObject(0).getBoolean("blocking"))
    }
}
