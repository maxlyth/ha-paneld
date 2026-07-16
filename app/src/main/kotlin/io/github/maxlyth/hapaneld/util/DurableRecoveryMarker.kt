package io.github.maxlyth.hapaneld.util

import java.io.File
import java.io.FileOutputStream

/** Small fsync-backed no-backup marker for a platform mutation that must be retried after process death. */
internal class DurableRecoveryMarker(private val file: File) {
    fun isArmed(): Boolean = file.isFile

    fun arm(): Boolean = runCatching {
        file.parentFile?.mkdirs()
        FileOutputStream(file, false).use { output ->
            output.write(1)
            output.fd.sync()
        }
        true
    }.getOrDefault(false)

    fun clear(): Boolean = !file.exists() || file.delete()

    /** Clear only after [recover] proves the platform state was restored. */
    fun recoverIfArmed(recover: () -> Boolean): Boolean {
        if (!isArmed()) return true
        if (!recover()) return false
        return clear()
    }
}
