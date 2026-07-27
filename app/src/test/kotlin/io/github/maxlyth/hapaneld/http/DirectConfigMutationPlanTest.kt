package io.github.maxlyth.hapaneld.http

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectConfigMutationPlanTest {
    @Test fun `touch change excludes unchanged navbar and auto sleep`() {
        val plan = planDirectConfigMutation(
            posted = linkedMapOf(
                "auto_sleep" to "true",
                "navbar_mode" to "Swipe reveal",
                "touch_sound" to "true",
            ),
            before = mapOf(
                "auto_sleep" to "true",
                "navbar_mode" to "Swipe reveal",
                "touch_sound" to "false",
            ),
        )

        assertEquals(setOf("touch_sound"), plan.changedKeys)
        assertEquals(listOf("touch_sound" to "true"), plan.changedLive)
        assertFalse(plan.requiresReconfigure)
    }

    @Test fun `unchanged auto sleep is a true no-op with no broad reconfigure`() {
        val plan = planDirectConfigMutation(
            posted = mapOf("auto_sleep" to "false"),
            before = mapOf("auto_sleep" to "false"),
        )

        assertTrue(plan.isNoOp)
        assertTrue(plan.changedLive.isEmpty())
        assertFalse(plan.requiresReconfigure)
    }

    @Test fun `ordinary or exposure change requests reconfigure while local live change does not`() {
        assertTrue(planDirectConfigMutation(
            posted = mapOf("friendly_name" to "Kitchen"),
            before = mapOf("friendly_name" to "Panel"),
        ).requiresReconfigure)
        assertTrue(planDirectConfigMutation(
            posted = mapOf("ha_expose_touch_sound" to "false"),
            before = mapOf("ha_expose_touch_sound" to "true"),
        ).requiresReconfigure)
        assertFalse(planDirectConfigMutation(
            posted = mapOf("touch_sound" to "true"),
            before = mapOf("touch_sound" to "false"),
        ).requiresReconfigure)
    }

    @Test fun `secret placeholder and canonical dashboard path are unchanged`() {
        val plan = planDirectConfigMutation(
            posted = mapOf("ha_token" to "", "home_dashboard" to "/lovelace/home/"),
            before = mapOf("ha_token" to "secret", "home_dashboard" to "lovelace/home"),
        )

        assertTrue(plan.isNoOp)
    }

    @Test fun `pending desired is idempotent and can be explicitly cancelled`() {
        val desiredBaseline = mapOf("touch_sound" to "true")
        assertTrue(planDirectConfigMutation(
            posted = mapOf("touch_sound" to "true"),
            before = desiredBaseline,
        ).isNoOp)
        assertEquals(
            listOf("touch_sound" to "false"),
            planDirectConfigMutation(
                posted = mapOf("touch_sound" to "false"),
                before = desiredBaseline,
            ).changedLive,
        )
    }

    @Test fun `complete effective baseline keeps transient and untouched hardware values no-op`() {
        val plan = planDirectConfigMutation(
            posted = mapOf("cpu_governor" to "schedutil", "zigbee_router" to "false"),
            before = mapOf("cpu_governor" to "schedutil", "zigbee_router" to "false"),
        )
        assertTrue(plan.isNoOp)
    }

    @Test fun `json API and html form negotiation are deterministic`() {
        assertTrue(configMutationWantsJson("application/json", "application/x-www-form-urlencoded"))
        assertTrue(configMutationWantsJson("*/*", "application/json; charset=utf-8"))
        assertFalse(configMutationWantsJson("text/html", "application/x-www-form-urlencoded"))
        assertEquals(
            "<!doctype html><meta charset=utf-8><meta http-equiv=refresh content='2;url=/configure'>" +
                "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
                "Saved &amp; pending &lt;touch&gt;</body>",
            configMutationHtml("Saved & pending <touch>"),
        )
    }
}
