package io.github.maxlyth.hapaneld.dashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class EntityLearningIssueVisibilityTest {
    @Test fun automaticRuntimePromotionOnlyShowsFindingsThatPreventedAutomaticAddition() {
        val stored = JSONArray()
            .put(JSONObject().put("type", "runtime_coverage").put("blocking", false).put("ignored", false))
            .put(JSONObject().put("type", "compatibility_gap").put("blocking", false).put("ignored", false))
            .put(JSONObject().put("type", "unbounded_selector").put("blocking", true).put("ignored", false))
            .put(JSONObject().put("type", "broad_selector").put("blocking", false).put("would_block", true).put("ignored", true))
            .toString()

        val automatic = JSONObject(visibleDashboardIssuesJson(stored, showAdvisories = false))
        assertEquals(listOf("unbounded_selector", "broad_selector"), automatic.types())
        assertEquals(2, automatic.getInt("dashboard_issue_count"))
        assertEquals(1, automatic.getInt("blocking_issue_count"))
        assertEquals(1, automatic.getInt("ignored_issue_count"))

        val manual = JSONObject(visibleDashboardIssuesJson(stored, showAdvisories = true))
        assertEquals(
            listOf("unbounded_selector", "broad_selector", "runtime_coverage", "compatibility_gap"),
            manual.types(),
        )
        assertEquals(4, manual.getInt("dashboard_issue_count"))
        assertEquals(1, manual.getInt("blocking_issue_count"))
        assertEquals(1, manual.getInt("ignored_issue_count"))
    }

    private fun JSONObject.types(): List<String> = getJSONArray("items").let { items ->
        List(items.length()) { index -> items.getJSONObject(index).getString("type") }
    }
}
