package io.github.maxlyth.hapaneld.backup

import io.github.maxlyth.hapaneld.util.AndroidInput
import java.util.Base64

/** Pure validation and execution boundary for a destructive HA Companion data restore. */
object CompanionRestore {
    const val DATABASE_FILE = "databases/HomeAssistantDB"

    val ALLOWED_FILES = listOf(
        DATABASE_FILE,
        "shared_prefs/session_0.xml",
        "shared_prefs/integration_0.xml",
    )

    data class EncodedFile(val relativePath: String, val base64: String)
    data class FilePayload(val relativePath: String, val bytes: ByteArray)
    data class Plan(val packageName: String, val files: List<FilePayload>)

    sealed class PlanResult {
        data class Valid(val plan: Plan) : PlanResult()
        data class Invalid(val reason: String) : PlanResult()
    }

    /** Validate and decode the complete plan before its caller commits config or stops the app. */
    fun plan(packageName: String, files: List<EncodedFile>, installedPackages: Set<String>): PlanResult {
        if (!AndroidInput.isPackage(packageName) || packageName !in installedPackages) {
            return PlanResult.Invalid("Companion package is not a supported installed package")
        }
        if (files.isEmpty()) return PlanResult.Invalid("Companion restore contains no files")

        val decoded = LinkedHashMap<String, ByteArray>()
        for (file in files) {
            if (file.relativePath !in ALLOWED_FILES) {
                return PlanResult.Invalid("Unsupported Companion file: ${file.relativePath}")
            }
            if (file.relativePath in decoded) {
                return PlanResult.Invalid("Duplicate Companion file: ${file.relativePath}")
            }
            val bytes = runCatching { Base64.getDecoder().decode(file.base64) }.getOrNull()
                ?: return PlanResult.Invalid("Invalid base64 for Companion file: ${file.relativePath}")
            if (bytes.isEmpty()) return PlanResult.Invalid("Empty Companion file: ${file.relativePath}")
            decoded[file.relativePath] = bytes
        }
        val ordered = ALLOWED_FILES.mapNotNull { rel -> decoded[rel]?.let { FilePayload(rel, it) } }
        return PlanResult.Valid(Plan(packageName, ordered))
    }

    data class TargetInfo(val uid: String, val selinuxContext: String)

    /** Payloads after bounded local SQLite validation and any approved preparation mutation. */
    data class Preparation(
        val files: List<FilePayload>,
        val repairedInternalUrls: Int = 0,
    ) {
        val fileSizes: Map<String, Long>
            get() = files.associate { it.relativePath to it.bytes.size.toLong() }

        companion object {
            fun unchanged(plan: Plan) = Preparation(plan.files)
        }
    }

    interface Executor {
        fun inspectTarget(packageName: String): TargetInfo?
        /** Validate and, when safe, repair the decoded payloads before the target app is stopped. */
        fun prepare(plan: Plan): Preparation?
        fun forceStop(packageName: String): Boolean
        /** Write one payload to a non-live staging path. False must leave the live destination untouched. */
        fun stage(packageName: String, file: FilePayload): Boolean
        /** Commit all staged files. A false result means rollback was attempted before returning. */
        fun commit(plan: Plan, target: TargetInfo, preparation: Preparation): Boolean
        fun discard(plan: Plan): Boolean
        fun relaunch(packageName: String): Boolean
    }

    data class Result(
        val ok: Boolean,
        val message: String,
        val committedFiles: Int? = 0,
        val relaunched: Boolean = false,
        val repairedInternalUrls: Int = 0,
    )

    /** Execute only a prevalidated [Plan]. Every staged write is mandatory; commit is never attempted after one fails. */
    fun execute(plan: Plan, executor: Executor): Result {
        val target = executor.inspectTarget(plan.packageName)
            ?: return Result(false, "could not read Companion owner or SELinux context")
        val preparation = executor.prepare(plan)
        val expectedFiles = plan.files.map { it.relativePath }
        if (preparation == null || preparation.files.map { it.relativePath } != expectedFiles ||
            preparation.files.any { it.bytes.isEmpty() } || preparation.repairedInternalUrls < 0
        ) {
            executor.discard(plan)
            return Result(false, "failed to validate or prepare Companion files")
        }
        if (!executor.forceStop(plan.packageName)) return Result(false, "could not stop Companion")

        for (file in preparation.files) {
            if (!executor.stage(plan.packageName, file)) {
                executor.discard(plan)
                val relaunched = executor.relaunch(plan.packageName)
                return Result(false, "failed to stage ${file.relativePath}", relaunched = relaunched)
            }
        }
        if (!executor.commit(plan, target, preparation)) {
            executor.discard(plan)
            val relaunched = executor.relaunch(plan.packageName)
            return Result(
                false,
                "failed to commit Companion files; rollback attempted",
                committedFiles = null,
                relaunched = relaunched,
                repairedInternalUrls = preparation.repairedInternalUrls,
            )
        }
        val relaunched = executor.relaunch(plan.packageName)
        if (!relaunched) return Result(
            false,
            "files restored but Companion relaunch failed",
            plan.files.size,
            repairedInternalUrls = preparation.repairedInternalUrls,
        )
        return Result(
            true,
            "restored Companion ${plan.packageName}",
            plan.files.size,
            relaunched = true,
            repairedInternalUrls = preparation.repairedInternalUrls,
        )
    }
}
