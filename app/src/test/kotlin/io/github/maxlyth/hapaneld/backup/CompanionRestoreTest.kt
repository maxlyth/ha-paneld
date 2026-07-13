package io.github.maxlyth.hapaneld.backup

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionRestoreTest {
    private val pkg = "io.homeassistant.companion.android.minimal"
    private fun encoded(rel: String, value: String = "data") =
        CompanionRestore.EncodedFile(rel, Base64.getEncoder().encodeToString(value.toByteArray()))

    private fun validPlan(vararg files: CompanionRestore.EncodedFile): CompanionRestore.Plan =
        (CompanionRestore.plan(pkg, files.toList(), setOf(pkg)) as CompanionRestore.PlanResult.Valid).plan

    @Test fun completePlanIsDecodedAndOrderedBeforeExecution() {
        val result = CompanionRestore.plan(
            pkg,
            listOf(
                encoded("shared_prefs/session_0.xml", "session"),
                encoded("databases/HomeAssistantDB", "database"),
            ),
            setOf(pkg),
        ) as CompanionRestore.PlanResult.Valid
        assertEquals(listOf("databases/HomeAssistantDB", "shared_prefs/session_0.xml"), result.plan.files.map { it.relativePath })
        assertArrayEquals("database".toByteArray(), result.plan.files.first().bytes)
    }

    @Test fun packageMustBeSafeSupportedAndInstalled() {
        assertTrue(CompanionRestore.plan("io.homeassistant.companion.android;reboot", listOf(encoded("databases/HomeAssistantDB")), setOf("io.homeassistant.companion.android;reboot")) is CompanionRestore.PlanResult.Invalid)
        assertTrue(CompanionRestore.plan(pkg, listOf(encoded("databases/HomeAssistantDB")), emptySet()) is CompanionRestore.PlanResult.Invalid)
        assertTrue(CompanionRestore.plan("com.example.other", listOf(encoded("databases/HomeAssistantDB")), setOf(pkg)) is CompanionRestore.PlanResult.Invalid)
    }

    @Test fun everyFileMustBeAllowlistedUniqueAndDecodable() {
        assertTrue(CompanionRestore.plan(pkg, emptyList(), setOf(pkg)) is CompanionRestore.PlanResult.Invalid)
        assertTrue(CompanionRestore.plan(pkg, listOf(encoded("../shared_prefs/session_0.xml")), setOf(pkg)) is CompanionRestore.PlanResult.Invalid)
        assertTrue(CompanionRestore.plan(pkg, listOf(encoded("shared_prefs/other.xml")), setOf(pkg)) is CompanionRestore.PlanResult.Invalid)
        assertTrue(CompanionRestore.plan(pkg, listOf(encoded("databases/HomeAssistantDB"), encoded("databases/HomeAssistantDB")), setOf(pkg)) is CompanionRestore.PlanResult.Invalid)
        assertTrue(CompanionRestore.plan(pkg, listOf(CompanionRestore.EncodedFile("databases/HomeAssistantDB", "%%%")), setOf(pkg)) is CompanionRestore.PlanResult.Invalid)
        assertTrue(CompanionRestore.plan(pkg, listOf(encoded("databases/HomeAssistantDB", "")), setOf(pkg)) is CompanionRestore.PlanResult.Invalid)
    }

    private class FakeExecutor(
        private val failStage: String? = null,
        private val commitOk: Boolean = true,
        private val relaunchOk: Boolean = true,
    ) : CompanionRestore.Executor {
        val events = mutableListOf<String>()
        override fun inspectTarget(packageName: String): CompanionRestore.TargetInfo {
            events += "inspect"
            return CompanionRestore.TargetInfo("10042", "u:object_r:app_data_file:s0:c1,c2")
        }
        override fun forceStop(packageName: String): Boolean { events += "stop"; return true }
        override fun stage(packageName: String, file: CompanionRestore.FilePayload): Boolean {
            events += "stage:${file.relativePath}"
            return file.relativePath != failStage
        }
        override fun commit(plan: CompanionRestore.Plan, target: CompanionRestore.TargetInfo): Boolean {
            events += "commit"
            return commitOk
        }
        override fun discard(plan: CompanionRestore.Plan): Boolean { events += "discard"; return true }
        override fun relaunch(packageName: String): Boolean { events += "relaunch"; return relaunchOk }
    }

    @Test fun failedFileWriteCannotPartiallyCommit() {
        val plan = validPlan(encoded("databases/HomeAssistantDB"), encoded("shared_prefs/session_0.xml"))
        val executor = FakeExecutor(failStage = "shared_prefs/session_0.xml")
        val result = CompanionRestore.execute(plan, executor)
        assertFalse(result.ok)
        assertEquals(0, result.committedFiles)
        assertFalse("commit must not run after any failed required write", "commit" in executor.events)
        assertEquals("discard", executor.events[executor.events.indexOf("stage:shared_prefs/session_0.xml") + 1])
        assertEquals("relaunch", executor.events.last())
    }

    @Test fun commitFailureIsReportedAndRelaunchAttempted() {
        val executor = FakeExecutor(commitOk = false)
        val result = CompanionRestore.execute(validPlan(encoded("databases/HomeAssistantDB")), executor)
        assertFalse(result.ok)
        assertEquals(null, result.committedFiles)
        assertEquals(listOf("inspect", "stop", "stage:databases/HomeAssistantDB", "commit", "discard", "relaunch"), executor.events)
    }

    @Test fun successCountsOnlyCommittedFiles() {
        val executor = FakeExecutor()
        val result = CompanionRestore.execute(
            validPlan(encoded("databases/HomeAssistantDB"), encoded("shared_prefs/session_0.xml")),
            executor,
        )
        assertTrue(result.ok)
        assertEquals(2, result.committedFiles)
        assertTrue(result.relaunched)
    }

    @Test fun relaunchFailureDoesNotHideCommittedRestore() {
        val result = CompanionRestore.execute(validPlan(encoded("databases/HomeAssistantDB")), FakeExecutor(relaunchOk = false))
        assertFalse(result.ok)
        assertEquals(1, result.committedFiles)
        assertFalse(result.relaunched)
    }
}
