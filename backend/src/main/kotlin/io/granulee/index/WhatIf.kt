package io.granulee.index

import io.granulee.clickhouse.ChConnection
import io.granulee.clickhouse.long
import io.granulee.facts.QueryFacts
import io.granulee.sql.TrialIndex

/** What EXPLAIN WHATIF says about one candidate index for one query. */
data class WhatIfEstimate(
    val name: String,
    val status: String, // applicable | not_applicable, as the server words it
    val reason: String? = null,
    val granulesAfter: Long? = null,
    val source: String? = null, // empirical | statistical | applicability_only
    val sampled: Boolean = false, // estimated from a sample of the table, not all of it
)

/** One EXPLAIN WHATIF answer: what the query reads today and what it would read with each candidate. */
data class WhatIfReport(
    val parts: Long? = null,
    val granules: Long? = null, // after the primary key, the partition key and the existing skip indexes
    val estimates: List<WhatIfEstimate> = emptyList(),
    val combined: WhatIfEstimate? = null, // every measured candidate at once
)

/** Reads what EXPLAIN WHATIF prints, and words an index as it wants it. */
object WhatIf {
    private val WITH = Regex("^With (\\S+) \\(.*\\):$")

    /** EXPLAIN WHATIF came with the hypothetical indexes; their system table tells whether the server has it. */
    fun supported(ch: ChConnection): Boolean = try {
        (ch.first("SELECT count() AS c FROM system.tables WHERE database = 'system' AND name = 'hypothetical_indexes'")?.long("c") ?: 0) > 0
    } catch (e: Exception) {
        false
    }

    /** The index expression as CREATE HYPOTHETICAL INDEX takes it: inside one pair of parentheses. */
    fun parenthesised(expression: String): String {
        val text = expression.trim()
        if (text.startsWith("(")) {
            var depth = 0
            for (i in text.indices) {
                if (text[i] == '(') depth++
                if (text[i] == ')' && --depth == 0) return if (i == text.lastIndex) text else "($text)"
            }
        }
        return "($text)"
    }

    /**
     * Reads the text EXPLAIN WHATIF prints: blocks of "  key: value" lines under a heading. An "Estimation:" block
     * belongs to the candidate above it.
     */
    fun parse(text: String): WhatIfReport {
        val blocks = mutableListOf<Pair<String, MutableMap<String, String>>>()
        text.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            if (!line.startsWith(" ")) {
                blocks.add(line.trim() to linkedMapOf())
            } else if (blocks.isNotEmpty()) {
                val at = line.indexOf(':')
                if (at > 0) blocks.last().second[line.substring(0, at).trim()] = line.substring(at + 1).trim()
            }
        }
        var parts: Long? = null
        var granules: Long? = null
        val estimates = mutableListOf<WhatIfEstimate>()
        var combined: WhatIfEstimate? = null
        var last = ""
        blocks.forEach { (heading, fields) ->
            fun estimate(name: String) = WhatIfEstimate(
                name = name,
                status = fields["status"].orEmpty(),
                reason = fields["reason"],
                granulesAfter = fields["marks"]?.toLongOrNull(),
                sampled = "marks_span" in fields,
            )
            when {
                heading.startsWith("Baseline") -> {
                    parts = fields["parts"]?.toLongOrNull()
                    granules = fields["marks"]?.toLongOrNull()
                }
                heading.startsWith("(combined:") -> {
                    combined = estimate("")
                    last = "combined"
                }
                heading == "Estimation:" -> when (last) {
                    "combined" -> combined = combined?.copy(source = fields["source"])
                    "candidate" -> estimates[estimates.lastIndex] = estimates.last().copy(source = fields["source"])
                }
                else -> WITH.find(heading)?.let {
                    estimates.add(estimate(it.groupValues[1]))
                    last = "candidate"
                }
            }
        }
        return WhatIfReport(parts, granules, estimates, combined)
    }
}

data class WhatIfRequest(val candidates: List<TrialIndex> = emptyList())

/** One logged query shape and what it reads today. */
data class WhatIfShape(
    val query: String,
    val count: Long, // runs in the log window
    val share: Double, // of the table's SELECTs
    val parts: Long?,
    val granules: Long?,
    val error: String?,
)

/** One candidate in one shape. */
data class WhatIfUse(
    val shape: Int, // position in TableWhatIf.shapes
    val applicable: Boolean,
    val reason: String?,
    val granulesBefore: Long?,
    val granulesAfter: Long?,
    val source: String?,
    val sampled: Boolean,
)

data class WhatIfCandidate(
    val name: String,
    val expression: String,
    val type: String,
    val granularity: Int,
    val error: String?, // the server refused the definition
    val shapes: Int, // shapes it can filter
    val share: Double, // of the table's SELECTs, by run count
    val granulesBefore: Long, // read today by those shapes
    val granulesAfter: Long, // read with the index
    val uses: List<WhatIfUse>,
)

data class TableWhatIf(
    val message: String?, // set when nothing could be estimated
    val checkedShapes: Int,
    val failedShapes: Int,
    val selects: Long,
    val shapes: List<WhatIfShape>,
    val candidates: List<WhatIfCandidate>,
)

/** A logged query shape and what EXPLAIN WHATIF said about it, or why it said nothing. */
internal class WhatIfAnswer(val query: QueryFacts, val report: Result<WhatIfReport>)

/** Adds up, per candidate index, what the answers for a table's logged queries say it would save. */
internal object WhatIfSummary {

    /** The answer when nothing could be estimated: the candidates with nothing counted, and why. */
    fun none(candidates: List<TrialIndex>, message: String?, refused: Map<String, String?> = emptyMap()) =
        TableWhatIf(message, 0, 0, 0, emptyList(), candidates.map { WhatIfCandidate(it.name, it.expression, it.type, it.granularity, refused[it.name], 0, 0.0, 0, 0, emptyList()) })

    /** `refused` holds, per candidate name, why the server did not take its definition; null where it did. */
    fun of(candidates: List<TrialIndex>, refused: Map<String, String?>, answers: List<WhatIfAnswer>, queryTotal: Long): TableWhatIf {
        val total = queryTotal.coerceAtLeast(1)
        val shapes = answers.map { answer ->
            val read = answer.report.getOrNull()
            WhatIfShape(answer.query.query, answer.query.count, answer.query.count.toDouble() / total, read?.parts, read?.granules, answer.report.exceptionOrNull()?.message)
        }
        val summed = candidates.map { index ->
            val uses = answers.withIndex().mapNotNull { (at, answer) ->
                val report = answer.report.getOrNull() ?: return@mapNotNull null
                val estimate = report.estimates.firstOrNull { it.name == index.name } ?: return@mapNotNull null
                val applies = estimate.status == "applicable" && estimate.granulesAfter != null && report.granules != null
                WhatIfUse(at, applies, estimate.reason, report.granules, estimate.granulesAfter, estimate.source, estimate.sampled)
            }
            val helped = uses.filter { it.applicable }
            WhatIfCandidate(
                index.name, index.expression, index.type, index.granularity, refused[index.name],
                shapes = helped.size,
                share = helped.sumOf { answers[it.shape].query.count }.toDouble() / total,
                granulesBefore = helped.sumOf { it.granulesBefore ?: 0 },
                granulesAfter = helped.sumOf { it.granulesAfter ?: 0 },
                uses = uses.sortedByDescending { it.applicable },
            )
        }
        return TableWhatIf(null, answers.size, answers.count { it.report.isFailure }, answers.sumOf { it.query.count }, shapes, summed)
    }
}
