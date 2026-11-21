package io.granulee

import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.SecureRandom

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

object WhatIf {
    private val WITH = Regex("^With (\\S+) \\(.*\\):$")

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

@RestController
@RequestMapping("/api")
class WhatIfController(private val sources: SourceService, private val scans: ScanService) {
    private val random = SecureRandom()

    /**
     * Estimates skip indexes that do not exist yet. Each candidate becomes a hypothetical index in a session of its
     * own, then EXPLAIN WHATIF runs for the table's most frequent logged SELECTs. ClickHouse builds a candidate in
     * memory over the granules a query reads today, so this reads table data; nothing is written.
     */
    @PostMapping("/sources/{id}/databases/{db}/tables/{table}/index-whatif")
    fun estimate(@PathVariable id: String, @PathVariable db: String, @PathVariable table: String, @RequestBody request: WhatIfRequest): TableWhatIf {
        val source = sources.find(id)
        val facts = scans.snapshot(id)?.table(db, table)
            ?: throw NotFoundException("Table $db.$table was not in the last scan of ${source.name}. Scan the source first.")
        val wanted = request.candidates.map { it.copy(name = it.name.trim(), expression = it.expression.trim(), type = it.type.trim(), granularity = it.granularity.coerceAtLeast(1)) }
        if (wanted.isEmpty()) throw BadRequestException("Add a candidate index first")
        if (wanted.size > 8) throw BadRequestException("At most 8 candidates at a time")
        wanted.forEach { index ->
            if (!Regex("[A-Za-z_][A-Za-z0-9_]*").matches(index.name)) throw BadRequestException("Index name \"${index.name}\" must be letters, digits and underscores")
            if (index.expression.isBlank() || index.type.isBlank()) throw BadRequestException("Index ${index.name} needs an expression and a type")
            if ((index.expression + index.type).contains(';')) throw BadRequestException("Index ${index.name} must be a single expression and type")
        }
        if (wanted.map { it.name }.distinct().size != wanted.size) throw BadRequestException("Two candidates share a name")

        fun none(message: String) = TableWhatIf(message, 0, 0, 0, emptyList(), wanted.map { WhatIfCandidate(it.name, it.expression, it.type, it.granularity, null, 0, 0.0, 0, 0, emptyList()) })
        if (facts.queryTotal == null) return none("system.query_log cannot be read on ${source.name}, so there are no queries to estimate the candidates against.")
        if (facts.queries.isEmpty()) return none("No SELECT on this table in the last ${source.queryLogWindowDays} days of query_log.")
        val ch = sources.connection(source)
        if (!supported(ch)) return none("${source.name} has no EXPLAIN WHATIF. A trial measures a new index instead.")

        val target = Sql.table(db, table)
        val session = "granulee-whatif-" + String.format("%016x", random.nextLong())
        try {
            val refused = wanted.associate { index ->
                index.name to try {
                    ch.exec("CREATE HYPOTHETICAL INDEX ${Sql.id(index.name)} ON $target ${WhatIf.parenthesised(index.expression)} TYPE ${index.type} GRANULARITY ${index.granularity}", session = session)
                    null
                } catch (e: Exception) {
                    e.message ?: "The server refused this index"
                }
            }
            if (refused.values.all { it != null }) {
                return TableWhatIf(null, 0, 0, 0, emptyList(), wanted.map { WhatIfCandidate(it.name, it.expression, it.type, it.granularity, refused[it.name], 0, 0.0, 0, 0, emptyList()) })
            }

            val total = facts.queryTotal.coerceAtLeast(1)
            val settings = mapOf("max_execution_time" to "60")
            val reports = facts.queries.map { q ->
                // A query on a Buffer table in front is estimated against the table behind it.
                val sql = facts.buffers.fold(q.query) { text, buffer -> rewriteQuery(text, buffer.substringBefore('.'), buffer.substringAfter('.'), target) }
                q to try {
                    Result.success(WhatIf.parse(ch.query("EXPLAIN WHATIF ${Explain.clean(sql)}", settings, session).joinToString("\n") { it.str("explain") }))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            val shapes = reports.map { (q, report) ->
                val read = report.getOrNull()
                WhatIfShape(q.query, q.count, q.count.toDouble() / total, read?.parts, read?.granules, report.exceptionOrNull()?.message)
            }
            val candidates = wanted.map { index ->
                val uses = reports.withIndex().mapNotNull { (at, pair) ->
                    val report = pair.second.getOrNull() ?: return@mapNotNull null
                    val estimate = report.estimates.firstOrNull { it.name == index.name } ?: return@mapNotNull null
                    val applies = estimate.status == "applicable" && estimate.granulesAfter != null && report.granules != null
                    WhatIfUse(at, applies, estimate.reason, report.granules, estimate.granulesAfter, estimate.source, estimate.sampled)
                }
                val helped = uses.filter { it.applicable }
                WhatIfCandidate(
                    index.name, index.expression, index.type, index.granularity, refused[index.name],
                    shapes = helped.size,
                    share = helped.sumOf { reports[it.shape].first.count }.toDouble() / total,
                    granulesBefore = helped.sumOf { it.granulesBefore ?: 0 },
                    granulesAfter = helped.sumOf { it.granulesAfter ?: 0 },
                    uses = uses.sortedByDescending { it.applicable },
                )
            }
            return TableWhatIf(null, reports.size, reports.count { it.second.isFailure }, reports.sumOf { it.first.count }, shapes, candidates)
        } finally {
            runCatching { ch.exec("DROP ALL HYPOTHETICAL INDEXES", session = session) }
        }
    }

    companion object {
        /** EXPLAIN WHATIF came with the hypothetical indexes; their system table tells whether the server has it. */
        fun supported(ch: ChConnection): Boolean = try {
            (ch.first("SELECT count() AS c FROM system.tables WHERE database = 'system' AND name = 'hypothetical_indexes'")?.long("c") ?: 0) > 0
        } catch (e: Exception) {
            false
        }
    }
}
