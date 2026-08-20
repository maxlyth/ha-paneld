package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every repository-documentation URL shipped in app sources must resolve. The Shizuku guide
 * consolidation deleted docs/shizuku.md while the Profile UI still linked to it, which no test
 * caught because the documentation link sweep read only Markdown and shell sources. This contract
 * reads the shipped Kotlin and web-asset sources, extracts each blob/main documentation URL, and
 * requires the referenced file to exist in the tree; where the URL carries a fragment, a heading
 * that slugifies to it must exist in the referenced Markdown, so a retargeted link cannot point at
 * a heading that was since reworded.
 */
class ShippedDocLinkContractTest {
    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main").isDirectory }

    private fun shippedSources(): List<File> =
        listOf("app/src/main/kotlin", "app/src/main/assets")
            .map { File(repoRoot, it) }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "js" || it.extension == "html") }.toList() }

    private fun headingSlugs(markdown: File): Set<String> =
        markdown.readLines()
            .filter { it.startsWith("#") }
            .map { heading ->
                heading.trimStart('#').trim()
                    .lowercase()
                    .replace(Regex("[`*_\\[\\]()]"), "")
                    .replace(Regex("[^a-z0-9\\- ]"), "")
                    .trim()
                    .replace(Regex(" +"), "-")
            }
            .toSet()

    @Test
    fun everyShippedRepositoryDocLinkResolvesToARealFileAndHeading() {
        val url = Regex("blob/main/((?:docs/)?[A-Za-z0-9_/.-]+\\.md)(#([A-Za-z0-9-]+))?")
        val problems = mutableListOf<String>()
        var found = 0
        for (source in shippedSources()) {
            for (match in url.findAll(source.readText())) {
                found++
                val path = match.groupValues[1]
                val anchor = match.groupValues[3]
                val target = File(repoRoot, path)
                if (!target.isFile) {
                    problems += "${source.name}: $path does not exist in the repository"
                } else if (anchor.isNotEmpty() && anchor !in headingSlugs(target)) {
                    problems += "${source.name}: $path has no heading matching #$anchor"
                }
            }
        }
        assertTrue("no shipped repository doc links were found; the extractor is broken", found > 0)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
