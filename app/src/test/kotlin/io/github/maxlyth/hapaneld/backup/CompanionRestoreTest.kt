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
        private val prepareOk: Boolean = true,
        private val preparedSizes: Map<String, Long>? = null,
        private val repairedInternalUrls: Int = 0,
        private val commitOk: Boolean = true,
        private val relaunchOk: Boolean = true,
    ) : CompanionRestore.Executor {
        val events = mutableListOf<String>()
        val stagedSizes = mutableMapOf<String, Int>()
        override fun inspectTarget(packageName: String): CompanionRestore.TargetInfo {
            events += "inspect"
            return CompanionRestore.TargetInfo("10042", "u:object_r:app_data_file:s0:c1,c2")
        }
        override fun prepare(plan: CompanionRestore.Plan): CompanionRestore.Preparation? {
            events += "prepare"
            if (!prepareOk) return null
            val files = plan.files.map { file ->
                val size = preparedSizes?.get(file.relativePath) ?: file.bytes.size.toLong()
                file.copy(bytes = ByteArray(size.toInt().coerceAtLeast(0)))
            }
            if (preparedSizes != null) {
                for ((path, size) in preparedSizes) {
                    if (path !in files.map { it.relativePath }) {
                        return CompanionRestore.Preparation(
                            files + CompanionRestore.FilePayload(path, ByteArray(size.toInt().coerceAtLeast(0))),
                            repairedInternalUrls,
                        )
                    }
                }
            }
            return CompanionRestore.Preparation(files, repairedInternalUrls)
        }
        override fun forceStop(packageName: String): Boolean { events += "stop"; return true }
        override fun stage(packageName: String, file: CompanionRestore.FilePayload): Boolean {
            events += "stage:${file.relativePath}"
            stagedSizes[file.relativePath] = file.bytes.size
            return file.relativePath != failStage
        }
        override fun commit(
            plan: CompanionRestore.Plan,
            target: CompanionRestore.TargetInfo,
            preparation: CompanionRestore.Preparation,
        ): Boolean {
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

    @Test fun preparedDatabaseBytesAreStagedBeforeTheTargetIsCommitted() {
        val original = encoded("databases/HomeAssistantDB", "tiny")
        val executor = FakeExecutor(preparedSizes = mapOf("databases/HomeAssistantDB" to 8192))

        val result = CompanionRestore.execute(validPlan(original), executor)

        assertTrue(result.ok)
        assertEquals(
            listOf("inspect", "prepare", "stop", "stage:databases/HomeAssistantDB", "commit", "relaunch"),
            executor.events,
        )
        assertEquals(8192, executor.stagedSizes["databases/HomeAssistantDB"])
    }

    @Test fun commitFailureIsReportedAndRelaunchAttempted() {
        val executor = FakeExecutor(commitOk = false)
        val result = CompanionRestore.execute(validPlan(encoded("databases/HomeAssistantDB")), executor)
        assertFalse(result.ok)
        assertEquals(null, result.committedFiles)
        assertEquals(listOf("inspect", "prepare", "stop", "stage:databases/HomeAssistantDB", "commit", "discard", "relaunch"), executor.events)
    }

    @Test fun failedStagedDatabasePreparationCannotReachLiveCommit() {
        val executor = FakeExecutor(prepareOk = false)
        val result = CompanionRestore.execute(validPlan(encoded("databases/HomeAssistantDB")), executor)
        assertFalse(result.ok)
        assertEquals(0, result.committedFiles)
        assertFalse("commit must not run after staged validation fails", "commit" in executor.events)
        assertEquals(
            listOf("inspect", "prepare", "discard"),
            executor.events,
        )
    }

    @Test fun malformedPreparationContractCannotReachLiveCommit() {
        val executor = FakeExecutor(
            preparedSizes = mapOf("databases/HomeAssistantDB" to 8192, "shared_prefs/other.xml" to 4),
        )
        val result = CompanionRestore.execute(validPlan(encoded("databases/HomeAssistantDB")), executor)
        assertFalse(result.ok)
        assertFalse("commit must not run with an unexpected staged path", "commit" in executor.events)
    }

    @Test fun successCountsOnlyCommittedFiles() {
        val executor = FakeExecutor(
            preparedSizes = mapOf("databases/HomeAssistantDB" to 8192, "shared_prefs/session_0.xml" to 7),
            repairedInternalUrls = 1,
        )
        val result = CompanionRestore.execute(
            validPlan(encoded("databases/HomeAssistantDB"), encoded("shared_prefs/session_0.xml")),
            executor,
        )
        assertTrue(result.ok)
        assertEquals(2, result.committedFiles)
        assertTrue(result.relaunched)
        assertEquals(1, result.repairedInternalUrls)
    }

    @Test fun relaunchFailureDoesNotHideCommittedRestore() {
        val result = CompanionRestore.execute(validPlan(encoded("databases/HomeAssistantDB")), FakeExecutor(relaunchOk = false))
        assertFalse(result.ok)
        assertEquals(1, result.committedFiles)
        assertFalse(result.relaunched)
    }
}
