package io.github.maxlyth.hapaneld.config

import io.github.maxlyth.hapaneld.util.Json

/**
 * A versioned, self-describing config document for export / import / revision snapshots.
 * Pure: serialize/parse operate on a flat `Map<String,String>` of values so this runs on the JVM
 * test classpath. The Android edge may instead parse inbound JSON with `org.json` and hand the map
 * to [fromValues]; [parse] is provided so a snapshot written by [serialize] round-trips losslessly.
 */
data class ConfigBundle(
    val kind: String,
    val schema: Int,
    val exportedAt: String,
    val exportedBy: String,
    val values: Map<String, String>,
) {
    /** Compact JSON; `values` rendered with sorted keys for stable, diffable output. */
    fun serialize(): String {
        val sb = StringBuilder()
        sb.append("{\"kind\":\"").append(esc(kind)).append("\",")
        sb.append("\"schema\":").append(schema).append(',')
        sb.append("\"exported_at\":\"").append(esc(exportedAt)).append("\",")
        sb.append("\"exported_by\":\"").append(esc(exportedBy)).append("\",")
        sb.append("\"values\":{")
        values.entries.sortedBy { it.key }.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            sb.append('"').append(esc(k)).append("\":\"").append(esc(v)).append('"')
        }
        sb.append("}}")
        return sb.toString()
    }

    companion object {
        const val KIND_CONFIG = "ha-paneld-config"
        const val KIND_REVISION = "ha-paneld-revision"

        fun fromValues(
            values: Map<String, String>,
            kind: String = KIND_CONFIG,
            schema: Int = SettingsRegistry.SCHEMA,
            exportedAt: String = "",
            exportedBy: String = "",
        ) = ConfigBundle(kind, schema, exportedAt, exportedBy, values)

        /** Minimal tolerant parser for the shape produced by [serialize]. Returns null on malformed input. */
        fun parse(json: String): ConfigBundle? = runCatching {
            val p = JsonReader(json)
            p.expect('{')
            var kind = ""; var schema = 0; var at = ""; var by = ""
            var values: Map<String, String> = emptyMap()
            while (true) {
                val key = p.readString(); p.expect(':')
                when (key) {
                    "kind" -> kind = p.readString()
                    "schema" -> schema = p.readNumber().toInt()
                    "exported_at" -> at = p.readString()
                    "exported_by" -> by = p.readString()
                    "values" -> values = p.readStringMap()
                    else -> p.skipValue()
                }
                if (!p.commaOrEnd('}')) break
            }
            p.requireEnd()
            ConfigBundle(kind, schema, at, by, values)
        }.getOrNull()

        private fun esc(s: String): String = Json.esc(s)
    }

    /** Tiny recursive-descent reader for the limited JSON this module emits (strings, ints, flat maps). */
    private class JsonReader(private val s: String) {
        private var i = 0
        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun expect(c: Char) { ws(); require(i < s.length && s[i] == c) { "expected $c" }; i++ }
        fun commaOrEnd(end: Char): Boolean {
            ws()
            return when {
                i < s.length && s[i] == ',' -> { i++; true }
                i < s.length && s[i] == end -> { i++; false }
                else -> throw IllegalStateException("expected , or $end")
            }
        }
        fun readString(): String {
            ws(); require(i < s.length && s[i] == '"') { "expected string at $i" }; i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalStateException("unterminated string")
        }
        fun readNumber(): Double {
            ws(); val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }
        fun readStringMap(): Map<String, String> {
            val m = LinkedHashMap<String, String>()
            expect('{'); ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                val k = readString(); expect(':'); m[k] = readString()
                if (!commaOrEnd('}')) break
            }
            return m
        }
        fun requireEnd() { ws(); require(i == s.length) { "trailing input" } }
        fun skipValue() {
            ws()
            when {
                i < s.length && s[i] == '"' -> readString()
                i < s.length && s[i] == '{' -> readStringMap()
                else -> readNumber()
            }
        }
    }
}
