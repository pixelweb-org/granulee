package io.granulee

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** One logged query shape and how ClickHouse plans it on the production table now. */
data class PlannedQuery(
    val query: String,
    val count: Long, // runs in the log window
    val share: Double, // of the table's SELECTs
    val plan: QueryExplain,
)

/** What the primary key or the partition key did for the logged queries that read the table itself. */
data class KeyUsage(
    val kind: String, // primary | partition
    val expression: String,
    val shapes: Int, // shapes the key narrows
    val share: Double, // of the table's SELECTs, by run count
    val binaryShare: Double?, // primary key only: narrowed by binary search
    val genericShare: Double?, // primary key only: narrowed by generic exclusion search, the slower way
    val granulesBefore: Long,
    val granulesAfter: Long,
    val queries: List<Int>,
)

/** The query shapes that touch one projection: planned onto it now, or logged as answered from it. */
data class ProjectionUsage(val name: String, val queries: List<Int>)

/** What one skip index did for the logged queries, measured with EXPLAIN on the production table. */
data class IndexUsage(
    val name: String,
    val expression: String,
    val type: String,
    val compressedBytes: Long,
    val shapes: Int, // query shapes whose plan uses the index
    val share: Double, // of the table's SELECTs, by run count
    val granulesBefore: Long, // handed to the index, over those shapes
    val granulesAfter: Long, // left after it
    val queries: List<Int> = emptyList(), // the shapes that use it, as positions in TableIndexUsage.queries
)

data class TableIndexUsage(
    val checkedShapes: Int,
    val failedShapes: Int,
    val selects: Long, // SELECTs in the log window the shapes stand for
    val message: String?,
    val indexes: List<IndexUsage>,
    val projections: List<ProjectionUsage> = emptyList(),
    val queries: List<PlannedQuery> = emptyList(), // every planned shape, most frequent first
    val keys: List<KeyUsage> = emptyList(),
    val proposals: List<TrialIndex> = emptyList(), // skip indexes the scan proposes for this table
    val whatIf: Boolean = false, // the server can estimate an index that does not exist yet
)

@RestController
@RequestMapping("/api")
class IndexController(private val sources: SourceService, private val scans: ScanService) {

    /**
     * Runs EXPLAIN indexes = 1 for the table's most frequent logged SELECTs, and for the ones logged as answered
     * from a projection. Per skip index it adds up how many granules it was given and how many it left, and it
     * tells which shapes use each index and each projection. Nothing is executed; planning reads index files only.
     */
    @GetMapping("/sources/{id}/databases/{db}/tables/{table}/index-usage")
    fun usage(@PathVariable id: String, @PathVariable db: String, @PathVariable table: String): TableIndexUsage {
        val source = sources.find(id)
        val facts = scans.snapshot(id)?.table(db, table)
            ?: throw NotFoundException("Table $db.$table was not in the last scan of ${source.name}. Scan the source first.")
        fun none(message: String) = TableIndexUsage(0, 0, 0, message, facts.indexes.map { IndexUsage(it.name, it.expression, it.type, it.compressedBytes, 0, 0.0, 0, 0) })
        if (facts.queryTotal == null) return none("system.query_log cannot be read on ${source.name}, so there are no queries to check the indexes and projections against.")
        if (facts.queries.isEmpty()) return none("No SELECT on this table in the last ${source.queryLogWindowDays} days of query_log.")

        val ch = sources.connection(source)
        val target = Sql.table(db, table)
        val total = facts.queryTotal.coerceAtLeast(1)
        val proposals = Analyzer.analyze(facts, source.queryLogWindowDays).mapNotNull { it.plan?.addIndex }
        val names = projections(ch, db, table, source.queryLogWindowDays).map { it.name }.toSet()

        // The shapes to plan, by normalized text: the table's most frequent ones, then those that read a projection.
        val shapes = LinkedHashMap<String, QueryFacts>()
        facts.queries.forEach { shapes.putIfAbsent(it.normalized, it) }
        val logged = if (names.isEmpty()) emptyMap() else projectionReads(ch, "$db.$table.", source.queryLogWindowDays)
        logged.values.flatten().forEach { shapes.putIfAbsent(it.normalized, it) }

        val settings = Explain.planned(ch) + mapOf("max_execution_time" to "30")
        val planned = shapes.values.sortedByDescending { it.count }.map { q ->
            // A query on a Buffer table in front is planned against the table behind it.
            val sql = facts.buffers.fold(q.query) { text, buffer -> rewriteQuery(text, buffer.substringBefore('.'), buffer.substringAfter('.'), target) }
            q to Explain.of(ch, sql, "$db.$table", pipeline = false, settings = settings, projections = names)
        }
        val indexes = facts.indexes.map { index ->
            val used = planned.withIndex().mapNotNull { (at, pair) ->
                pair.second.steps.firstOrNull { it.kind == "skip" && it.name == index.name }?.let { Triple(at, pair.first, it) }
            }
            IndexUsage(
                index.name, index.expression, index.type, index.compressedBytes,
                shapes = used.size,
                share = used.sumOf { it.second.count }.toDouble() / total,
                granulesBefore = used.sumOf { it.third.granulesBefore },
                granulesAfter = used.sumOf { it.third.granulesAfter },
                queries = used.map { it.first },
            )
        }
        val projectionUse = names.sorted().map { name ->
            val read = logged[name].orEmpty().map { it.normalized }.toSet()
            ProjectionUsage(name, planned.withIndex().filter { (_, pair) -> pair.second.projection == name || pair.first.normalized in read }.map { it.index })
        }
        // The keys only count for reads of the table itself: a projection has a primary key of its own.
        val own = planned.withIndex().filter { it.value.second.projection == null }
        fun narrowing(plan: QueryExplain, kinds: Set<String>) = plan.steps.filter { it.kind in kinds && it.condition != null && it.condition != "true" }
        fun keyUsage(kind: String, expression: String, kinds: Set<String>): KeyUsage? {
            if (expression.isBlank()) return null
            val used = own.mapNotNull { (at, pair) -> narrowing(pair.second, kinds).takeIf { it.isNotEmpty() }?.let { Triple(at, pair.first, it) } }
            fun shareOf(algorithm: String) = used.filter { (_, _, steps) -> steps.any { it.searchAlgorithm.orEmpty().startsWith(algorithm) } }
                .sumOf { it.second.count }.toDouble() / total
            return KeyUsage(
                kind, expression,
                shapes = used.size,
                share = used.sumOf { it.second.count }.toDouble() / total,
                binaryShare = if (kind == "primary") shareOf("binary") else null,
                genericShare = if (kind == "primary") shareOf("generic") else null,
                granulesBefore = used.sumOf { it.third.first().granulesBefore },
                granulesAfter = used.sumOf { it.third.last().granulesAfter },
                queries = used.map { it.first },
            )
        }
        val keys = listOfNotNull(
            keyUsage("primary", facts.primaryKey.ifBlank { facts.sortingKey }, setOf("primary")),
            keyUsage("partition", facts.partitionKey, setOf("minmax", "partition")),
        )
        return TableIndexUsage(
            checkedShapes = planned.size,
            failedShapes = planned.count { it.second.error != null },
            selects = planned.sumOf { it.first.count },
            message = null,
            indexes = indexes,
            projections = projectionUse,
            queries = planned.map { (q, plan) -> PlannedQuery(q.query, q.count, q.count.toDouble() / total, plan) },
            keys = keys,
            proposals = proposals,
            whatIf = WhatIfController.supported(ch),
        )
    }

    /** Per projection name, the five most frequent SELECT shapes the log says were answered from it. */
    private fun projectionReads(ch: ChConnection, prefix: String, days: Int): Map<String, List<QueryFacts>> = try {
        ch.query(
            """
            SELECT p, substring(q, 1, 20000) AS q, substring(normalizeQuery(q), 1, 4000) AS nq, toInt64(c) AS c
            FROM (
                SELECT p, any(query) AS q, count() AS c
                FROM system.query_log ARRAY JOIN projections AS p
                WHERE event_date >= today() - $days AND type = 'QueryFinish' AND query_kind = 'Select' AND is_initial_query
                  AND $NOT_GRANULEE AND startsWith(p, ${Sql.lit(prefix)})
                GROUP BY p, normalized_query_hash
            )
            ORDER BY c DESC
            LIMIT 5 BY p
            """.trimIndent(),
        ).groupBy({ it.str("p").removePrefix(prefix) }) { QueryFacts(it.str("q"), it.str("nq"), it.long("c")) }
    } catch (e: Exception) {
        emptyMap()
    }
}
