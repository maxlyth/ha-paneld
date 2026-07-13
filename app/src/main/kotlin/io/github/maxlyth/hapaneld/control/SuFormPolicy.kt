package io.github.maxlyth.hapaneld.control

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
}
