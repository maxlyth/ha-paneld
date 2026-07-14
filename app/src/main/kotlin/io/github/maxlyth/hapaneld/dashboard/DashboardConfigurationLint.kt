package io.github.maxlyth.hapaneld.dashboard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Conservative dependency analysis for selector-driven dashboard cards.
 *
 * This deliberately computes a structural superset rather than reproducing a card's dynamic
 * filtering. A selector is safe to subscribe only when that superset is bounded. Dynamic state,
 * attribute, sorting, and display-limit clauses never make the superset smaller.
 */
object DashboardConfigurationLint {
    const val SELECTOR_ENTITY_LIMIT = 64
    const val SELECTOR_TOTAL_LIMIT = 128

    enum class IssueType(val wireName: String) {
        UNBOUNDED_SELECTOR("unbounded_selector"),
        BROAD_SELECTOR("broad_selector"),
        SELECTOR_BUDGET("selector_budget"),
    }

    data class Issue(
        val severity: String = "error",
        val type: IssueType,
        val blocking: Boolean = true,
        val viewTitle: String,
        val viewPath: String,
        val cardTitle: String?,
        val sourceLocations: List<String>,
        val ruleSummary: String,
        val candidateCount: Int?,
        val limit: Int,
        val reason: String,
        val recommendation: String,
        val fingerprint: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("severity", severity)
            .put("type", type.wireName)
            .put("blocking", blocking)
            .put("view_title", viewTitle)
            .put("view_path", viewPath)
            .put("card_title", cardTitle ?: JSONObject.NULL)
            .put("source_locations", JSONArray(sourceLocations))
            .put("rule_summary", ruleSummary)
            .put("candidate_count", candidateCount ?: JSONObject.NULL)
            .put("limit", limit)
            .put("reason", reason)
            .put("recommendation", recommendation)
            .put("fingerprint", fingerprint)
    }

    data class Result(
        /** Concrete IDs contributed only by individually bounded selectors. */
        val safeEntityIds: Set<String>,
        val issues: List<Issue>,
    ) {
        val blocking: Boolean get() = issues.any(Issue::blocking)
    }

    private data class Metadata(val area: String, val floor: String, val labels: Set<String>, val friendlyName: String)
    private data class View(val title: String, val path: String)
    private data class Source(val location: String, val cardTitle: String?)
    private data class Rule(
        val domains: Set<String>,
        val entityPatterns: Set<String>,
        val areas: Set<String>,
        val floors: Set<String>,
        val labels: Set<String>,
        val namePattern: String?,
        val usable: Boolean,
        val dynamic: Boolean,
        val signature: String,
    )
    private data class Finding(
        val type: IssueType,
        val view: View,
        val source: Source,
        val signature: String,
        val summary: String,
        val candidateCount: Int?,
        val limit: Int,
    )

    fun analyze(
        configJson: String,
        catalog: Collection<String>,
        metadataJson: Map<String, String>,
        friendlyNames: Map<String, String> = emptyMap(),
    ): Result {
        val root = JSONObject(configJson)
        val catalogIds = catalog.asSequence().map(String::lowercase)
            .filter(ENTITY_ID::matches).distinct().sorted().toList()
        val metadata = catalogIds.associateWith { id -> parseMetadata(metadataJson[id], friendlyNames[id].orEmpty()) }
        val safe = sortedSetOf<String>()
        val findings = mutableListOf<Finding>()
        val boundedSources = mutableListOf<Source>()

        fun analyzeCard(card: JSONObject, view: View, source: Source) {
            val filter = card.optJSONObject("filter")
            val includes = filter?.let { rules(it.opt("include")) }.orEmpty()
            val excludes = filter?.let { rules(it.opt("exclude")) }.orEmpty().map(::parseRule)
            // An exclude containing a runtime/dynamic refinement cannot safely remove its entire
            // structural match. Unsupported excludes are therefore ignored, not approximated.
            val supportedExcludes = excludes.filter { it.usable && !it.dynamic }
            val excluded = supportedExcludes.flatMapTo(hashSetOf()) { match(it, catalogIds, metadata) }

            if (includes.isEmpty()) {
                findings += Finding(
                    IssueType.UNBOUNDED_SELECTOR, view, source, "missing-include", "Unbounded entity selector",
                    null, SELECTOR_ENTITY_LIMIT,
                )
                return
            }
            for (raw in includes) {
                val rule = parseRule(raw)
                val effectiveSignature = rule.signature + "|exclude=" +
                    supportedExcludes.map(Rule::signature).sorted().joinToString(",")
                if (!rule.usable) {
                    findings += Finding(
                        IssueType.UNBOUNDED_SELECTOR, view, source, effectiveSignature,
                        "Unbounded or dynamic entity selector", null, SELECTOR_ENTITY_LIMIT,
                    )
                    continue
                }
                val candidates = match(rule, catalogIds, metadata).filterNotTo(sortedSetOf(), excluded::contains)
                if (candidates.size > SELECTOR_ENTITY_LIMIT) {
                    findings += Finding(
                        IssueType.BROAD_SELECTOR, view, source, effectiveSignature,
                        summary(rule), candidates.size, SELECTOR_ENTITY_LIMIT,
                    )
                } else {
                    safe += candidates
                    boundedSources += source
                }
            }
        }

        val views = root.optJSONArray("views")
        if (views != null) for (viewIndex in 0 until views.length()) {
            val viewObject = views.optJSONObject(viewIndex) ?: continue
            val view = View(
                title = safeText(viewObject.optString("title")) ?: "View ${viewIndex + 1}",
                path = safeIdentifier(viewObject.optString("path")) ?: "views[$viewIndex]",
            )
            walkCards(viewObject, "dashboard.views[$viewIndex]", view) { card, source ->
                analyzeCard(card, view, source)
            }
        }

        if (safe.size > SELECTOR_TOTAL_LIMIT) {
            findings += Finding(
                type = IssueType.SELECTOR_BUDGET,
                view = View("Dashboard", "dashboard"),
                source = Source("dashboard", null),
                signature = "selector-total-budget",
                summary = "Bounded selectors collectively match ${safe.size} entities",
                candidateCount = safe.size,
                limit = SELECTOR_TOTAL_LIMIT,
            )
        }

        return Result(safe, group(findings, boundedSources))
    }

    private fun group(findings: List<Finding>, boundedSources: List<Source>): List<Issue> = findings
        .groupBy { Triple(it.view.path, it.type, it.signature) }
        .map { (key, grouped) ->
            val first = grouped.first()
            val locations = if (first.type == IssueType.SELECTOR_BUDGET) {
                boundedSources.map(Source::location).distinct().sorted()
            } else grouped.map { it.source.location }.distinct().sorted()
            val titles = grouped.mapNotNull { it.source.cardTitle }.distinct()
            Issue(
                type = first.type,
                viewTitle = first.view.title,
                viewPath = first.view.path,
                cardTitle = titles.singleOrNull(),
                sourceLocations = locations,
                ruleSummary = first.summary,
                candidateCount = first.candidateCount,
                limit = first.limit,
                reason = when (first.type) {
                    IssueType.UNBOUNDED_SELECTOR ->
                        "The selector has no structural bound that can be converted into a safe subscription set."
                    IssueType.BROAD_SELECTOR ->
                        "The structural candidate set exceeds the per-selector subscription safety limit. Dynamic filters, sorting, and display limits cannot safely reduce it."
                    IssueType.SELECTOR_BUDGET ->
                        "The combined bounded selectors exceed the dashboard subscription safety limit."
                },
                recommendation = when (first.type) {
                    IssueType.UNBOUNDED_SELECTOR ->
                        "Add an explicit entity ID, entity pattern, domain, area, floor, or label constraint."
                    IssueType.BROAD_SELECTOR ->
                        "Narrow the selector with an area, floor, label, entity pattern, or explicit entity list."
                    IssueType.SELECTOR_BUDGET ->
                        "Narrow the dashboard selectors so their combined entity set stays within the limit."
                },
                fingerprint = EntityLearningProtocol.hash("${key.first}|${key.second.wireName}|${key.third}"),
            )
        }.sortedWith(compareBy<Issue>({ it.viewPath }, { it.type.wireName }, { it.fingerprint }))

    private fun walkCards(
        value: Any?,
        path: String,
        view: View,
        consume: (JSONObject, Source) -> Unit,
    ) {
        when (value) {
            is JSONObject -> {
                if (value.optString("type") == "custom:auto-entities") {
                    consume(value, Source(path, safeText(value.optString("title")) ?: safeText(value.optString("name"))))
                }
                value.keys().asSequence().toList().sorted().forEach { key ->
                    walkCards(value.opt(key), "$path.$key", view, consume)
                }
            }
            is JSONArray -> for (index in 0 until value.length()) walkCards(value.opt(index), "$path[$index]", view, consume)
        }
    }

    private fun rules(value: Any?): List<JSONObject> = when (value) {
        is JSONObject -> listOf(value)
        is JSONArray -> (0 until value.length()).mapNotNull { index ->
            when (val item = value.opt(index)) {
                is JSONObject -> item
                is String -> JSONObject().put("entity_id", item)
                else -> null
            }
        }
        is String -> listOf(JSONObject().put("entity_id", value))
        else -> emptyList()
    }

    private fun parseRule(raw: JSONObject): Rule {
        val domains = structuralValues(raw.opt("domain"), DOMAIN)
        val rawEntityPatterns = values(raw.opt("entity_id"))
        val invalidEntityPattern = rawEntityPatterns.any { !isEntityPattern(it.lowercase()) }
        // Entity patterns in one include rule are alternatives. If any pattern is unsafe, dropping only
        // that alternative would under-count the selector; discard the complete pattern constraint and
        // retain only independent domain/area/floor/label constraints as a conservative superset.
        val patterns = if (invalidEntityPattern) emptySet() else structuralValues(raw.opt("entity_id"), ENTITY_PATTERN)
        val areas = structuralValues(raw.opt("area"), IDENTIFIER) + structuralValues(raw.opt("area_id"), IDENTIFIER)
        val floors = structuralValues(raw.opt("floor"), IDENTIFIER) + structuralValues(raw.opt("floor_id"), IDENTIFIER)
        val labels = structuralValues(raw.opt("label"), IDENTIFIER) + structuralValues(raw.opt("label_id"), IDENTIFIER)
        val rawName = (raw.opt("name") as? String)?.takeIf(String::isNotBlank)
        val invalidNamePattern = raw.has("name") && (rawName == null || !isFriendlyNamePattern(rawName))
        val namePattern = rawName?.takeIf(::isFriendlyNamePattern)
        val structuralKeys = setOf("domain", "entity_id", "area", "area_id", "floor", "floor_id", "label", "label_id", "name")
        val presentationKeys = setOf("options", "sort")
        val keys = raw.keys().asSequence().toSet()
        val suppliedStructural = keys.intersect(structuralKeys)
        val invalidStructural = invalidEntityPattern || invalidNamePattern || suppliedStructural.any { key ->
            values(raw.opt(key)).any { value ->
                containsTemplate(value) || when (key) {
                    "domain" -> !DOMAIN.matches(value.lowercase())
                    "entity_id" -> !isEntityPattern(value.lowercase())
                    "name" -> !isFriendlyNamePattern(value)
                    else -> !IDENTIFIER.matches(value.lowercase())
                }
            }
        }
        val dynamic = (keys - structuralKeys - presentationKeys).isNotEmpty() || invalidStructural
        val usable = domains.isNotEmpty() || patterns.isNotEmpty() || areas.isNotEmpty() || floors.isNotEmpty() ||
            labels.isNotEmpty() || namePattern != null
        val signature = listOf(
            "d=" + domains.sorted().joinToString(","),
            "e=" + patterns.sorted().joinToString(","),
            "a=" + areas.sorted().joinToString(","),
            "f=" + floors.sorted().joinToString(","),
            "l=" + labels.sorted().joinToString(","),
            "n=" + (namePattern?.let(EntityLearningProtocol::hash) ?: ""),
            "dynamic=$dynamic",
            // Preserve exact-rule grouping without placing raw template or state expressions in an
            // issue record. The short hash is deterministic but not reversible in the UI payload.
            "rule=" + EntityLearningProtocol.hash(EntityLearningProtocol.canonical(raw)),
        ).joinToString("|")
        return Rule(domains, patterns, areas, floors, labels, namePattern, usable, dynamic, signature)
    }

    private fun match(rule: Rule, catalog: List<String>, metadata: Map<String, Metadata>): Set<String> {
        val patterns = rule.entityPatterns.mapNotNull(::compilePattern)
        val nameMatcher = rule.namePattern?.let(::compileFriendlyNamePattern)
        return catalog.filterTo(sortedSetOf()) { id ->
            val meta = metadata.getValue(id)
            (rule.domains.isEmpty() || id.substringBefore('.') in rule.domains) &&
                (patterns.isEmpty() || patterns.any { it.containsMatchIn(id) }) &&
                (rule.areas.isEmpty() || meta.area in rule.areas) &&
                (rule.floors.isEmpty() || meta.floor in rule.floors) &&
                (rule.labels.isEmpty() || meta.labels.any(rule.labels::contains)) &&
                (nameMatcher == null || meta.friendlyName.isNotEmpty() && nameMatcher(meta.friendlyName))
        }
    }

    private fun compileFriendlyNamePattern(pattern: String): ((String) -> Boolean)? = when {
        pattern.length >= 2 && pattern.startsWith('/') && pattern.endsWith('/') -> {
            val source = pattern.substring(1, pattern.length - 1)
            if (!isSafeEntitySelectorRegex(source)) null else runCatching { Regex(source) }.getOrNull()
                ?.let { regex -> { value: String -> regex.containsMatchIn(value) } }
        }
        '*' in pattern -> runCatching {
            Regex("^" + pattern.split('*').joinToString(".*") { Regex.escape(it) } + "$")
        }.getOrNull()?.let { regex -> { value: String -> regex.matches(value) } }
        else -> { value: String -> value == pattern }
    }

    private fun compilePattern(pattern: String): Regex? = when {
        pattern.length >= 2 && pattern.startsWith('/') && pattern.endsWith('/') -> {
            val source = pattern.substring(1, pattern.length - 1)
            if (isSafeEntitySelectorRegex(source)) runCatching { Regex(source) }.getOrNull() else null
        }
        else -> runCatching {
            Regex(buildString {
                append('^')
                pattern.forEach { ch -> append(when (ch) {
                    '*' -> ".*"
                    '?' -> "."
                    else -> Regex.escape(ch.toString())
                }) }
                append('$')
            })
        }.getOrNull()
    }

    /** Auto-entities slash patterns originate in dashboard configuration and run against the complete
     *  catalogue. Keep the supported subset deliberately regular: no groups, alternation, counted
     *  repetition or backreferences means quantifiers cannot nest. Permit only one unbounded quantifier:
     *  adjacent or separated `.*`/`+` fragments can still produce catastrophic backtracking even without
     *  those richer constructs. Rejected patterns are
     *  treated as an unconstrained selector by [match], producing a blocking broad-selector diagnostic. */
    internal fun isSafeEntitySelectorRegex(source: String): Boolean {
        if (source.isEmpty() || source.length > MAX_PATTERN_LENGTH) return false
        var escaped = false
        var inClass = false
        var previousQuantifier = false
        var unboundedQuantifiers = 0
        source.forEach { ch ->
            if (escaped) {
                if (ch.isDigit()) return false
                escaped = false
                previousQuantifier = false
                return@forEach
            }
            if (ch == '\\') {
                escaped = true
                previousQuantifier = false
                return@forEach
            }
            if (inClass) {
                if (ch == ']') inClass = false
                return@forEach
            }
            when (ch) {
                '[' -> { inClass = true; previousQuantifier = false }
                '(', ')', '{', '}', '|' -> return false
                '*', '+' -> {
                    if (previousQuantifier || ++unboundedQuantifiers > 1) return false
                    previousQuantifier = true
                }
                '?' -> {
                    if (previousQuantifier) return false
                    previousQuantifier = true
                }
                else -> previousQuantifier = false
            }
        }
        return !escaped && !inClass
    }

    private fun parseMetadata(json: String?, friendlyName: String): Metadata {
        val objectValue = json?.let { runCatching { JSONObject(it) }.getOrNull() }
        fun first(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
            objectValue?.optString(key)?.lowercase()?.takeIf(String::isNotBlank)
        }.orEmpty()
        fun labels(): Set<String> = listOf("lb", "labels", "label_ids").flatMapTo(hashSetOf()) { key ->
            when (val value = objectValue?.opt(key)) {
                is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).lowercase().takeIf(String::isNotBlank) }
                is String -> listOf(value.lowercase())
                else -> emptyList()
            }
        }
        return Metadata(first("ai", "area_id", "area"), first("fi", "floor_id", "floor"), labels(), friendlyName)
    }

    private fun summary(rule: Rule): String {
        val parts = mutableListOf<String>()
        if (rule.domains.isNotEmpty()) parts += "domain ${safeList(rule.domains)}"
        if (rule.entityPatterns.isNotEmpty()) parts += "entity pattern ${safeList(rule.entityPatterns)}"
        if (rule.areas.isNotEmpty()) parts += "area ${safeList(rule.areas)}"
        if (rule.floors.isNotEmpty()) parts += "floor ${safeList(rule.floors)}"
        if (rule.labels.isNotEmpty()) parts += "label ${safeList(rule.labels)}"
        if (rule.namePattern != null) parts += "friendly name ${safeNamePattern(rule.namePattern)}"
        return parts.joinToString("; ").take(MAX_SUMMARY_LENGTH)
    }

    private fun safeNamePattern(pattern: String): String = pattern.replace(Regex("[\\p{Cntrl}]"), " ").take(80)

    private fun safeList(values: Set<String>): String = values.sorted().take(3).joinToString(", ") { it.take(40) } +
        if (values.size > 3) " (+${values.size - 3})" else ""

    private fun structuralValues(value: Any?, allowed: Regex): Set<String> = values(value).map(String::lowercase)
        .filterNot(::containsTemplate).filter { candidate ->
            if (allowed === ENTITY_PATTERN) isEntityPattern(candidate) else allowed.matches(candidate)
        }.toSortedSet()

    private fun values(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }
        else -> emptyList()
    }

    private fun isEntityPattern(value: String): Boolean = when {
        value.length > MAX_PATTERN_LENGTH || containsTemplate(value) -> false
        ENTITY_ID.matches(value) -> true
        GLOB_ENTITY_ID.matches(value) -> true
        value.length >= 2 && value.startsWith('/') && value.endsWith('/') ->
            value.substring(1, value.length - 1).let { source ->
                isSafeEntitySelectorRegex(source) && runCatching { Regex(source) }.isSuccess
            }
        else -> false
    }

    private fun isFriendlyNamePattern(value: String): Boolean = when {
        value.isEmpty() || value.length > MAX_PATTERN_LENGTH || containsTemplate(value) ||
            value.any(Char::isISOControl) -> false
        value.length >= 2 && value.startsWith('/') && value.endsWith('/') ->
            value.substring(1, value.length - 1).let { source ->
                isSafeEntitySelectorRegex(source) && runCatching { Regex(source) }.isSuccess
            }
        '*' in value -> value.none { it in UNSAFE_GLOB_META }
        else -> true
    }

    private fun safeText(value: String): String? = value.trim().takeIf {
        it.isNotBlank() && !containsTemplate(it)
    }?.replace(Regex("[\\p{Cntrl}]"), " ")?.take(80)

    private fun safeIdentifier(value: String): String? = value.lowercase().takeIf(IDENTIFIER::matches)
    private fun containsTemplate(value: String): Boolean = TEMPLATE_MARKERS.any(value::contains)

    private val ENTITY_ID = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")
    private val GLOB_ENTITY_ID = Regex("^[a-z0-9_*?]+\\.[a-z0-9_*?]+$")
    private val DOMAIN = Regex("^[a-z0-9_]+$")
    private val IDENTIFIER = Regex("^[a-z0-9_-]+$")
    private val ENTITY_PATTERN = Regex(".*") // Identity marker; validated by isEntityPattern.
    private val TEMPLATE_MARKERS = listOf("{{", "{%", "[[[", "hass.states", "states[")
    private val UNSAFE_GLOB_META = setOf('\\', '.', '+', '?', '^', '$', '{', '}', '(', ')', '|', '[', ']', '/')
    private const val MAX_PATTERN_LENGTH = 160
    private const val MAX_SUMMARY_LENGTH = 240
}
