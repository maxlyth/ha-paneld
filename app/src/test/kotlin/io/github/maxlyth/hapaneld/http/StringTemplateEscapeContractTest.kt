package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backslash-escaped quote inside a string template's `${...}` block is a compile error, because the
 * block holds Kotlin code rather than string data. It is an unusually expensive mistake: Kotlin loses
 * track of the enclosing class, so the reported errors appear hundreds of lines away as "unresolved
 * reference" on every companion-object member, and the real cause is nowhere in the output.
 *
 * This has now happened twice in the same expression in [PaneldServer]'s settings-schema builder, because
 * a correctly-written `${... spec.key != "x" ...}` sitting inside an escaped-quote JSON string reads like
 * an escaping bug to anyone editing the line. Each "fix" broke the build; each repair was then re-broken.
 *
 * Two things stop that loop. The builder now hoists every quote-bearing expression into a local so the
 * templates contain bare identifiers, and this test fails fast — at the offending file and line — if an
 * escaped quote ever appears inside an interpolation again.
 */
class StringTemplateEscapeContractTest {
    @Test fun noEscapedQuoteAppearsInsideAStringTemplateInterpolation() {
        val roots = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).filter { it.isDirectory }
        assertTrue("no Kotlin source root found from ${File(".").absolutePath}", roots.isNotEmpty())
        val offenders = roots.asSequence()
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    if (escapedQuoteInsideInterpolation(line)) "${file.path}:${index + 1}: $line" else null
                }
            }
            .toList()
        assertTrue(
            "escaped quote inside a \${...} interpolation — the block holds code, not string data, so " +
                "\\\" is a parse error that breaks the whole module:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * Scan one line for a backslash-quote sitting in **code position** inside a `${...}` block.
     *
     * The distinction matters: an interpolation may legitimately contain a nested string literal, and
     * `\"` inside *that* is ordinary escaping — `${if (x) "a=\"b\"" else ""}` is correct and common in
     * this file's HTML builders. Only a backslash-quote outside any nested literal is the parse error,
     * so nested string state is tracked rather than just brace depth. Brace depth is counted too, so a
     * lambda inside an interpolation does not end it early. Templates here are single-line, which keeps
     * a line-at-a-time scan sufficient and cheap.
     */
    private fun escapedQuoteInsideInterpolation(line: String): Boolean {
        var depth = 0
        var inNestedString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (depth == 0) {
                if (c == '$' && i + 1 < line.length && line[i + 1] == '{') {
                    depth = 1
                    i += 2
                    continue
                }
                i++
                continue
            }
            if (inNestedString) {
                if (c == '\\') { i += 2; continue } // an escape inside a nested literal is fine
                if (c == '"') inNestedString = false
                i++
                continue
            }
            when {
                c == '"' -> inNestedString = true
                c == '\\' -> if (i + 1 < line.length && line[i + 1] == '"') return true else i++
                c == '{' -> depth++
                c == '}' -> depth--
            }
            i++
        }
        return false
    }
}
