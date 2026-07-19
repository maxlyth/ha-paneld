package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.util.MonotonicDeadline

/**
 * Cached `su` dialect selection. A successful dialect is sticky because trying the other syntax after
 * a command-specific non-zero exit would misclassify the command result. A prior negative probe is not
 * sticky: root-manager readiness can change after boot, so every later one-shot boundary may try both.
 */
internal object SuFormPolicy {
    const val UNPROBED = -1
    const val TOOLBOX = 0
    const val ANDROID = 1
    const val NONE_LAST_PROBE = 2

    fun candidates(cached: Int): IntArray = when (cached) {
        TOOLBOX, ANDROID -> intArrayOf(cached)
        else -> intArrayOf(TOOLBOX, ANDROID)
    }

    fun working(cached: Int): Boolean = cached == TOOLBOX || cached == ANDROID

    data class Selection<T : Any>(val form: Int, val value: T)

    /** Try dialects under one caller-owned deadline so a retry cannot multiply the public timeout. */
    fun <T : Any> firstSuccessfulWithin(
        cached: Int,
        deadline: MonotonicDeadline,
        attempt: (form: Int, deadline: MonotonicDeadline) -> T?,
    ): Selection<T>? {
        val candidates = candidates(cached)
        for ((index, candidate) in candidates.withIndex()) {
            val remainingMs = deadline.remainingMs()
            if (remainingMs <= 0L) break
            val candidatesLeft = candidates.size - index
            val sliceMs = remainingMs / candidatesLeft + if (remainingMs % candidatesLeft == 0L) 0L else 1L
            val value = attempt(candidate, deadline.cappedTo(sliceMs)) ?: continue
            return Selection(candidate, value)
        }
        return null
    }
}
